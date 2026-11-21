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
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.LongFunction;

import static org.frostnova.nova.console.assistant.AssistantAggregateRequest.*;

/** 一次查询的流式汇总。内存中只保留分组状态、去重集合和有限的回包样本。 */
final class AssistantAggregateQuery {
    static final String EVENT_SOURCE = "已归档的场次事件明细";
    static final String SESSION_SOURCE = "已归档的场次汇总";
    static final String DAY_RULE = "北京时间 06:00 换统计日，起止日期均含当天。事件按发生时刻，场次按开播时刻整场归入";
    static final String AMOUNT_BASIS = "到手价值，与控制台流水同口径。盲盒计开出物价值，背包礼物计入流水";
    private static final String EVENTS = "events.jsonl";
    private static final String DANMU = "danmu.jsonl";
    private static final String DETAIL = "detail.json";
    private static final int TABLE_ROWS = 60;
    private static final int MAX_EVENT_CHARS = 16_384;
    private static final int MAX_SESSION_CHARS = 1_048_576;
    private static final int MAX_GROUPS = 50_000;
    private static final int MAX_GROUP_TEXT_CHARS = 2_000_000;

    private final AssistantAggregateRequest request;
    private final AssistantPrivacy privacy;
    private final boolean sessions;
    private final boolean needsDistinct;
    private final boolean byAudience;
    private final boolean usesSessionAudience;
    private final long from;
    private final long to;
    private final List<Field> numericFields;
    private final Set<Field> fields;
    private final Set<String> eventFields;
    private final JSONReader.Context readerContext = JSONFactory.createReadContext();
    private final Detail detail = new Detail();
    private final Map<Key, Bucket> groups = new HashMap<>();
    private final Bucket total;
    private final JSONArray missingDetails = new JSONArray();
    private int missingDetailsCount;
    private boolean hasAvailableDetails;
    private int linesScanned;
    private int invalidLines;
    private int unknownFilters;
    private int unknownValues;
    private int groupTextChars;
    private LongFunction<String> aliases;
    private ClockGroups clockGroups;

    AssistantAggregateQuery(AssistantAggregateRequest request, AssistantPrivacy privacy) {
        this.request = request;
        this.privacy = privacy;
        sessions = request.kinds().contains(Kind.SESSION);
        needsDistinct = request.measures().stream().anyMatch(m -> m.operation() == Operation.DISTINCT);
        byAudience = request.groups().contains(Group.AUDIENCE);
        from = StatisticsDay.startOf(request.from()).toEpochMilli();
        to = StatisticsDay.startOf(request.to().plusDays(1)).toEpochMilli();
        numericFields = request.measures().stream().map(Measure::field).filter(Objects::nonNull).distinct().toList();
        fields = EnumSet.noneOf(Field.class);
        fields.addAll(numericFields);
        request.filters().forEach(filter -> fields.add(filter.field()));
        request.groups().stream().map(group -> group.field).filter(Objects::nonNull).forEach(fields::add);
        usesSessionAudience = fields.contains(Field.SESSION_AUDIENCE);
        eventFields = sessions ? Set.of() : eventFields();
        total = new Bucket(needsDistinct, numericFields.size());
    }

    JSONObject run(String platform, long uid, LiveSessionArchive archive, LiveMetricCatalog.Insights rules,
                   BiFunction<Long, String, Path> file, boolean living, Long liveStart, Instant now,
                   AssistantQueryContext context) {
        if (!context.amountVisible() && request.usesAmount()) return rejected(source(), HIDDEN_AMOUNT);
        if (!sessions && !"bilibili".equals(platform)) return rejected(source(), "此平台没有提供这些事件明细");
        boolean liveUnarchived = living && now.toEpochMilli() >= from
                && (liveStart == null ? now.toEpochMilli() < to : liveStart < to);
        try {
            if (sessions) {
                Map<Long, Row> summaries = new HashMap<>();
                archive.forEachOverlapping(platform, uid, from, to, usesSessionAudience, MAX_SESSION_CHARS, session -> {
                    if (archived(session, liveStart) && session.startTime() >= from) {
                        summaries.put(session.startTime(), summary(session, rules));
                        if (summaries.size() > MAX_GROUPS) throw stateLimit();
                    }
                });
                summaries.values().forEach(this::collect);
            } else {
                scanEvents(platform, uid, archive, file, liveStart);
            }
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

    private static boolean archived(LiveSession session, Long liveStart) {
        return session.startTime() > 0 && session.endTime() >= session.startTime()
                && !Objects.equals(session.startTime(), liveStart);
    }

    private void scanEvents(String platform, long uid, LiveSessionArchive archive,
                            BiFunction<Long, String, Path> file, Long liveStart) throws IOException {
        boolean danmu = request.kinds().contains(Kind.SUPER_CHAT);
        boolean events = !danmu || request.kinds().size() > 1;
        var visited = new HashSet<Long>();
        archive.forEachOverlapping(platform, uid, from, to, false, MAX_SESSION_CHARS, session -> {
            long start = session.startTime();
            if (!archived(session, liveStart) || !visited.add(start)) return;
            if (visited.size() > MAX_GROUPS) throw stateLimit();
            var missing = new JSONArray();
            if (events) scanDetails(file.apply(start, EVENTS), session, false, missing);
            if (danmu) scanDetails(file.apply(start, DANMU), session, true, missing);
            if (!missing.isEmpty()) {
                missingDetailsCount++;
                if (missingDetails.size() < TABLE_ROWS)
                    missingDetails.add(JSONObject.of("session", start, "files", missing));
            }
        });
    }

    private void scanDetails(Path path, LiveSession session, boolean danmu, JSONArray missing) {
        if (scan(path, session.startTime(), danmu) || knownEmpty(path, session.metrics(), danmu)) {
            hasAvailableDetails = true;
        } else {
            missing.add(path.getFileName().toString());
        }
    }

    private boolean knownEmpty(Path path, Map<String, Double> metrics, boolean danmu) {
        // 逐条归档在第一条记录到来时创建。封存过的场次可以没有这份文件。
        boolean sealed = Files.isRegularFile(path.resolveSibling(DETAIL));
        for (Kind kind : request.kinds()) {
            if ((kind == Kind.SUPER_CHAT) != danmu) continue;
            boolean recordedZero = false;
            for (String key : switch (kind) {
                case GIFT -> List.of("gift_value", "gift_paid");
                case BOX -> List.of("box_count");
                case GUARD -> List.of("guard_value", "captain_count", "commander_count", "governor_count");
                case SUPER_CHAT -> List.of("super_chat_count", "super_chat_value");
                case FOLLOW -> List.of("follow_count");
                case PK, SESSION -> List.<String>of();
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

    private boolean scan(Path path, long start, boolean danmu) {
        try (var stream = JsonlFiles.lines(path, MAX_EVENT_CHARS)) {
            var lines = stream.iterator();
            while (lines.hasNext()) {
                if (Thread.currentThread().isInterrupted()) throw new Invalid("查数已中止");
                if (++linesScanned > AssistantQueryService.LINE_LIMIT)
                    throw new Invalid("明细超过 " + AssistantQueryService.LINE_LIMIT + " 行扫描上限，请缩短时间范围");
                Row row = event(lines.next(), start, danmu);
                if (row != null) collect(row);
            }
            return true;
        } catch (NoSuchFileException ignored) {
            return false;
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private Row event(String line, long start, boolean danmu) {
        try { readDetail(line); }
        catch (RuntimeException ignored) {
            invalidLines++;
            return null;
        }
        String type = danmu ? detail.danmuType : detail.type;
        Kind kind = type == null ? null : switch (type) {
            case "gift" -> danmu ? null : Kind.GIFT;
            case "box" -> danmu ? null : Kind.BOX;
            case "guard" -> danmu ? null : Kind.GUARD;
            case "follow" -> danmu ? null : Kind.FOLLOW;
            case "pk_settle" -> danmu ? null : Kind.PK;
            case "SUPER_CHAT" -> danmu ? Kind.SUPER_CHAT : null;
            default -> null;
        };
        if (kind == null || !request.kinds().contains(kind)) return null;
        Long at = detail.at;
        if (at == null || at <= 0) {
            invalidLines++;
            return null;
        }
        if (at < from || at >= to) return null;
        detail.kind = kind;
        detail.start = start;
        return detail;
    }

    private Set<String> eventFields() {
        var keys = new HashSet<>(Set.of("at", "t", "type"));
        if (needsDistinct) keys.add("uid");
        if (byAudience) keys.addAll(List.of("un", "uname"));
        for (Field field : fields) {
            keys.addAll(switch (field) {
                case NAME -> List.of("gn", "bn");
                case OPENED_GIFT -> List.of("gn");
                case AUDIENCE -> List.of("uid");
                case GUARD_OPERATION -> List.of("ot");
                case GUARD_LEVEL -> List.of("lv");
                case PK_RESULT -> List.of("rs");
                case QUANTITY -> List.of("n");
                case AMOUNT -> List.of("val");
                case MY_VOTES -> List.of("mv");
                case OPPONENT_VOTES -> List.of("op");
                default -> throw new IllegalStateException();
            });
        }
        return keys;
    }

    /** 跳过无关字段和留言原文，只解码当前查询用到的列。 */
    private void readDetail(String line) {
        detail.clear();
        try (JSONReader reader = JSONReader.of(line, readerContext)) {
            if (!reader.nextIfObjectStart()) throw new IllegalArgumentException();
            while (!reader.nextIfObjectEnd()) {
                String key = reader.readFieldName();
                if (key == null) throw new IllegalArgumentException();
                if (!eventFields.contains(key)) {
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
                    case "gn" -> detail.giftName = readText(reader);
                    case "bn" -> detail.boxName = readText(reader);
                    case "ot" -> detail.guardOperation = label(readNumber(reader), 1, "open", "renew");
                    case "lv" -> detail.guardLevel = label(readNumber(reader), 1, "governor", "commander", "captain");
                    case "rs" -> detail.pkResult = label(readNumber(reader), 0, "loss", "win", "draw");
                    case "n" -> detail.quantity = number(readNumber(reader));
                    case "val" -> detail.amount = number(readNumber(reader));
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
    }

    private record Summary(long start, Map<Field, BigDecimal> values) implements Row {
        @Override public Kind kind() { return Kind.SESSION; }
        @Override public long at() { return start; }
        @Override public Object value(Field field) { return values.get(field); }
    }

    /** 每次只处理一行，分组状态会取走需要保留的值。 */
    private static final class Detail implements Row {
        Kind kind;
        long start;
        Long at;
        Long audience;
        String type;
        String danmuType;
        String name;
        String danmuName;
        String giftName;
        String boxName;
        String guardOperation;
        String guardLevel;
        String pkResult;
        BigDecimal quantity;
        BigDecimal amount;
        BigDecimal myVotes;
        BigDecimal opponentVotes;

        void clear() {
            at = null;
            audience = null;
            type = null;
            danmuType = null;
            name = null;
            danmuName = null;
            giftName = null;
            boxName = null;
            guardOperation = null;
            guardLevel = null;
            pkResult = null;
            quantity = null;
            amount = null;
            myVotes = null;
            opponentVotes = null;
        }

        @Override public Kind kind() { return kind; }
        @Override public long start() { return start; }
        @Override public long at() { return at; }
        @Override public Long audience() { return audience; }

        @Override
        public String nickname() {
            String nickname = kind == Kind.SUPER_CHAT ? danmuName : name;
            return nickname == null || nickname.length() > 128 ? null : nickname;
        }

        @Override
        public Object value(Field field) {
            return switch (field) {
                case NAME -> kind == Kind.BOX ? boxName : giftName;
                case OPENED_GIFT -> giftName;
                case AUDIENCE -> audience;
                case GUARD_OPERATION -> guardOperation;
                case GUARD_LEVEL -> guardLevel;
                case PK_RESULT -> pkResult;
                case QUANTITY -> quantity;
                case AMOUNT -> amount;
                case MY_VOTES -> myVotes;
                case OPPONENT_VOTES -> opponentVotes;
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

    private static BigDecimal number(Object raw) {
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
        if (byAudience && audience == null || !matches(row, audience)) return;
        addCount(total, audience);
        Bucket bucket = request.groups().isEmpty() ? null : groupBucket(row, audience);
        boolean unknown = false;
        for (int i = 0; i < numericFields.size(); i++) {
            var number = (BigDecimal) row.value(numericFields.get(i));
            if (number == null) unknown = true;
            else {
                total.numbers[i].add(number);
                if (bucket != null) bucket.numbers[i].add(number);
            }
        }
        if (unknown) unknownValues++;
    }

    private Bucket groupBucket(Row row, Long audience) {
        var keys = request.groups();
        var key = new Key(group(keys.get(0), row), keys.size() == 2 ? group(keys.get(1), row) : null);
        Bucket bucket = groups.computeIfAbsent(key, this::bucket);
        addCount(bucket, audience);
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
            case KIND -> row.kind().key;
            default -> row.value(group.field);
        };
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
        return new Bucket(needsDistinct && !byAudience, numericFields.size());
    }

    private void checkTextBudget() {
        if (groupTextChars > MAX_GROUP_TEXT_CHARS) throw stateLimit();
    }

    private void addCount(Bucket bucket, Long audience) {
        bucket.count++;
        if (bucket.audience != null && audience != null) bucket.audience.add(audience);
    }

    private JSONObject table(boolean liveUnarchived, Long liveStart, AssistantQueryContext context) {
        int limit = request.limit() == null ? TABLE_ROWS - 1 : request.limit();
        var columns = new JSONArray();
        request.groups().forEach(g -> columns.add(g.key));
        request.measures().forEach(m -> columns.add(m.key()));
        boolean allMissing = missingDetailsCount > 0 && !hasAvailableDetails;
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
            appendMeasures(row, ranked.bucket(), false);
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
    }

    private Object value(Bucket bucket, Measure measure) {
        return switch (measure.operation()) {
            case COUNT -> bucket.count;
            case DISTINCT -> bucket.audience == null ? 1 : bucket.audience.size();
            default -> bucket.numbers[numericFields.indexOf(measure.field())].value(measure.operation(), bucket.count);
        };
    }

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
            if (metric != null) sortValue = value(bucket, metric);
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

    private String source() { return sessions ? SESSION_SOURCE : EVENT_SOURCE; }

    static JSONObject rejected(String source, String reason) {
        return JSONObject.of("source", source, "rows", null, "reason", reason);
    }

    private static Invalid stateLimit() { return new Invalid("场次或分组数据过多，请缩短时间范围或减少分组"); }

    private record Key(Object first, Object second) {}
    private record Ranked(Key key, Key orderKey, Bucket bucket, Object sortValue) {}
    private record ClockGroups(long start, long end, String day, String week, String month, int hour, int weekday) {}

    private static final class Bucket {
        long count;
        final AudienceSet audience;
        final Numeric[] numbers;
        String name;
        long namedAt;

        Bucket(boolean distinct, int fields) {
            audience = distinct ? new AudienceSet() : null;
            numbers = new Numeric[fields];
            Arrays.setAll(numbers, i -> new Numeric());
        }
    }

    /** 每行至多给本组和合计各添一个正整数编号，数组避免为每个编号另留对象。 */
    private static final class AudienceSet {
        private long[] keys;
        private int size;

        int size() { return size; }

        void add(long uid) {
            if (keys == null) keys = new long[4];
            int index = slot(keys, uid);
            if (keys[index] == uid) return;
            if (size == keys.length / 2) {
                var larger = new long[keys.length * 2];
                for (long key : keys) {
                    if (key != 0) larger[slot(larger, key)] = key;
                }
                keys = larger;
                index = slot(keys, uid);
            }
            keys[index] = uid;
            size++;
        }

        private static int slot(long[] keys, long uid) {
            long mixed = uid * 0x9e3779b97f4a7c15L;
            int mask = keys.length - 1;
            int index = (int) (mixed ^ (mixed >>> 32)) & mask;
            while (keys[index] != 0 && keys[index] != uid) index = (index + 1) & mask;
            return index;
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

        BigDecimal value(Operation op, long count) {
            if (known != count) return null;
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
