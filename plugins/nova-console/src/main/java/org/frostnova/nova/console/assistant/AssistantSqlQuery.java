package org.frostnova.nova.console.assistant;

import com.alibaba.fastjson2.JSONFactory;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONReader;
import org.frostnova.nova.core.analytics.LiveMetricCatalog;
import org.frostnova.nova.core.analytics.StatisticsDay;
import org.frostnova.nova.core.analytics.StreamerStartSlots;
import org.frostnova.nova.core.lang.JsonlFiles;
import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.service.LiveSessionArchive;
import org.sqlite.SQLiteConnection;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.IntPredicate;
import java.util.function.LongFunction;

import static org.frostnova.nova.console.assistant.AssistantAggregateQuery.*;
import static org.frostnova.nova.console.assistant.AssistantAggregateRequest.Kind;
import static org.frostnova.nova.console.assistant.AssistantAudienceHistory.DANMU;
import static org.frostnova.nova.console.assistant.AssistantAudienceHistory.EVENTS;
import static org.frostnova.nova.console.assistant.AssistantAudienceHistory.MAX_SESSION_CHARS;
import static org.frostnova.nova.console.assistant.AssistantAudienceIndex.NO_DAY;
import static org.frostnova.nova.console.assistant.AssistantSqlRequest.Table;
import static org.frostnova.nova.console.assistant.AssistantSqlRequest.rejected;
import static org.frostnova.nova.core.service.LiveDataService.SERIES_BUCKET_MILLIS;

/** 将请求窗口内选中的归档行流式写入一次性的数据库。 */
final class AssistantSqlQuery {
    private static final String DETAIL = "detail.json";
    private static final List<Kind> EVENT_KINDS = List.of(Kind.GIFT, Kind.BOX, Kind.GUARD, Kind.FOLLOW, Kind.PK);
    private static final Set<String> TIMED = Set.of("at", "uid", "t", "type");
    private static final ReentrantLock DATABASE_LOCK = new ReentrantLock();

    private final AssistantSqlRequest request;
    private final AssistantPrivacy privacy;
    private final boolean amountVisible;
    private final long from;
    private final long to;

    AssistantSqlQuery(AssistantSqlRequest request, AssistantQueryContext context, AssistantPrivacy privacy) {
        this.request = request;
        this.privacy = privacy;
        this.amountVisible = context.amountVisible();
        from = request.fromAt();
        to = request.toAt();
    }

    JSONObject run(AssistantAudienceHistory historyCache, AssistantAudienceHistory.Key key, LiveSessionArchive archive, LiveMetricCatalog.Insights rules,
                   BiFunction<Long, String, Path> file, Long liveStart, IntPredicate pauseHistory) {
        if (!"bilibili".equals(key.platform()) && request.tables().stream().anyMatch(table -> table != Table.SESSIONS))
            return rejected("此平台只提供场次汇总");
        if (!AssistantSqlNative.available()) return AssistantSqlNative.unavailable();
        try (var progress = has(Table.AUDIENCE)
                ? historyCache.load(key, archive, file, pauseHistory, historyReader(file)) : null) {
            if (progress != null && progress.view() == null) {
                return rejected("观众首次出现记录还在建立，已完成 "
                        + progress.completedSessions() + "/" + progress.totalSessions() + " 场，请稍后再问");
            }
            var history = progress == null ? null : progress.view();
            return runWithSnapshot(key, archive, rules, file, liveStart, history);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return rejected("只读查询已中止");
        } catch (AssistantSqlRequest.Invalid | AssistantAggregateRequest.Invalid error) {
            return rejected(error.getMessage());
        } catch (SQLException error) {
            return rejected(AssistantSqlDatabase.reason(error));
        } catch (IOException | UncheckedIOException error) {
            Throwable cause = error instanceof UncheckedIOException ? error.getCause() : error;
            return rejected(cause instanceof JsonlFiles.LineTooLongException
                    ? "归档中有超长记录，请缩短时间范围后重试" : "无法完整读取归档，请稍后再试");
        } catch (LinkageError error) {
            return rejected("无法加载当前系统的 SQLite 运行库");
        }
    }

    private JSONObject runWithSnapshot(AssistantAudienceHistory.Key key, LiveSessionArchive archive,
                                       LiveMetricCatalog.Insights rules, BiFunction<Long, String, Path> file,
                                       Long liveStart, AssistantAudienceIndex.View history)
            throws InterruptedException, SQLException, IOException {
        // 同时只让一份库占用内存，装库连接关闭后再打开只读快照。
        DATABASE_LOCK.lockInterruptibly();
        try {
            var loader = new Loader(file, rules, history);
            byte[] snapshot;
            try (var db = AssistantSqlDatabase.open()) {
                loader.load(db, key, archive, liveStart);
                snapshot = db.getDatabase().serialize("main");
            }
            if (history != null) AssistantAudienceHistory.checkUnchanged(key, file, history);
            Set<String> tables = request.tables().stream().map(Table::key).collect(java.util.stream.Collectors.toUnmodifiableSet());
            JSONObject result = AssistantSqlNative.select(snapshot, tables, request.sql());
            if (!result.containsKey("reason")) loader.coverage(result);
            return result;
        } finally {
            DATABASE_LOCK.unlock();
        }
    }

    private boolean has(Table table) { return request.tables().contains(table); }

    private boolean inWindow(long at) { return at >= from && at < to; }

    private static String day(long at) { return StatisticsDay.dateOf(Instant.ofEpochMilli(at)).toString(); }

    private final class Loader {
        private final BiFunction<Long, String, Path> file;
        private final LiveMetricCatalog.Insights rules;
        private final AssistantAudienceIndex.View history;
        private final EnumMap<Table, PreparedStatement> inserts = new EnumMap<>(Table.class);
        private final Set<Long> visited = new HashSet<>();
        private final Set<Long> seenAudience = new HashSet<>();
        private final Set<String> eventFields = fields(false);
        private final Set<String> danmuFields = fields(true);
        private final Detail detail = new Detail();
        private final JSONReader.Context reader = JSONFactory.createReadContext();
        private LongFunction<String> aliases;
        private int rows;
        private int invalidLines;
        private int missingEvents;
        private int missingDanmu;
        private int missingMinutes;

        Loader(BiFunction<Long, String, Path> file, LiveMetricCatalog.Insights rules, AssistantAudienceIndex.View history) {
            this.file = file;
            this.rules = rules;
            this.history = history;
        }

        void load(SQLiteConnection db, AssistantAudienceHistory.Key key, LiveSessionArchive archive, Long liveStart)
                throws SQLException, IOException {
            db.setAutoCommit(false);
            try (var statement = db.createStatement()) {
                for (Table table : request.tables()) {
                    String columns = table.columns(amountVisible);
                    statement.executeUpdate("CREATE TABLE " + table.key() + "(" + columns + ")");
                    int count = columns.split(",").length;
                    inserts.put(table, db.prepareStatement("INSERT INTO " + table.key()
                            + " VALUES (" + String.join(",", Collections.nCopies(count, "?")) + ")"));
                }
                archive.forEachOverlapping(key.platform(), key.uid(), from, to, has(Table.EVENTS) || has(Table.AUDIENCE),
                        MAX_SESSION_CHARS, session -> loadSession(session, liveStart, "bilibili".equals(key.platform())));
                db.setAutoCommit(true);
            } catch (SqlFailure error) {
                throw error.cause;
            } finally {
                // 连接负责在任一装库步骤失败时清理剩余的语句和事务。
                for (var insert : inserts.values()) insert.close();
            }
        }

        private Set<String> fields(boolean danmu) {
            var fields = new HashSet<>(TIMED);
            if (danmu && has(Table.DANMU)) fields.add("text");
            if (!danmu && has(Table.SESSIONS)) fields.add("rs");
            if (has(Table.EVENTS)) {
                if (!danmu) fields.addAll(List.of("gn", "bn", "n", "lv", "rs"));
                if (amountVisible) fields.addAll(danmu ? List.of("val") : List.of("val", "pay", "bp"));
            }
            return fields;
        }

        private void loadSession(LiveSession session, Long liveStart, boolean bilibili) {
            long start = session.startTime();
            if (!archived(session, liveStart) || !visited.add(start)) return;
            boolean openingInWindow = inWindow(start);
            boolean summary = has(Table.SESSIONS) && openingInWindow;
            boolean readEvents = bilibili && (summary || has(Table.EVENTS) || history != null);
            boolean readDanmu = has(Table.DANMU) || has(Table.EVENTS) || history != null;
            Set<Long> roster = openingInWindow && (has(Table.EVENTS) || history != null)
                    ? roster(session, count -> checkInterrupted()) : Collections.emptySet();
            var pk = new Pk();
            if (readEvents) loadDetails(session, false, roster, summary ? pk : null);
            if (readDanmu) loadDetails(session, true, roster, null);
            for (Long uid : roster) {
                checkInterrupted();
                String alias = alias(uid);
                if (alias == null) continue;
                addAudience(uid, alias);
                if (has(Table.EVENTS)) insertEvent(start, start, "attendance", null, 1, alias, null, null, null);
            }
            if (summary) insertSession(session, pk);
            if (has(Table.MINUTES)) loadMinutes(session);
        }

        private void loadDetails(LiveSession session, boolean danmu, Set<Long> roster, Pk pk) {
            long start = session.startTime();
            Path path = file.apply(start, danmu ? DANMU : EVENTS);
            boolean present = scan(path, line -> {
                try {
                    detail.read(line, danmu ? danmuFields : eventFields, reader, true);
                } catch (RuntimeException ignored) {
                    invalidLines++;
                    return;
                }
                if (!detail.timed(danmu)) return;
                Kind kind = detail.kind(danmu);
                if (pk != null && kind == Kind.PK) pk.observe(detail.pkResult);
                long at = detail.at();
                if (!inWindow(at)) return;
                boolean putEvent = has(Table.EVENTS) && kind != null && kind != Kind.DANMU;
                boolean putDanmu = has(Table.DANMU) && kind == Kind.DANMU;
                if (!putEvent && !putDanmu && history == null) return;
                String alias = alias(detail.audience);
                if (alias != null) addAudience(detail.audience, alias);
                if (putEvent) {
                    if (!roster.isEmpty()) roster.remove(detail.audience);
                    String name = switch (kind) {
                        case GIFT -> detail.giftName;
                        case BOX -> detail.boxName;
                        case GUARD -> detail.guardLevel;
                        default -> null;
                    };
                    boolean paidEvent = kind == Kind.GIFT || kind == Kind.BOX || kind == Kind.GUARD || kind == Kind.SUPER_CHAT;
                    Object quantity = paidEvent && kind != Kind.SUPER_CHAT ? detail.quantity : 1;
                    insertEvent(at, start, kind.key, name, quantity, alias, detail.pkResult,
                            paidEvent ? detail.amount : null, paidEvent ? detail.paid(kind) : null);
                }
                if (putDanmu) {
                    insert(Table.DANMU, at, day(at), start, alias, detail.characters);
                }
            });
            if (danmu) {
                if (!present && !knownEmpty(path, session.metrics(),
                        has(Table.DANMU) ? List.of(Kind.DANMU, Kind.SUPER_CHAT) : List.of(Kind.SUPER_CHAT))) missingDanmu++;
            } else {
                if (pk != null) pk.available = present;
                if (!present && !knownEmpty(path, session.metrics(), EVENT_KINDS)) missingEvents++;
            }
        }

        private void insertEvent(long at, long start, String kind, String name, Object quantity, String alias,
                                 String pk, BigDecimal amount, BigDecimal paid) {
            if (amountVisible) insert(Table.EVENTS, at, day(at), start, kind, name, quantity, alias, pk, amount, paid);
            else insert(Table.EVENTS, at, day(at), start, kind, name, quantity, alias, pk);
        }

        private void insertSession(LiveSession session, Pk pk) {
            var metrics = session.metrics();
            long start = session.startTime();
            Double messages = rules == null ? null : metrics.get(rules.messageKey());
            Double peak = session.peak("online_count").map(value -> value.value()).orElse(null);
            long effective = StreamerStartSlots.effectiveSeconds(session);
            Integer wins = pk.available ? pk.wins : null;
            Integer losses = pk.available ? pk.losses : null;
            Integer draws = pk.available ? pk.draws : null;
            if (amountVisible) {
                Double revenue = rules == null || rules.revenueKeys().isEmpty() || metrics.isEmpty() ? null : rules.revenue(metrics);
                insert(Table.SESSIONS, start, day(start), session.endTime(), session.durationSeconds(), effective,
                        messages, peak, metrics.get("watched_count"), pk.result(), wins, losses, draws, revenue,
                        metrics.get("gift_value"), metrics.get("guard_value"));
            } else {
                insert(Table.SESSIONS, start, day(start), session.endTime(), session.durationSeconds(), effective,
                        messages, peak, metrics.get("watched_count"), pk.result(), wins, losses, draws);
            }
        }

        private void loadMinutes(LiveSession session) {
            long start = session.startTime();
            long first = Math.max(from, minuteAt(start));
            long end = Math.min(to, session.endTime());
            if (end <= Math.max(from, start)) return;
            long count = (end - 1 - first) / SERIES_BUCKET_MILLIS + 1;
            checkRows(count);
            AssistantMinuteSeries samples;
            try {
                samples = AssistantMinuteSeries.read(file.apply(start, DETAIL), first, (int) count);
            } catch (NoSuchFileException error) {
                samples = null;
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
            if (samples == null) missingMinutes++;
            for (int i = 0; i < count; i++) {
                long at = first + i * SERIES_BUCKET_MILLIS;
                insert(Table.MINUTES, at, day(at), start, samples == null ? null : samples.online()[i],
                        samples == null ? null : samples.watched()[i]);
            }
        }

        private String alias(Long uid) {
            if (!privacy.canAttribute(uid)) return null;
            if (aliases == null) aliases = privacy.aliases();
            return aliases.apply(uid);
        }

        private void addAudience(long uid, String alias) {
            if (history == null || !seenAudience.add(uid)) return;
            try {
                int first = history.firstDay(uid);
                insert(Table.AUDIENCE, alias, first == NO_DAY ? null : LocalDate.ofEpochDay(first).toString());
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }

        private void insert(Table table, Object... values) {
            checkInterrupted();
            checkRows(1);
            var statement = inserts.get(table);
            try {
                for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
                statement.executeUpdate();
                rows++;
            } catch (SQLException error) {
                throw new SqlFailure(error);
            }
        }

        void coverage(JSONObject result) {
            result.put("loadedRows", rows);
            if (history != null) {
                int earliest = history.progress.earliestDay();
                result.put("historyFrom", earliest == NO_DAY ? null : LocalDate.ofEpochDay(earliest).toString());
            }
            if (missingEvents > 0 || missingDanmu > 0 || missingMinutes > 0 || invalidLines > 0) {
                result.put("coverageNote", "部分场次缺少明细或含坏行，缺少记录不能视为零");
                result.put("missingEventsSessions", missingEvents);
                result.put("missingDanmuSessions", missingDanmu);
                result.put("missingMinutesSessions", missingMinutes);
                result.put("invalidLines", invalidLines);
            }
        }

        private void checkRows(long count) {
            if (count > AssistantQueryService.LINE_LIMIT - rows)
                throw new AssistantSqlRequest.Invalid("装库超过 50 万行上限，请缩短时间范围或减少所选表");
        }
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new AssistantSqlRequest.Invalid("只读查询装库已中止");
    }

    private static final class Pk {
        boolean available;
        int wins;
        int losses;
        int draws;

        void observe(String result) {
            if (result == null) return;
            switch (result) {
                case "win" -> wins++;
                case "loss" -> losses++;
                case "draw" -> draws++;
                default -> throw new IllegalArgumentException();
            }
        }

        String result() {
            if (wins > 0) return losses > 0 || draws > 0 ? "mixed" : "win";
            if (losses > 0) return draws > 0 ? "mixed" : "loss";
            return draws > 0 ? "draw" : null;
        }
    }

    private static final class SqlFailure extends RuntimeException {
        final SQLException cause;

        SqlFailure(SQLException cause) {
            super(cause);
            this.cause = cause;
        }
    }
}
