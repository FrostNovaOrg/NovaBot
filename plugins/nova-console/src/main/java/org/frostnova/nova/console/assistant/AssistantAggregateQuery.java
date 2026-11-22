package org.frostnova.nova.console.assistant;

import com.alibaba.fastjson2.JSONFactory;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONReader;
import org.frostnova.nova.core.analytics.LiveMetricCatalog;
import org.frostnova.nova.core.analytics.StatisticsDay;
import org.frostnova.nova.core.analytics.StreamerStartSlots;
import org.frostnova.nova.core.lang.JsonlFiles;
import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.service.LiveSessionArchive;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;

import static org.frostnova.nova.core.service.LiveDataService.SERIES_BUCKET_MILLIS;
import static org.frostnova.nova.console.assistant.AssistantAggregateRequest.*;
import static org.frostnova.nova.console.assistant.AssistantAudienceHistory.*;
import static org.frostnova.nova.console.assistant.AssistantAudienceIndex.NO_DAY;

/** 一次查询的流式汇总。内存中只保留分组状态、去重集合和有限的回包样本。 */
final class AssistantAggregateQuery {
    static final String EVENT_SOURCE = "已归档的场次事件明细";
    static final String SESSION_SOURCE = "已归档的场次汇总";
    static final String MINUTE_SOURCE = "已归档的场次逐分钟人数序列";
    static final String DANMU_SOURCE = "已归档的场次弹幕明细";
    static final String AUDIENCE_SOURCE = "已归档的观众名单、事件和弹幕明细";
    static final String DAY_RULE = "北京时间 06:00 换统计日，起止日期均含当天。事件按发生时刻，场次按开播时刻整场归入";
    static final String AMOUNT_BASIS = "到手价值，与控制台流水同口径。盲盒计开出物价值，背包礼物计入流水";
    static final String MINUTE_RULE = "在线和看过人数每 60 秒一格取最大，按格起点归组。平均和极值各自跳过缺采分钟，全缺时返回 null。"
            + "缺采数按筛选读数前的直播分钟计算";
    static final String SESSION_COUNT_RULE = "按 hour 或 weekday 分组时，sessionCount 是有数据的不同直播场数。"
            + "同时汇总在线和看过人数时，用 onlineSessionCount、watchedSessionCount 分别计各自有读数的场数";
    static final String KNOWN_AMOUNT_RULE = "knownAmountSubtotal 仅合计已知金额，完整合计仍未知。"
            + "missingAmountRows 是缺金额的记录数，missingAmountsByDayAndKind 按发生统计日列各类缺失条数";
    static final String HISTORY_RULE = "以可读取的进房等名单、事件或弹幕判断首次留档。同场有逐条记录时用其最早时刻，仅在名单上时按开播时刻估算。"
            + "窗口内首次留档为新观众，窗口前首次留档为老观众。留档起点之前来过的人可能计作新观众";
    static final String ZERO_RULE = "无记录的弹幕、礼物时段按 0 计数，缺少明细的时段显示未知";
    private static final String DETAIL = "detail.json";
    private static final int TABLE_ROWS = 60;
    private static final int MAX_EVENT_CHARS = 16_384;
    private static final int MAX_GROUPS = 50_000;
    private static final int MAX_GROUP_TEXT_CHARS = 2_000_000;
    private static final Set<String> HISTORY_FIELDS = Set.of("at", "uid", "t", "type");
    private static final List<Kind> AUDIENCE_EVENTS = List.of(Kind.GIFT, Kind.BOX, Kind.GUARD, Kind.FOLLOW);
    private static final List<Kind> AUDIENCE_DANMU = List.of(Kind.DANMU, Kind.SUPER_CHAT);

    private final AssistantAggregateRequest request;
    private final AssistantPrivacy privacy;
    private final AssistantAudienceHistory historyCache;
    private final Path sessionsFile;
    private final boolean sessions;
    private final boolean series;
    private final boolean audienceActivity;
    private final boolean byAge;
    private final boolean needsDistinct;
    private final boolean needsPaying;
    private final boolean byAudience;
    private final boolean byMinute;
    private final boolean danmuByDay;
    private final boolean usesSessionAudience;
    private final List<Filter> startFilters;
    private final long from;
    private final long to;
    private final long groupMillis;
    private final List<Field> numericFields;
    private final List<Field> sessionSampleFields;
    private final List<String> sessionCountColumns;
    private final int eventAmountIndex;
    private final Set<Field> fields;
    private final Set<String> eventFields;
    private final JSONReader.Context readerContext = JSONFactory.createReadContext();
    private final Detail detail = new Detail();
    private final Map<Key, Bucket> groups = new HashMap<>();
    private final Map<String, EnumMap<Kind, Integer>> missingAmounts = new TreeMap<>();
    private final Bucket total;
    private final JSONArray missingDetails = new JSONArray();
    private int missingDetailsCount;
    private boolean hasAvailableDetails;
    private int scanWork;
    private int linesScanned;
    private long historyRecordsScanned;
    private int expectedMinutes;
    private int missingOnline;
    private int missingWatched;
    private int invalidLines;
    private int unknownFilters;
    private int unknownValues;
    private int groupTextChars;
    private LongFunction<String> aliases;
    private ClockGroups clockGroups;
    private AssistantAudienceIndex.View history;

    AssistantAggregateQuery(AssistantAggregateRequest request, AssistantPrivacy privacy,
                            AssistantAudienceHistory historyCache, Path sessionsFile) {
        this.request = request;
        this.privacy = privacy;
        this.historyCache = historyCache;
        this.sessionsFile = sessionsFile;
        sessions = request.kinds().contains(Kind.SESSION);
        series = request.kinds().contains(Kind.MINUTE);
        audienceActivity = request.kinds().contains(Kind.AUDIENCE_ACTIVITY);
        byAge = request.groups().contains(Group.AUDIENCE_AGE);
        needsDistinct = request.measures().stream().anyMatch(m -> m.operation() == Operation.DISTINCT);
        needsPaying = request.measures().stream().anyMatch(m -> m.operation() == Operation.DISTINCT_PAYING);
        byAudience = request.groups().contains(Group.AUDIENCE);
        byMinute = request.groups().contains(Group.MINUTE);
        danmuByDay = request.kinds().contains(Kind.DANMU) && request.groups().equals(List.of(Group.DAY));
        groupMillis = SERIES_BUCKET_MILLIS * (request.bucketMinutes() == null ? 1 : request.bucketMinutes());
        from = StatisticsDay.startOf(request.from()).toEpochMilli();
        to = StatisticsDay.startOf(request.to().plusDays(1)).toEpochMilli();
        startFilters = request.filters().stream().filter(f -> f.field() == Field.START).toList();
        numericFields = request.measures().stream().map(Measure::field).filter(Objects::nonNull).distinct().toList();
        boolean byClock = request.groups().contains(Group.HOUR) || request.groups().contains(Group.WEEKDAY);
        sessionSampleFields = byClock ? numericFields.stream().filter(this::isSample).toList() : List.of();
        if (!byClock) sessionCountColumns = List.of();
        else if (sessionSampleFields.size() < 2) sessionCountColumns = List.of("sessionCount");
        else sessionCountColumns = sessionSampleFields.stream().map(f -> f.key + "SessionCount").toList();
        boolean sumEventAmount = !sessions && request.measures().stream()
                .anyMatch(m -> m.field() == Field.AMOUNT && m.operation() == Operation.SUM);
        eventAmountIndex = sumEventAmount ? numericFields.indexOf(Field.AMOUNT) : -1;
        fields = EnumSet.noneOf(Field.class);
        fields.addAll(numericFields);
        request.filters().forEach(filter -> fields.add(filter.field()));
        request.groups().stream().map(group -> group.field).filter(Objects::nonNull).forEach(fields::add);
        usesSessionAudience = fields.contains(Field.SESSION_AUDIENCE);
        eventFields = sessions || series ? Set.of() : eventFields();
        total = new Bucket(needsDistinct, needsPaying, numericFields.size(), sessionCountColumns.size());
    }

    JSONObject run(String platform, long uid, LiveSessionArchive archive, LiveMetricCatalog.Insights rules,
                   BiFunction<Long, String, Path> file, boolean living, Long liveStart, Instant now,
                   AssistantQueryContext context, IntPredicate pauseHistory) {
        if (!context.amountVisible() && request.usesAmount()) return rejected(source(), HIDDEN_AMOUNT);
        if (!sessions && !"bilibili".equals(platform)) {
            return rejected(source(), series ? "此平台没有提供逐分钟人数序列" : "此平台没有提供这些事件明细");
        }
        boolean liveUnarchived = living && now.toEpochMilli() >= from
                && (liveStart == null ? now.toEpochMilli() < to : liveStart < to);
        var historyKey = new AssistantAudienceHistory.Key(platform, uid, liveStart == null ? 0 : liveStart, sessionsFile);
        try (var historyResult = byAge ? historyCache.load(historyKey, archive, file, pauseHistory,
                historyReader(file)) : null) {
            if (historyResult != null) {
                history = historyResult.view();
                historyRecordsScanned = historyResult.scannedRecords();
                if (history == null) return historyInProgress(historyResult);
            }
            if (sessions) {
                Map<Long, Row> summaries = new HashMap<>();
                archive.forEachOverlapping(platform, uid, from, to, usesSessionAudience, MAX_SESSION_CHARS, session -> {
                    if (archived(session, liveStart) && session.startTime() >= from) {
                        summaries.put(session.startTime(), summary(session, rules));
                        if (summaries.size() > MAX_GROUPS) throw stateLimit();
                    }
                });
                summaries.values().forEach(this::collect);
            } else if (series) {
                scanMinutes(platform, uid, archive, file, liveStart);
            } else {
                scanEvents(platform, uid, archive, file, liveStart);
            }
            if (history != null) checkUnchanged(historyKey, file, history);
            return table(liveUnarchived, liveStart, context);
        } catch (Invalid error) {
            return rejected(source(), error.getMessage());
        } catch (IOException | UncheckedIOException error) {
            Throwable cause = error instanceof UncheckedIOException ? error.getCause() : error;
            String reason = cause instanceof JsonlFiles.LineTooLongException
                    ? "归档中有超长记录，请缩短时间范围后重试" : "无法完整读取归档，请稍后再试";
            return rejected(source(), reason);
        }
    }

    static boolean archived(LiveSession session, Long liveStart) {
        return session.startTime() > 0 && session.endTime() >= session.startTime()
                && !Objects.equals(session.startTime(), liveStart);
    }

    private boolean selectedStart(long start) {
        if (startFilters.isEmpty()) return true;
        var value = BigDecimal.valueOf(start);
        return startFilters.stream().allMatch(f -> f.comparison().matches(value.compareTo((BigDecimal) f.value())));
    }

    private JSONObject historyInProgress(AssistantAudienceHistory.Result progress) {
        int completed = progress.completedSessions(), total = progress.totalSessions();
        var result = rejected(source(), "观众首次出现记录还在建立，已完成 " + completed + "/" + total + " 场，请稍后再问");
        result.put("historyCompletedSessions", completed);
        result.put("historyTotalSessions", total);
        result.put("historyScannedRecords", progress.scannedRecords());
        return result;
    }

    static AssistantAudienceHistory.SessionReader historyReader(BiFunction<Long, String, Path> file) {
        Detail detail = new Detail();
        var context = JSONFactory.createReadContext();
        return (session, changes) -> {
            long start = session.startTime();
            changes.observe(start);
            Set<Long> roster = roster(session, changes::count);
            scanHistoryFile(file.apply(start, EVENTS), false, roster, changes, detail, context);
            scanHistoryFile(file.apply(start, DANMU), true, roster, changes, detail, context);
            roster.forEach(person -> changes.observe(person, start));
        };
    }

    private static void scanHistoryFile(Path path, boolean danmu, Set<Long> roster, Changes changes,
                                        Detail detail, JSONReader.Context context) {
        scan(path, line -> {
            changes.count(1);
            try {
                detail.read(line, HISTORY_FIELDS, context);
            } catch (RuntimeException error) {
                changes.skipInvalidLine();
                return;
            }
            if (!detail.timed(danmu)) return;
            if (detail.audience != null && detail.audience > 0) {
                roster.remove(detail.audience);
                changes.observe(detail.audience, detail.at);
            } else {
                changes.observe(detail.at);
            }
        });
    }

    static Set<Long> roster(LiveSession session, LongConsumer count) {
        var roster = new HashSet<Long>();
        for (var people : session.userSets().values()) {
            count.accept(people.size());
            for (Long person : people) if (person != null && person > 0) roster.add(person);
        }
        return roster;
    }

    private static boolean visitSession(Set<Long> visited, long start) {
        boolean fresh = visited.add(start);
        if (visited.size() > MAX_GROUPS) throw stateLimit();
        return fresh;
    }

    private void scanMinutes(String platform, long uid, LiveSessionArchive archive,
                             BiFunction<Long, String, Path> file, Long liveStart) throws IOException {
        var visited = new HashSet<Long>();
        archive.forEachOverlapping(platform, uid, from, to, false, MAX_SESSION_CHARS, session -> {
            long start = session.startTime();
            if (!archived(session, liveStart) || !selectedStart(start) || !visitSession(visited, start)) return;
            long first = firstMinute(start);
            int minutes = minuteCount(session, first);
            if (minutes == 0) return;
            expectedMinutes += minutes;
            var samples = readSamples(file.apply(start, DETAIL), first, minutes);
            if (samples == null) addMissing(start, JSONArray.of(DETAIL));
            else hasAvailableDetails = true;
            for (int i = 0; i < minutes; i++) {
                BigDecimal online = samples == null ? null : samples.online()[i];
                BigDecimal watched = samples == null ? null : samples.watched()[i];
                if (online == null) missingOnline++;
                if (watched == null) missingWatched++;
                if (Thread.currentThread().isInterrupted()) throw new Invalid("查数已中止");
                collect(new Minute(start, first + i * SERIES_BUCKET_MILLIS, online, watched));
            }
        });
    }

    private AssistantMinuteSeries readSamples(Path path, long first, int count) {
        try {
            var samples = AssistantMinuteSeries.read(path, first, count);
            if (samples == null) invalidLines++;
            return samples;
        } catch (NoSuchFileException ignored) {
            return null;
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private long firstMinute(long start) { return Math.max(from, minuteAt(start)); }

    private int minuteCount(LiveSession session, long first) {
        long end = Math.min(to, session.endTime());
        if (end <= Math.max(from, session.startTime())) return 0;
        long count = (end - 1 - first) / SERIES_BUCKET_MILLIS + 1;
        charge(count);
        return (int) count;
    }

    static long minuteAt(long at) { return Math.floorDiv(at, SERIES_BUCKET_MILLIS) * SERIES_BUCKET_MILLIS; }

    private long minuteGroup(long at, long start) {
        long first = minuteAt(start);
        return first + Math.floorDiv(at - first, groupMillis) * groupMillis;
    }

    private void seedEventMinutes(LiveSession session, boolean missing) {
        long first = firstMinute(session.startTime());
        int minutes = minuteCount(session, first);
        if (minutes == 0) return;
        long end = first + minutes * SERIES_BUCKET_MILLIS;
        long start = session.startTime();
        var groupKeys = request.groups();
        for (long at = minuteGroup(first, start); at < end; at += groupMillis) {
            Key key = groupKeys.get(0) == Group.SESSION ? new Key(start, at) : new Key(at, groupKeys.size() == 2 ? start : null);
            groups.computeIfAbsent(key, this::bucket).incomplete |= missing;
        }
    }

    private void markMissingDays(LiveSession session) {
        var first = StatisticsDay.dateOf(Instant.ofEpochMilli(Math.max(from, session.startTime())));
        var last = StatisticsDay.dateOf(Instant.ofEpochMilli(Math.min(to - 1, session.endTime())));
        for (var day = first; !day.isAfter(last); day = day.plusDays(1)) {
            groups.get(new Key(day.toString(), null)).incomplete = true;
        }
    }

    private void addMissing(long start, JSONArray files) {
        missingDetailsCount++;
        if (missingDetails.size() < TABLE_ROWS) missingDetails.add(JSONObject.of("session", start, "files", files));
    }

    private void scanEvents(String platform, long uid, LiveSessionArchive archive,
                            BiFunction<Long, String, Path> file, Long liveStart) throws IOException {
        List<Kind> danmu = audienceActivity ? AUDIENCE_DANMU : request.kinds().stream().filter(AssistantAggregateQuery::isDanmu).toList();
        List<Kind> events = audienceActivity ? AUDIENCE_EVENTS : request.kinds().stream().filter(k -> !isDanmu(k)).toList();
        if (danmuByDay) {
            for (var day = request.from(); !day.isAfter(request.to()); day = day.plusDays(1)) {
                groups.computeIfAbsent(new Key(day.toString(), null), this::bucket);
            }
        }
        var visited = new HashSet<Long>();
        archive.forEachOverlapping(platform, uid, from, to, audienceActivity, MAX_SESSION_CHARS, session -> {
            long start = session.startTime();
            if (!archived(session, liveStart) || !selectedStart(start) || !visitSession(visited, start)) return;
            var missing = new JSONArray();
            Set<Long> roster = audienceActivity ? roster(session, this::charge) : null;
            if (!events.isEmpty()) scanDetails(file.apply(start, EVENTS), session, events, false, missing, roster);
            if (!danmu.isEmpty()) scanDetails(file.apply(start, DANMU), session, danmu, true, missing, roster);
            if (audienceActivity && session.hasUserSets()) hasAvailableDetails = true;
            if (roster != null && start >= from) roster.forEach(person -> collect(new Attendance(start, person)));
            if (!missing.isEmpty()) addMissing(start, missing);
            if (danmuByDay && !missing.isEmpty()) markMissingDays(session);
            if (byMinute) seedEventMinutes(session, !missing.isEmpty());
        });
    }

    private void scanDetails(Path path, LiveSession session, List<Kind> kinds, boolean danmu,
                             JSONArray missing, Set<Long> roster) {
        if (scan(path, line -> {
            charge(1);
            linesScanned++;
            Row row = event(line, session.startTime(), danmu, roster);
            if (row != null) collect(row);
        }) || knownEmpty(path, session.metrics(), kinds)) {
            hasAvailableDetails = true;
        } else {
            missing.add(path.getFileName().toString());
        }
    }

    static boolean knownEmpty(Path path, Map<String, Double> metrics, List<Kind> kinds) {
        // 逐条归档在第一条记录到来时创建。封存过的场次可以没有这份文件。
        boolean sealed = Files.isRegularFile(path.resolveSibling(DETAIL));
        for (Kind kind : kinds) {
            boolean recordedZero = false;
            for (String key : switch (kind) {
                case GIFT -> List.of("gift_value", "gift_paid");
                case BOX -> List.of("box_count");
                case GUARD -> List.of("guard_value", "captain_count", "commander_count", "governor_count");
                case SUPER_CHAT -> List.of("super_chat_count", "super_chat_value");
                case DANMU -> List.of("danmu_count");
                case FOLLOW -> List.of("follow_count");
                case PK, SESSION, MINUTE, AUDIENCE_ACTIVITY -> List.<String>of();
            }) {
                Double value = metrics.get(key);
                if (value == null) continue;
                // 汇总已有记录时，封存标记也无法说明逐条文件为空。
                if (value != 0) return false;
                recordedZero = true;
            }
            if (!recordedZero && !sealed) return false;
        }
        return true;
    }

    static boolean scan(Path path, Consumer<String> visit) {
        try (var stream = JsonlFiles.lines(path, MAX_EVENT_CHARS)) {
            var lines = stream.iterator();
            while (lines.hasNext()) {
                if (Thread.currentThread().isInterrupted()) throw new Invalid("查数已中止");
                visit.accept(lines.next());
            }
            return true;
        } catch (NoSuchFileException ignored) {
            return false;
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private void charge(long count) {
        if (Thread.currentThread().isInterrupted()) throw new Invalid("查数已中止");
        if (count > AssistantQueryService.LINE_LIMIT - scanWork) {
            String unit = series || byAge || audienceActivity || byMinute ? " 条" : " 行";
            throw new Invalid("明细超过 " + AssistantQueryService.LINE_LIMIT + unit + "扫描上限，请缩短时间范围");
        }
        scanWork += (int) count;
    }

    private boolean decode(String line, Set<String> keys) {
        try { detail.read(line, keys, readerContext); }
        catch (RuntimeException ignored) {
            invalidLines++;
            return false;
        }
        return true;
    }

    private static boolean isDanmu(Kind kind) { return kind == Kind.SUPER_CHAT || kind == Kind.DANMU; }

    private Row event(String line, long start, boolean danmu, Set<Long> roster) {
        if (!decode(line, eventFields)) return null;
        Kind kind = detail.kind(danmu);
        if (audienceActivity) {
            if (detail.audience != null && detail.timed(danmu)) {
                roster.remove(detail.audience);
                if (kind == null) kind = Kind.AUDIENCE_ACTIVITY;
            }
            if (kind == null || kind == Kind.PK && detail.audience == null) return null;
        } else if (kind == null || !request.kinds().contains(kind)) return null;
        if (detail.at == null || detail.at <= 0) {
            invalidLines++;
            return null;
        }
        if (detail.at < from || detail.at >= to) return null;
        detail.kind = kind;
        detail.start = start;
        return detail;
    }

    private Set<String> eventFields() {
        var keys = new HashSet<>(Set.of("at", "t", "type"));
        if (needsDistinct || needsPaying || audienceActivity) keys.add("uid");
        if (byAudience) keys.addAll(List.of("un", "uname"));
        if (needsPaying) keys.addAll(List.of("val", "pay", "bp"));
        for (Field field : fields) {
            keys.addAll(switch (field) {
                case NAME -> List.of("gn", "bn");
                case OPENED_GIFT -> List.of("gn");
                case AUDIENCE -> List.of("uid");
                case TEXT -> List.of("text");
                case GUARD_OPERATION -> List.of("ot");
                case GUARD_LEVEL -> List.of("lv");
                case PK_RESULT -> List.of("rs");
                case QUANTITY -> List.of("n");
                case AMOUNT -> List.of("val");
                case MY_VOTES -> List.of("mv");
                case OPPONENT_VOTES -> List.of("op");
                case START -> List.of();
                default -> throw new IllegalStateException();
            });
        }
        return keys;
    }

    /** 跳过无关字段和留言原文，只解码当前查询用到的列。 */
    private static void readDetail(String line, Set<String> keys, JSONReader.Context context, Detail detail,
                                   boolean textLengthOnly) {
        detail.clear();
        try (JSONReader reader = JSONReader.of(line, context)) {
            if (!reader.nextIfObjectStart()) throw new IllegalArgumentException();
            while (!reader.nextIfObjectEnd()) {
                String key = reader.readFieldName();
                if (key == null) throw new IllegalArgumentException();
                if (!keys.contains(key)) {
                    reader.skipValue();
                    continue;
                }
                switch (key) {
                    case "t" -> detail.type = readText(reader);
                    case "type" -> detail.danmuType = readText(reader);
                    case "at" -> detail.at = integer(readNumber(reader));
                    case "uid" -> detail.audience = integer(readNumber(reader));
                    case "un" -> detail.name = readText(reader);
                    case "uname" -> detail.danmuName = readText(reader);
                    case "text" -> {
                        if (textLengthOnly && reader.isString()) {
                            String text = reader.readString();
                            detail.characters = text == null ? null : text.codePointCount(0, text.length());
                        } else if (textLengthOnly) reader.skipValue();
                        else detail.text = readText(reader);
                    }
                    case "gn" -> detail.giftName = readText(reader);
                    case "bn" -> detail.boxName = readText(reader);
                    case "ot" -> detail.guardOperation = label(readNumber(reader), 1, "open", "renew");
                    case "lv" -> detail.guardLevel = label(readNumber(reader), 1, "governor", "commander", "captain");
                    case "rs" -> detail.pkResult = label(readNumber(reader), 0, "loss", "win", "draw");
                    case "n" -> detail.quantity = number(readNumber(reader));
                    case "val" -> detail.amount = number(readNumber(reader));
                    case "pay" -> detail.paid = number(readNumber(reader));
                    case "bp" -> detail.boxPrice = number(readNumber(reader));
                    case "mv" -> detail.myVotes = number(readNumber(reader));
                    case "op" -> detail.opponentVotes = opponentVotes(reader);
                    default -> throw new IllegalStateException();
                }
            }
            if (!reader.isEnd()) throw new IllegalArgumentException();
        }
    }

    private static String readText(JSONReader reader) {
        if (reader.isString()) {
            String value = reader.readString();
            return value == null || value.isBlank() ? null : value;
        }
        reader.skipValue();
        return null;
    }

    private static Number readNumber(JSONReader reader) {
        if (reader.isNumber()) return reader.readNumber();
        reader.skipValue();
        return null;
    }

    private Row summary(LiveSession session, LiveMetricCatalog.Insights rules) {
        Map<String, Double> metrics = session.metrics();
        var values = new EnumMap<Field, BigDecimal>(Field.class);
        for (Field field : fields) {
            values.put(field, switch (field) {
                case AMOUNT -> rules == null || rules.revenueKeys().isEmpty() || metrics == null || metrics.isEmpty()
                        ? null : number(rules.revenue(metrics));
                case START -> BigDecimal.valueOf(session.startTime());
                case END -> BigDecimal.valueOf(session.endTime());
                case DURATION -> BigDecimal.valueOf(session.durationSeconds());
                case EFFECTIVE -> BigDecimal.valueOf(StreamerStartSlots.effectiveSeconds(session));
                case MESSAGES -> number(rules == null || metrics == null ? null : metrics.get(rules.messageKey()));
                case PEAK_ONLINE -> number(session.peak("online_count").map(peak -> peak.value()).orElse(null));
                case WATCHED -> number(metrics == null ? null : metrics.get("watched_count"));
                case SESSION_AUDIENCE -> sessionAudience(session, rules);
                default -> throw new IllegalStateException();
            });
        }
        return new Summary(session.startTime(), values);
    }

    private static BigDecimal sessionAudience(LiveSession session, LiveMetricCatalog.Insights rules) {
        if (rules == null || !session.hasUserSets()) return null;
        var audience = new HashSet<Long>();
        rules.audienceSets().forEach(key -> audience.addAll(session.userSet(key)));
        return BigDecimal.valueOf(audience.size());
    }

    private interface Row {
        Kind kind();
        long start();
        long at();
        Object value(Field field);
        default Long audience() { return null; }
        default String nickname() { return null; }
        default BigDecimal paid() { return BigDecimal.ZERO; }
    }

    private record Summary(long start, Map<Field, BigDecimal> values) implements Row {
        @Override public Kind kind() { return Kind.SESSION; }
        @Override public long at() { return start; }
        @Override public Object value(Field field) { return values.get(field); }
    }

    private record Minute(long start, long at, BigDecimal online, BigDecimal watched) implements Row {
        @Override public Kind kind() { return Kind.MINUTE; }
        @Override public Object value(Field field) {
            return switch (field) {
                case ONLINE -> online;
                case WATCHED -> watched;
                case START -> BigDecimal.valueOf(start);
                default -> throw new IllegalStateException();
            };
        }
    }

    private record Attendance(long start, Long audience) implements Row {
        @Override public Kind kind() { return Kind.AUDIENCE_ACTIVITY; }
        @Override public long at() { return start; }
        @Override public Object value(Field field) {
            return switch (field) {
                case AUDIENCE -> audience;
                case AMOUNT -> BigDecimal.ZERO;
                case START -> BigDecimal.valueOf(start);
                default -> throw new IllegalStateException();
            };
        }
    }

    /** 每次只处理一行，分组状态会取走需要保留的值。 */
    static final class Detail implements Row {
        Kind kind;
        long start;
        Long at;
        Long audience;
        String type;
        String danmuType;
        String name;
        String danmuName;
        String text;
        Integer characters;
        String giftName;
        String boxName;
        String guardOperation;
        String guardLevel;
        String pkResult;
        BigDecimal quantity;
        BigDecimal amount;
        BigDecimal paid;
        BigDecimal boxPrice;
        BigDecimal myVotes;
        BigDecimal opponentVotes;

        void read(String line, Set<String> keys, JSONReader.Context context) {
            read(line, keys, context, false);
        }

        void read(String line, Set<String> keys, JSONReader.Context context, boolean textLengthOnly) {
            readDetail(line, keys, context, this, textLengthOnly);
        }

        boolean timed(boolean danmu) {
            return (danmu ? danmuType : type) != null && at != null && at > 0;
        }

        Kind kind(boolean danmu) {
            String tag = danmu ? danmuType : type;
            return tag == null ? null : switch (tag) {
                case "gift" -> danmu ? null : Kind.GIFT;
                case "box" -> danmu ? null : Kind.BOX;
                case "guard" -> danmu ? null : Kind.GUARD;
                case "follow" -> danmu ? null : Kind.FOLLOW;
                case "pk_settle" -> danmu ? null : Kind.PK;
                case "SUPER_CHAT" -> danmu ? Kind.SUPER_CHAT : null;
                case "DANMU", "EMOJI" -> danmu ? Kind.DANMU : null;
                default -> null;
            };
        }

        void clear() {
            at = null;
            audience = null;
            type = null;
            danmuType = null;
            name = null;
            danmuName = null;
            text = null;
            characters = null;
            giftName = null;
            boxName = null;
            guardOperation = null;
            guardLevel = null;
            pkResult = null;
            quantity = null;
            amount = null;
            paid = null;
            boxPrice = null;
            myVotes = null;
            opponentVotes = null;
        }

        @Override public Kind kind() { return kind; }
        @Override public long start() { return start; }
        @Override public long at() { return at; }
        @Override public Long audience() { return audience; }

        @Override
        public String nickname() {
            String nickname = isDanmu(kind) ? danmuName : name;
            return nickname == null || nickname.length() > 128 ? null : nickname;
        }

        @Override public BigDecimal paid() {
            return paid(kind);
        }

        BigDecimal paid(Kind kind) {
            return switch (kind) {
                case GIFT, GUARD -> paid;
                case BOX -> paid == null ? boxPrice : paid;
                case SUPER_CHAT -> amount;
                default -> BigDecimal.ZERO;
            };
        }

        @Override
        public Object value(Field field) {
            return switch (field) {
                case NAME -> kind == Kind.BOX ? boxName : giftName;
                case OPENED_GIFT -> giftName;
                case AUDIENCE -> audience;
                case TEXT -> text;
                case GUARD_OPERATION -> guardOperation;
                case GUARD_LEVEL -> guardLevel;
                case PK_RESULT -> pkResult;
                case QUANTITY -> quantity;
                case AMOUNT -> switch (kind) {
                    case GIFT, BOX, GUARD, SUPER_CHAT -> amount;
                    default -> BigDecimal.ZERO;
                };
                case MY_VOTES -> myVotes;
                case OPPONENT_VOTES -> opponentVotes;
                case START -> BigDecimal.valueOf(start);
                default -> throw new IllegalStateException();
            };
        }
    }

    private static BigDecimal opponentVotes(JSONReader reader) {
        if (!reader.isArray()) {
            reader.skipValue();
            return null;
        }
        List<?> opponents = reader.readArray();
        if (opponents.isEmpty()) return null;
        BigDecimal total = BigDecimal.ZERO;
        for (Object opponent : opponents) {
            BigDecimal votes = opponent instanceof Map<?, ?> row ? number(row.get("v")) : null;
            if (votes == null) return null;
            total = total.add(votes);
        }
        return total;
    }

    private static String label(Object raw, int first, String... labels) {
        Long code = integer(raw);
        return code != null && code >= first && code < first + labels.length ? labels[code.intValue() - first] : null;
    }

    private static Long integer(Object raw) {
        if (raw instanceof Long number) return number;
        if (raw instanceof Integer number) return number.longValue();
        BigDecimal value = number(raw);
        if (value == null) return null;
        try { return value.longValueExact(); }
        catch (ArithmeticException ignored) { return null; }
    }

    static BigDecimal number(Object raw) {
        BigDecimal value = raw instanceof Number number ? decimal(number) : null;
        return value != null && value.precision() <= 30 && Math.abs((long) value.scale()) <= 18 ? value : null;
    }

    private boolean matches(Row row, Long audience) {
        boolean unknown = false;
        for (Filter filter : request.filters()) {
            if (filter.field() == Field.AUDIENCE) {
                if (audience == null || !alias(audience).equalsIgnoreCase((String) filter.value())) return false;
                continue;
            }
            Object value = row.value(filter.field());
            if (value == null) unknown = true;
            else if (value instanceof BigDecimal number) {
                if (!filter.comparison().matches(number.compareTo((BigDecimal) filter.value()))) return false;
            } else if (filter.comparison() == Comparison.CONTAINS) {
                if (!((String) value).contains((String) filter.value())) return false;
            } else if (!value.equals(filter.value())) return false;
        }
        if (unknown) unknownFilters++;
        return !unknown;
    }

    private void collect(Row row) {
        Long audience = privacy.canAttribute(row.audience()) ? row.audience() : null;
        if ((byAudience || byAge) && audience == null || !matches(row, audience)) return;
        BigDecimal paid = needsPaying && audience != null ? row.paid() : BigDecimal.ZERO;
        addCount(total, row, audience, paid);
        Bucket bucket = request.groups().isEmpty() ? null : groupBucket(row);
        if (bucket != null) addCount(bucket, row, audience, paid);
        boolean unknown = paid == null || paid.signum() < 0;
        for (int i = 0; i < numericFields.size(); i++) {
            Field field = numericFields.get(i);
            var number = (BigDecimal) row.value(field);
            if (number == null) {
                unknown |= !isSample(field);
                if (i == eventAmountIndex) {
                    missingAmounts.computeIfAbsent(clockGroups(row.at()).day(), day -> new EnumMap<>(Kind.class))
                            .merge(row.kind(), 1, Integer::sum);
                }
            } else {
                total.numbers[i].add(number);
                if (bucket != null) bucket.numbers[i].add(number);
            }
        }
        if (unknown) unknownValues++;
    }

    private Bucket groupBucket(Row row) {
        var keys = request.groups();
        var key = new Key(group(keys.get(0), row), keys.size() == 2 ? group(keys.get(1), row) : null);
        Bucket bucket = groups.computeIfAbsent(key, this::bucket);
        if (byAudience && row.at() >= bucket.namedAt) {
            String name = row.nickname();
            if (name != null) {
                groupTextChars += name.length() - (bucket.name == null ? 0 : bucket.name.length());
                checkTextBudget();
                bucket.name = name;
                bucket.namedAt = row.at();
            }
        }
        return bucket;
    }

    private Object group(Group group, Row row) {
        return switch (group) {
            case DAY -> clockGroups(row.at()).day();
            case WEEK -> clockGroups(row.at()).week();
            case MONTH -> clockGroups(row.at()).month();
            case HOUR -> clockGroups(row.at()).hour();
            case WEEKDAY -> clockGroups(row.at()).weekday();
            case SESSION -> row.start();
            case MINUTE -> minuteGroup(row.at(), row.start());
            case KIND -> row.kind().key;
            case AUDIENCE_AGE -> age(row.audience());
            default -> row.value(group.field);
        };
    }

    private String age(long audience) {
        try {
            int first = history.firstDay(audience);
            if (first == NO_DAY || first > request.to().toEpochDay())
                throw new Invalid("无法核对观众首次留档，请稍后重试");
            return first < request.from().toEpochDay() ? "returning" : "new";
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private ClockGroups clockGroups(long at) {
        if (clockGroups == null || at < clockGroups.start() || at >= clockGroups.end()) {
            Instant instant = Instant.ofEpochMilli(at);
            var hour = instant.atZone(StatisticsDay.ZONE).truncatedTo(ChronoUnit.HOURS);
            var day = StatisticsDay.dateOf(instant);
            int weekday = day.getDayOfWeek().getValue();
            var monday = day.minusDays(weekday - 1L);
            clockGroups = new ClockGroups(hour.toInstant().toEpochMilli(), hour.plusHours(1).toInstant().toEpochMilli(),
                    day.toString(), monday.toString(), YearMonth.from(day).toString(), hour.getHour(), weekday);
        }
        return clockGroups;
    }

    private Bucket bucket(Key key) {
        if (groups.size() >= MAX_GROUPS) throw stateLimit();
        if (key.first() instanceof String text) groupTextChars += text.length();
        if (key.second() instanceof String text) groupTextChars += text.length();
        checkTextBudget();
        return new Bucket(needsDistinct && !byAudience, needsPaying, numericFields.size(), sessionCountColumns.size());
    }

    private void checkTextBudget() {
        if (groupTextChars > MAX_GROUP_TEXT_CHARS) throw stateLimit();
    }

    private void addCount(Bucket bucket, Row row, Long audience, BigDecimal paid) {
        bucket.count++;
        if (bucket.sessions.length > 0) {
            if (sessionSampleFields.isEmpty()) {
                if (!(row instanceof Minute minute) || minute.online() != null || minute.watched() != null)
                    bucket.sessions[0].add(row.start());
            } else {
                for (int i = 0; i < sessionSampleFields.size(); i++) {
                    if (row.value(sessionSampleFields.get(i)) != null) bucket.sessions[i].add(row.start());
                }
            }
        }
        if (audience == null) return;
        if (bucket.audience != null) bucket.audience.add(audience);
        if (bucket.paying != null) {
            if (paid == null || paid.signum() < 0) bucket.unknownPaying = true;
            else if (paid.signum() > 0) bucket.paying.add(audience);
        }
    }

    private JSONObject table(boolean liveUnarchived, Long liveStart, AssistantQueryContext context) {
        int limit = request.limit() == null ? TABLE_ROWS - 1 : request.limit();
        var columns = new JSONArray();
        request.groups().forEach(g -> columns.add(g.key));
        request.measures().forEach(m -> columns.add(m.key()));
        columns.addAll(sessionCountColumns);
        boolean allMissing = !series && missingDetailsCount > 0 && !hasAvailableDetails;
        var totals = new JSONArray();
        if (!request.groups().isEmpty()) totals.add("合计");
        if (request.groups().size() == 2) totals.add(null);
        appendMeasures(totals, total, allMissing);
        JSONArray rows = new JSONArray();
        if (request.groups().isEmpty()) rows.add(new JSONArray(totals));
        else topRows(limit).forEach(ranked -> {
            var row = new JSONArray();
            row.add(cell(ranked.key().first(), ranked.bucket(), request.groups().get(0)));
            if (request.groups().size() == 2)
                row.add(cell(ranked.key().second(), ranked.bucket(), request.groups().get(1)));
            appendMeasures(row, ranked.bucket(), ranked.bucket().incomplete);
            rows.add(row);
        });
        int groupCount = request.groups().isEmpty() ? 1 : groups.size();
        boolean truncated = rows.size() < groupCount;
        var result = JSONObject.of("source", source(), "from", request.from().toString(), "to", request.to().toString(),
                "fromAt", from, "toAtExclusive", to, "statisticsDay", DAY_RULE, "columns", columns,
                "rows", rows, "totals", totals, "groupCount", groupCount, "truncated", truncated,
                "missingDetailsCount", missingDetailsCount, "missingDetails", missingDetails,
                "liveUnarchived", liveUnarchived, "scannedLines", linesScanned, "invalidLines", invalidLines,
                "unknownFilterRows", unknownFilters, "unknownMetricRows", unknownValues);
        if (truncated) result.put("truncationNote", "仅展示排序后的前 " + rows.size() + " 行，合计覆盖全部匹配记录");
        if (missingDetailsCount > missingDetails.size()) result.put("missingDetailsTruncated", true);
        if (missingDetailsCount > 0 || invalidLines > 0 || unknownFilters > 0 || unknownValues > 0)
            result.put("coverageNote", "缺失明细和无法读取的记录未计入。null 表示缺少完整数值");
        if (numericFields.contains(Field.QUANTITY)) {
            result.put("quantityUnit", request.kinds().contains(Kind.GUARD) ? "个月" : "个");
        }
        if (!missingAmounts.isEmpty()) {
            Numeric amount = total.numbers[eventAmountIndex];
            result.put("knownAmountSubtotal", amount.known == 0 ? null : amount.sum);
            result.put("missingAmountRows", total.count - amount.known);
            result.put("missingAmountsByDayAndKind", missingAmounts.entrySet().stream().map(entry -> {
                var counts = new JSONObject();
                entry.getValue().forEach((kind, count) -> counts.put(kind.key, count));
                return JSONObject.of("day", entry.getKey(), "countsByKind", counts);
            }).toList());
        }
        if (series) {
            result.put("expectedMinutes", expectedMinutes);
            result.put("missingMinutes", JSONObject.of("online", missingOnline, "watched", missingWatched));
            result.put("minuteNote", MINUTE_RULE);
        } else if (byMinute || request.kinds().contains(Kind.DANMU)) {
            result.put("zeroNote", ZERO_RULE);
        }
        if (byAge) {
            int earliest = history.progress.earliestDay();
            result.put("historyFrom", earliest == NO_DAY ? null : LocalDate.ofEpochDay(earliest).toString());
            result.put("historyNote", earliest == NO_DAY ? "此主播暂无可用的历史留档。" + HISTORY_RULE : HISTORY_RULE);
            result.put("historyScannedRecords", historyRecordsScanned);
            if (history.progress.invalidLines() > 0) result.put("historyInvalidLines", history.progress.invalidLines());
        }
        if (needsPaying) {
            result.put("payingAudienceBasis", "按正实扣去重可识别的付费观众。盲盒缺实扣时用存档盒价估算，醒目留言用存档金额。实扣无法判断时该组付费人数未知");
        }
        if (liveUnarchived) {
            result.put("liveUnarchivedStart", liveStart);
            result.put("liveNote", "正在直播的场次还没归档，未计入");
        }
        if (context.amountVisible()) {
            result.put("amountUnit", "元");
            result.put("amountBasis", AMOUNT_BASIS);
        }
        return result;
    }

    private Object cell(Object key, Bucket bucket, Group group) {
        return group == Group.AUDIENCE ? JSONObject.of("uid", key, "uname", bucket.name, "alias", alias((Long) key)) : key;
    }

    private void appendMeasures(JSONArray row, Bucket bucket, boolean unknown) {
        for (Measure measure : request.measures()) row.add(unknown ? null : value(bucket, measure));
        for (LongSet sessions : bucket.sessions) row.add(unknown ? null : sessions.size());
    }

    private Object value(Bucket bucket, Measure measure) {
        return switch (measure.operation()) {
            case COUNT -> bucket.count;
            case DISTINCT -> bucket.audience == null ? 1 : bucket.audience.size();
            case DISTINCT_PAYING -> bucket.unknownPaying ? null : bucket.paying.size();
            default -> bucket.numbers[numericFields.indexOf(measure.field())]
                    .value(measure.operation(), bucket.count, isSample(measure.field()));
        };
    }

    private boolean isSample(Field field) { return series && (field == Field.ONLINE || field == Field.WATCHED); }

    private List<Ranked> topRows(int limit) {
        Sort sort = request.sort();
        Group firstGroup = request.groups().get(0);
        boolean byAlias = byAudience && (sort == null || Group.AUDIENCE.key.equals(sort.by()));
        Measure metric = sort == null ? null : request.measures().stream().filter(m -> m.key().equals(sort.by()))
                .findFirst().orElse(null);
        Comparator<Ranked> order = (a, b) -> {
            int primary = compare(a.sortValue(), b.sortValue(), sort != null && sort.descending());
            if (primary != 0) return primary;
            int first = compare(a.orderKey().first(), b.orderKey().first(), false);
            if (first != 0) return first;
            int second = compare(a.orderKey().second(), b.orderKey().second(), false);
            if (second != 0) return second;
            // 六位代号碰撞时仍保持顺序稳定。
            int raw = compare(a.key().first(), b.key().first(), false);
            return raw != 0 ? raw : compare(a.key().second(), b.key().second(), false);
        };
        var best = new PriorityQueue<Ranked>(limit, order.reversed());
        for (var entry : groups.entrySet()) {
            Key key = entry.getKey();
            Bucket bucket = entry.getValue();
            Key orderKey = key;
            if (byAlias) {
                orderKey = firstGroup == Group.AUDIENCE
                        ? new Key(alias((Long) key.first()), key.second())
                        : new Key(key.first(), alias((Long) key.second()));
            }
            Object sortValue = null;
            if (metric != null) sortValue = bucket.incomplete ? null : value(bucket, metric);
            else if (sort != null) sortValue = sort.by().equals(firstGroup.key) ? orderKey.first() : orderKey.second();
            var ranked = new Ranked(key, orderKey, bucket, sortValue);
            if (best.size() < limit) best.add(ranked);
            else if (order.compare(ranked, best.element()) < 0) {
                best.remove();
                best.add(ranked);
            }
        }
        return best.stream().sorted(order).toList();
    }

    @SuppressWarnings("unchecked")
    private static int compare(Object left, Object right, boolean descending) {
        if (left == null) return right == null ? 0 : 1;
        if (right == null) return -1;
        return descending ? ((Comparable<Object>) right).compareTo(left) : ((Comparable<Object>) left).compareTo(right);
    }

    private String alias(long uid) {
        if (aliases == null) aliases = privacy.aliases();
        return aliases.apply(uid);
    }

    private String source() {
        if (sessions) return SESSION_SOURCE;
        if (series) return MINUTE_SOURCE;
        if (audienceActivity) return AUDIENCE_SOURCE;
        return request.kinds().contains(Kind.DANMU) ? DANMU_SOURCE : EVENT_SOURCE;
    }

    static JSONObject rejected(String source, String reason) {
        return JSONObject.of("source", source, "rows", null, "reason", reason);
    }

    private static Invalid stateLimit() { return new Invalid("场次或分组数据过多，请缩短时间范围或减少分组"); }

    private record Key(Object first, Object second) {}
    private record Ranked(Key key, Key orderKey, Bucket bucket, Object sortValue) {}
    private record ClockGroups(long start, long end, String day, String week, String month, int hour, int weekday) {}

    private static final class Bucket {
        long count;
        final LongSet audience;
        final LongSet paying;
        final Numeric[] numbers;
        final LongSet[] sessions;
        boolean incomplete;
        boolean unknownPaying;
        String name;
        long namedAt;

        Bucket(boolean distinct, boolean needsPaying, int fields, int sessionCounts) {
            audience = distinct ? new LongSet() : null;
            paying = needsPaying ? new LongSet() : null;
            numbers = new Numeric[fields];
            Arrays.setAll(numbers, i -> new Numeric());
            sessions = new LongSet[sessionCounts];
            Arrays.setAll(sessions, i -> new LongSet());
        }
    }

    /** 正整数去重集合，数组避免为每个值另留对象。 */
    private static final class LongSet {
        private long[] keys;
        private int size;

        int size() { return size; }

        void add(long value) {
            if (keys == null) keys = new long[4];
            int index = AssistantAudienceHistory.slot(keys, value);
            if (keys[index] == value) return;
            if (size == keys.length / 2) {
                var larger = new long[keys.length * 2];
                for (long key : keys) {
                    if (key != 0) larger[AssistantAudienceHistory.slot(larger, key)] = key;
                }
                keys = larger;
                index = AssistantAudienceHistory.slot(keys, value);
            }
            keys[index] = value;
            size++;
        }

    }

    private static final class Numeric {
        long known;
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal min;
        BigDecimal max;

        void add(BigDecimal value) {
            known++;
            sum = sum.add(value);
            if (min == null || value.compareTo(min) < 0) min = value;
            if (max == null || value.compareTo(max) > 0) max = value;
        }

        BigDecimal value(Operation op, long count, boolean skipMissing) {
            if (!skipMissing && known != count) return null;
            return switch (op) {
                case SUM -> sum;
                case MAX -> max;
                case MIN -> min;
                case AVG -> known == 0 ? null : sum.divide(BigDecimal.valueOf(known), MathContext.DECIMAL64);
                default -> throw new IllegalStateException();
            };
        }
    }
}
