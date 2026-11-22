package org.frostnova.nova.console.assistant;

import com.alibaba.fastjson2.JSONObject;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

record AssistantAggregateRequest(List<Kind> kinds, LocalDate from, LocalDate to, List<Filter> filters,
                                 List<Group> groups, List<Measure> measures, Sort sort, Integer limit,
                                 Integer bucketMinutes) {
    static final String NAME = "aggregate";
    static final String HIDDEN_AMOUNT = "这里不显示金额";
    static final List<String> REQUIRED = List.of("kinds", "from", "to", "metrics");
    private static final int MAX_FILTERS = 12;
    private static final int MAX_MEASURES = 8;
    private static final int MAX_LIMIT = 50;
    private static final int MAX_BUCKET_MINUTES = 1440;
    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Pattern ALIAS = Pattern.compile("观众-[0-9a-fA-F]{6}");

    enum Kind {
        GIFT("gift"), BOX("box"), GUARD("guard"), SUPER_CHAT("super_chat"),
        FOLLOW("follow"), PK("pk_settle"), SESSION("session"), MINUTE("minute"), DANMU("danmu"),
        AUDIENCE_ACTIVITY("audience_activity");

        final String key;
        Kind(String key) { this.key = key; }
    }

    enum Field {
        NAME("name", Kind.GIFT, Kind.BOX),
        OPENED_GIFT("openedGiftName", Kind.BOX),
        AUDIENCE("audience", Kind.GIFT, Kind.BOX, Kind.GUARD, Kind.SUPER_CHAT, Kind.FOLLOW,
                Kind.DANMU, Kind.AUDIENCE_ACTIVITY),
        TEXT("text", Kind.DANMU),
        GUARD_OPERATION("guardOperation", Kind.GUARD),
        GUARD_LEVEL("guardLevel", Kind.GUARD),
        PK_RESULT("pkResult", Kind.PK),
        QUANTITY("quantity", true, true, Kind.GIFT, Kind.BOX, Kind.GUARD),
        AMOUNT("amount", true, true, Kind.GIFT, Kind.BOX, Kind.GUARD, Kind.SUPER_CHAT, Kind.SESSION,
                Kind.AUDIENCE_ACTIVITY),
        MY_VOTES("pkMyVotes", true, false, Kind.PK),
        OPPONENT_VOTES("pkOpponentVotes", true, false, Kind.PK),
        START("startTime", true, false, Kind.values()),
        END("endTime", true, false, Kind.SESSION),
        DURATION("durationSeconds", true, true, Kind.SESSION),
        EFFECTIVE("effectiveSeconds", true, true, Kind.SESSION),
        MESSAGES("messages", true, true, Kind.SESSION),
        PEAK_ONLINE("peakOnline", true, false, Kind.SESSION),
        ONLINE("online", true, false, Kind.MINUTE),
        WATCHED("watched", true, false, Kind.SESSION, Kind.MINUTE),
        SESSION_AUDIENCE("sessionAudience", true, false, Kind.SESSION);

        final String key;
        final boolean numeric;
        final boolean additive;
        final Set<Kind> kinds;

        Field(String key, Kind... kinds) { this(key, false, false, kinds); }
        Field(String key, boolean numeric, boolean additive, Kind... kinds) {
            this.key = key;
            this.numeric = numeric;
            this.additive = additive;
            this.kinds = Set.of(kinds);
        }
    }

    enum Group {
        DAY("day"), WEEK("week"), MONTH("month"), HOUR("hour"), WEEKDAY("weekday"),
        SESSION("session"), KIND("kind"), MINUTE("minute"),
        NAME(Field.NAME), OPENED_GIFT(Field.OPENED_GIFT), AUDIENCE(Field.AUDIENCE), AUDIENCE_AGE("audienceAge", Field.AUDIENCE),
        GUARD_OPERATION(Field.GUARD_OPERATION), GUARD_LEVEL(Field.GUARD_LEVEL), PK_RESULT(Field.PK_RESULT);

        final String key;
        final Field field;
        Group(String key) { this(key, null); }
        Group(Field field) { this(field.key, field); }
        Group(String key, Field field) { this.key = key; this.field = field; }
    }

    enum Comparison {
        EQ("eq"), CONTAINS("contains"), GT("gt"), GTE("gte"), LT("lt"), LTE("lte");

        final String key;
        Comparison(String key) { this.key = key; }

        boolean matches(int order) {
            return switch (this) {
                case EQ -> order == 0;
                case GT -> order > 0;
                case GTE -> order >= 0;
                case LT -> order < 0;
                case LTE -> order <= 0;
                case CONTAINS -> throw new IllegalStateException();
            };
        }
    }

    enum Operation { COUNT, DISTINCT, DISTINCT_PAYING, SUM, MAX, MIN, AVG }
    record Filter(Field field, Comparison comparison, Object value) {}
    record Measure(String key, Operation operation, Field field) {}
    record Sort(String by, boolean descending) {}

    private static final Measure COUNT = new Measure("count", Operation.COUNT, null);
    private static final Measure DISTINCT = new Measure("distinctAudience", Operation.DISTINCT, null);
    private static final Measure DISTINCT_PAYING = new Measure("distinctPayingAudience", Operation.DISTINCT_PAYING, null);
    private static final Map<String, Measure> METRICS = metrics();
    private static final List<String> PK_RESULTS = List.of("win", "loss", "draw");
    private static final List<String> GUARD_OPERATIONS = List.of("open", "renew");
    private static final List<String> GUARD_LEVELS = List.of("captain", "commander", "governor");
    private static final Set<Kind> PAID_KINDS = Set.of(Kind.GIFT, Kind.BOX, Kind.GUARD, Kind.SUPER_CHAT);

    private static Map<String, Measure> metrics() {
        var result = new LinkedHashMap<String, Measure>();
        for (Measure measure : List.of(COUNT, DISTINCT, DISTINCT_PAYING)) result.put(measure.key(), measure);
        for (Field field : Field.values()) {
            if (!field.numeric) continue;
            for (Operation operation : List.of(Operation.SUM, Operation.MAX, Operation.MIN, Operation.AVG)) {
                if (operation == Operation.SUM && !field.additive) continue;
                String key = operation.name().toLowerCase(Locale.ROOT) + ":" + field.key;
                result.put(key, new Measure(key, operation, field));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    static JSONObject fields() {
        return JSONObject.of(
                "kinds", array(choice(keys(Kind.values(), k -> k.key),
                        "gift 礼物、box 盲盒、guard 上舰、super_chat 醒目留言、follow 关注、pk_settle PK 结算、session 场次。"
                                + "minute 逐分钟在线与看过人数，danmu 文字与表情弹幕，audience_activity 观众活动（含进房等名单及逐条记录）。"
                                + "只允许合选礼物、盲盒、上舰、醒目留言，合选时可用 count、sum:amount、distinctAudience、distinctPayingAudience。"), 1, 4),
                "from", string("开始统计日 YYYY-MM-DD，含当天"),
                "to", string("结束统计日 YYYY-MM-DD，含当天，首尾合计最多 366 天"),
                "filters", array(object(JSONObject.of(
                        "field", choice(keys(Field.values(), f -> f.key),
                                "name 是礼物名或盒名，openedGiftName 是开出的礼物名，audience 是观众代号，text 是弹幕文字或表情名。"
                                        + "amount 元、quantity 个数、pkMyVotes 本方票数、pkOpponentVotes 各对手票数之和。"
                                        + "startTime 是场次开播毫秒，可用等于选定一场。endTime 为毫秒，durationSeconds、effectiveSeconds 为秒。"
                                        + "场次有 messages 弹幕条数、peakOnline 在线峰值、watched 看过人数、sessionAudience 名单去重人数。"
                                        + "minute 行有 online 在线人数和 watched 看过人数。"),
                        "op", choice(keys(Comparison.values(), c -> c.key),
                                "名称及弹幕文字用 eq（等于）或 contains（包含），数值用 eq、gt（大于）、gte（大于等于）、lt（小于）、lte（小于等于）。其余用 eq。"),
                        "value", JSONObject.of("anyOf", List.of(JSONObject.of("type", "string"), JSONObject.of("type", "number")),
                                "description", "数字字段填数字。pkResult 用 win/loss/draw，guardOperation 用 open/renew，"
                                        + "guardLevel 用 captain/commander/governor，audience 用现有观众代号。")), List.of("field", "op", "value")), 0, MAX_FILTERS),
                "groupBy", array(choice(keys(Group.values(), g -> g.key),
                        "依次为统计日 day、统计日所在周的周一日期 week、统计日所在月 month、北京时间钟点 hour、"
                                + "统计日的星期几 weekday（周一为 1）、开播毫秒 session、"
                                + "记录种类 kind、名称 name、开出礼物 openedGiftName、观众代号 audience、上舰新开/续费 guardOperation、等级 guardLevel、PK 结果 pkResult。"
                                + "audienceAge 按观众在该主播处的首次留档分为 new（窗口内）和 returning（窗口前）。"
                                + "minute 从开播所在分钟起按 bucketMinutes 分组，只可搭配 session。"), 0, 2),
                "bucketMinutes", JSONObject.of("type", "integer", "minimum", 1, "maximum", MAX_BUCKET_MINUTES,
                        "description", "按 minute 分组时每组分钟数，默认 1"),
                "metrics", array(choice(List.copyOf(METRICS.keySet()),
                        "count 条数，distinctAudience 不同观众人数，sum:quantity 个数合计，sum:amount 金额合计，"
                                + "distinctPayingAudience 有实扣记录的不同付费观众人数。"
                                + "max:字段、min:字段、avg:字段为最大、最小、平均。场次时长、有效时长、弹幕条数也可 sum:字段。"), 1, MAX_MEASURES),
                "sort", object(JSONObject.of("by", string("使用一个已选择的分组键或汇总列，例如 sum:amount"),
                        "direction", choice(List.of("asc", "desc"), "升序 asc 或降序 desc")), List.of("by", "direction")),
                "limit", JSONObject.of("type", "integer", "minimum", 1, "maximum", MAX_LIMIT, "description", "可选，排序后取前 1–50 行"));
    }

    static AssistantAggregateRequest parse(JSONObject input) {
        if (input == null) throw new Invalid("请提供汇总参数");
        var kinds = list(input, "kinds", "记录种类", true, 4).stream()
                .map(item -> lookup(item, Kind.values(), kind -> kind.key, "未知记录种类")).toList();
        if (new HashSet<>(kinds).size() != kinds.size()) throw new Invalid("记录种类不能重复");
        boolean combined = kinds.size() > 1;
        if (combined && !PAID_KINDS.containsAll(kinds))
            throw new Invalid("仅礼物、盲盒、上舰和醒目留言可以合选");
        LocalDate from = date(input.get("from")), to = date(input.get("to"));
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days < 1 || days > 366) throw new Invalid("起止统计日需按顺序，范围最多 366 天");
        var groups = list(input, "groupBy", "分组键", false, 2).stream()
                .map(item -> lookup(item, Group.values(), group -> group.key, "未知分组键")).toList();
        if (new HashSet<>(groups).size() != groups.size()) throw new Invalid("分组键不能重复");
        for (Group group : groups) {
            if (group.field != null) requireField(group.field, kinds);
        }
        boolean minuteGroup = groups.contains(Group.MINUTE);
        if (minuteGroup && (kinds.contains(Kind.SESSION) || groups.stream().anyMatch(g -> g != Group.MINUTE && g != Group.SESSION)))
            throw new Invalid("分钟分组用于逐分钟或逐条明细，只能搭配场次分组");
        Integer bucketMinutes = null;
        if (input.containsKey("bucketMinutes")) {
            if (!minuteGroup) throw new Invalid("bucketMinutes 仅用于分钟分组");
            bucketMinutes = boundedInteger(input.get("bucketMinutes"), MAX_BUCKET_MINUTES, "每组分钟数");
        }
        var filters = list(input, "filters", "筛选条件", false, MAX_FILTERS).stream()
                .map(item -> filter(item, kinds)).toList();
        var measures = list(input, "metrics", "汇总列", true, MAX_MEASURES).stream().map(item -> {
            Measure measure = item instanceof String text ? METRICS.get(text) : null;
            if (measure == null) throw new Invalid("未知汇总列");
            if (combined && measure != COUNT && measure != DISTINCT && measure != DISTINCT_PAYING
                    && !(measure.operation() == Operation.SUM && measure.field() == Field.AMOUNT))
                throw new Invalid("合选记录种类只能汇总条数、金额、不同观众人数或付费人数");
            if (measure == DISTINCT) requireField(Field.AUDIENCE, kinds);
            if (measure == DISTINCT_PAYING && !PAID_KINDS.containsAll(kinds) && !kinds.contains(Kind.AUDIENCE_ACTIVITY))
                throw new Invalid("付费人数适用于付费事件或观众活动");
            if (measure.field() != null) requireField(measure.field(), kinds);
            return measure;
        }).toList();
        if (new HashSet<>(measures).size() != measures.size()) throw new Invalid("汇总列不能重复");
        Sort sort = input.containsKey("sort") ? sort(input.get("sort"), groups, measures) : null;
        Integer limit = input.containsKey("limit") ? boundedInteger(input.get("limit"), MAX_LIMIT, "前 N 行") : null;
        return new AssistantAggregateRequest(kinds, from, to, filters, groups, measures, sort, limit, bucketMinutes);
    }

    boolean usesAmount() {
        return filters.stream().anyMatch(f -> f.field() == Field.AMOUNT)
                || measures.stream().anyMatch(m -> m.field() == Field.AMOUNT);
    }

    /** 只回显已校验的结构和取值。 */
    JSONObject arguments() {
        var args = JSONObject.of("kinds", kinds.stream().map(k -> k.key).toList(),
                "from", from.toString(), "to", to.toString(), "metrics", measures.stream().map(Measure::key).toList());
        if (!groups.isEmpty()) args.put("groupBy", groups.stream().map(g -> g.key).toList());
        if (!filters.isEmpty()) args.put("filters", filters.stream().map(f ->
                JSONObject.of("field", f.field().key, "op", f.comparison().key, "value", f.value())).toList());
        if (sort != null) args.put("sort", JSONObject.of("by", sort.by(), "direction", sort.descending() ? "desc" : "asc"));
        if (limit != null) args.put("limit", limit);
        if (bucketMinutes != null) args.put("bucketMinutes", bucketMinutes);
        return args;
    }

    private static Filter filter(Object item, List<Kind> kinds) {
        if (!(item instanceof Map<?, ?> fieldMap)) throw new Invalid("筛选条件需包含 field、op 和 value");
        Field field = lookup(fieldMap.get("field"), Field.values(), f -> f.key, "未知筛选字段");
        requireField(field, kinds);
        Comparison comparison = lookup(fieldMap.get("op"), Comparison.values(), c -> c.key, "未知筛选比较方式");
        Object raw = fieldMap.get("value");
        if (field.numeric) {
            if (comparison == Comparison.CONTAINS || !(raw instanceof Number number))
                throw new Invalid("数值筛选需用数字和数值比较方式");
            BigDecimal value = decimal(number);
            if (value == null || value.precision() > 30 || Math.abs((long) value.scale()) > 18)
                throw new Invalid("筛选数字超出范围");
            return new Filter(field, comparison, value);
        }
        if (!(raw instanceof String value) || value.isBlank() || value.length() > 80)
            throw new Invalid("文字筛选需填写 1–80 字");
        if (field == Field.TEXT) {
            if (comparison != Comparison.EQ && comparison != Comparison.CONTAINS)
                throw new Invalid("弹幕文字支持等于或包含");
        } else if (field == Field.NAME || field == Field.OPENED_GIFT) {
            if (comparison != Comparison.EQ && comparison != Comparison.CONTAINS)
                throw new Invalid("名称支持等于或包含");
        } else if (comparison != Comparison.EQ) {
            throw new Invalid("结果、上舰细分和观众代号只支持等于");
        }
        boolean valid = switch (field) {
            case AUDIENCE -> ALIAS.matcher(value).matches();
            case PK_RESULT -> PK_RESULTS.contains(value);
            case GUARD_OPERATION -> GUARD_OPERATIONS.contains(value);
            case GUARD_LEVEL -> GUARD_LEVELS.contains(value);
            default -> true;
        };
        if (!valid) throw new Invalid("筛选取值无效（" + field.key + "）");
        return new Filter(field, comparison, value);
    }

    static BigDecimal decimal(Number number) {
        try {
            return number instanceof BigDecimal value ? value : new BigDecimal(number.toString());
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static void requireField(Field field, List<Kind> kinds) {
        if (!field.kinds.containsAll(kinds)) throw new Invalid("所选记录种类没有字段 " + field.key);
    }

    private static Sort sort(Object raw, List<Group> groups, List<Measure> measures) {
        if (!(raw instanceof Map<?, ?> value) || !(value.get("by") instanceof String by)
                || groups.stream().noneMatch(g -> g.key.equals(by)) && measures.stream().noneMatch(m -> m.key().equals(by)))
            throw new Invalid("排序列必须是已选择的分组键或汇总列");
        if (!"asc".equals(value.get("direction")) && !"desc".equals(value.get("direction")))
            throw new Invalid("排序方向只能为 asc 或 desc");
        return new Sort(by, "desc".equals(value.get("direction")));
    }

    private static int boundedInteger(Object raw, int max, String label) {
        if (raw instanceof Number number) {
            BigDecimal value = decimal(number);
            if (value != null && value.compareTo(BigDecimal.ONE) >= 0
                    && value.compareTo(BigDecimal.valueOf(max)) <= 0 && value.stripTrailingZeros().scale() <= 0)
                return value.intValueExact();
        }
        throw new Invalid(label + "需为 1–" + max + " 的整数");
    }

    static LocalDate date(Object raw) {
        if (raw instanceof String text && DATE.matcher(text).matches()) {
            try { return LocalDate.parse(text); }
            catch (DateTimeParseException ignored) { }
        }
        throw new Invalid("起止统计日需使用有效的 YYYY-MM-DD 日期");
    }

    private static List<?> list(JSONObject input, String key, String label, boolean required, int max) {
        if (!input.containsKey(key) && !required) return List.of();
        if (!(input.get(key) instanceof List<?> values) || required && values.isEmpty() || values.size() > max)
            throw new Invalid(label + "需为数组" + (required ? "，至少一项" : "") + "，最多 " + max + " 项");
        return values;
    }

    private static <T> T lookup(Object raw, T[] values, Function<T, String> key, String reason) {
        if (raw instanceof String text) {
            for (T value : values) if (key.apply(value).equals(text)) return value;
        }
        throw new Invalid(reason);
    }

    private static <T> List<String> keys(T[] values, Function<T, String> key) {
        return Arrays.stream(values).map(key).toList();
    }

    private static JSONObject string(String description) {
        return JSONObject.of("type", "string", "description", description);
    }

    private static JSONObject choice(List<String> values, String description) {
        return JSONObject.of("type", "string", "enum", values, "description", description);
    }

    private static JSONObject array(JSONObject items, int min, int max) {
        return JSONObject.of("type", "array", "items", items, "minItems", min, "maxItems", max, "uniqueItems", true);
    }

    private static JSONObject object(JSONObject properties, List<String> required) {
        return JSONObject.of("type", "object", "properties", properties, "required", required, "additionalProperties", false);
    }

    static final class Invalid extends IllegalArgumentException {
        Invalid(String reason) { super(reason); }
    }
}
