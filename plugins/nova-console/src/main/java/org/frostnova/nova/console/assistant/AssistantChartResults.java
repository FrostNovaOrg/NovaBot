package org.frostnova.nova.console.assistant;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.analytics.StatisticsDay;
import org.frostnova.nova.core.assistant.AssistantChartRenderer.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.frostnova.nova.console.assistant.AssistantChartRequest.*;

/** 本机表格与编号。列和行始终成对来自同一次查数。 */
final class AssistantChartResults {
    private static final Set<String> COUNT_COLUMNS = Set.of("period", "item", "alias", "type", "count", "speakers",
            "startTime", "endTime", "start", "end", "at", "from", "to", "label", "status",
            "durationSeconds", "effectiveSeconds", "maintenanceGapSeconds", "roomOutageSeconds", "titleChangeCount",
            "sessions", "messages", "peakOnline", "audience", "interactionAudience", "follows", "sampleCount",
            "listQuality", "endReasonText", "firstRecordedAt", "composition.firstRecorded", "composition.returning",
            "coverage.complete", "coverage.anonymous", "coverage.unknown", "coverage.missing",
            "capture.effectiveSeconds", "capture.gapSeconds", "capture.gapSessions",
            "fans.change", "fans.fromAt", "fans.toAt", "fans.fromValue", "fans.toValue");
    private static final Pattern MONEY_LABEL = Pattern.compile("金额|流水|单价|营收|收入|收益|盈亏|支出|花费|实扣|消耗|礼物价值"
            + "|[¥￥$€£]|人民币|(?i:\\b(?:CNY|RMB|USD)\\b)");
    private static final Pattern SQL_MONEY_COLUMN = Pattern.compile("amount|revenue|price|income|money|yuan|earnings|cost",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AVERAGE_COLUMN = Pattern.compile("(?:avg|average)(?:[:_].+)?", Pattern.CASE_INSENSITIVE);
    private static final Set<String> MONEY_COLUMNS = Set.of("giftPaid", "guardValue", "gift_value", "gift_paid", "guard_value",
            "super_chat_value", "box_value", "box_profit");
    private static final Set<String> TIMESTAMPS = Set.of("at", "startTime", "endTime", "start", "end", "from", "to",
            "session", "minute", "firstRecordedAt", "fans.fromAt", "fans.toAt", "start_at", "end_at", "session_start");
    private static final String SESSION_COUNT_SUFFIX = "SessionCount";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(StatisticsDay.ZONE);
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("day", "统计日"), Map.entry("week", "统计周"), Map.entry("month", "统计月"), Map.entry("hour", "北京时间"),
            Map.entry("weekday", "星期"), Map.entry("minute", "时间"), Map.entry("kind", "记录种类"),
            Map.entry("at", "时间"), Map.entry("startTime", "开播时间"), Map.entry("session", "场次"), Map.entry("period", "时段"),
            Map.entry("sessionCount", "场数"),
            Map.entry("alias", "观众"), Map.entry("name", "名称"), Map.entry("share", "贡献占比"), Map.entry("type", "分类"),
            Map.entry("audienceAge", "观众"), Map.entry("openedGiftName", "开出礼物"), Map.entry("guardOperation", "上舰方式"),
            Map.entry("guardLevel", "上舰等级"), Map.entry("pkResult", "PK 结果"),
            Map.entry("messages", "弹幕数"), Map.entry("peakOnline", "在线峰值"), Map.entry("durationSeconds", "开播时长（秒）"),
            Map.entry("count", "条数"), Map.entry("distinctAudience", "观众人数"), Map.entry("distinctPayingAudience", "付费人数"),
            Map.entry("avg:online", "平均在线"), Map.entry("avg:watched", "平均看过人数"), Map.entry("sum:quantity", "个数合计"),
            Map.entry("online", "在线人数"), Map.entry("watched", "看过人数"), Map.entry("sessionAudience", "名单人数"),
            Map.entry("quantity", "个数"), Map.entry("amount", "金额（元）"), Map.entry("effectiveSeconds", "有效时长（秒）"),
            Map.entry("pkMyVotes", "本方票数"), Map.entry("pkOpponentVotes", "对手票数"), Map.entry("endTime", "下播时间"),
            Map.entry("revenue", "流水（元）"), Map.entry("sum:amount", "金额合计（元）"));
    private static final Map<String, String> OPERATIONS = Map.of("sum", "合计", "avg", "平均", "min", "最小", "max", "最大");
    private static final Map<String, Map<String, String>> CATEGORY_LABELS = Map.of(
            "audienceAge", Map.of("new", "新观众", "returning", "老观众"),
            "guardOperation", Map.of("open", "新开", "renew", "续费"),
            "guardLevel", Map.of("captain", "舰长", "commander", "提督", "governor", "总督"),
            "pkResult", Map.of("win", "胜", "loss", "负", "draw", "平"),
            "kind", Map.of("gift", "礼物", "box", "盲盒", "guard", "上舰", "super_chat", "醒目留言", "follow", "关注",
                    "pk_settle", "PK 结算", "session", "场次", "minute", "逐分钟记录", "danmu", "弹幕", "audience_activity", "观众活动"),
            "type", Map.of("DANMU", "弹幕", "EMOJI", "表情弹幕", "SUPER_CHAT", "醒目留言"));
    private static final Set<String> AGGREGATE_GROUPS = Arrays.stream(AssistantAggregateRequest.Group.values())
            .map(group -> group.key).collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Set<String> AGGREGATE_COUNTS = Set.of("count", "distinctAudience", "distinctPayingAudience");
    private static final Set<String> AGGREGATE_DURATION_HOURS = Set.of(
            "sum:durationHours", "avg:durationHours", "sum:effectiveHours", "avg:effectiveHours");
    private static final Set<String> AGGREGATE_NUMBERS = Arrays.stream(AssistantAggregateRequest.Field.values())
            .filter(field -> field.numeric && field != AssistantAggregateRequest.Field.AMOUNT)
            .map(field -> field.key).collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final Map<Integer, Result> byNumber = new LinkedHashMap<>();
    private final List<Result> fresh = new ArrayList<>();
    private int lastNumber;

    AssistantChartResults(List<Result> previous) {
        previous.forEach(result -> byNumber.put(result.number(), result));
        lastNumber = previous.stream().mapToInt(Result::number).max().orElse(0);
    }

    List<Result> fresh() { return List.copyOf(fresh); }

    void add(String tool, JSONObject arguments, JSONObject data, Supplier<Set<String>> nonMonetaryMetrics) {
        Table table = switch (tool) {
            case "aggregate" -> aggregate(data);
            case "sessions" -> table(data.getJSONArray("items"));
            case "session" -> compare(data.getJSONObject("current"), data.getJSONObject("previous"), data.getJSONObject("median"),
                    "本场", "上一场");
            case "curve" -> table(items(data, "curve", "items"));
            case "danmu" -> counts(data.getJSONObject("byType"));
            case "revenue" -> table(data.getJSONArray("contributors"));
            case "insights" -> {
                var current = data.getJSONObject("insights");
                yield current == null ? Table.EMPTY : compare(current, current.getJSONObject("previous"), null, "本期", "上期");
            }
            case "trend" -> table(items(data, "trend", "buckets"));
            case "fans" -> table(items(data, "fans", "points"));
            default -> Table.EMPTY;
        };
        Set<String> knownMetrics = "curve".equals(tool) && table.columns().contains("value")
                || table.columns().stream().anyMatch(AssistantChartResults::metricColumn)
                ? nonMonetaryMetrics.get() : Set.of();
        String curveMetric = arguments.getString("metric");
        if (curveMetric == null || curveMetric.isBlank()) curveMetric = "online_count";
        var safe = new HashSet<String>();
        for (String column : table.columns()) {
            if (safeColumn(tool, column, curveMetric, knownMetrics)) safe.add(column);
        }
        register(tool, arguments, data, table, Set.copyOf(safe));
    }

    void addSql(JSONObject arguments, JSONObject data, AssistantQueryContext context) {
        if (data.containsKey("reason")) return;
        Table table = aggregate(data);
        register(AssistantSqlRequest.NAME, arguments, data, table,
                context.amountVisible() ? Set.of() : Set.copyOf(table.columns()));
    }

    private void register(String tool, JSONObject arguments, JSONObject data, Table table, Set<String> safe) {
        var result = new Result(++lastNumber, tool, arguments, data.getString("source"), table, safe);
        data.put("resultNumber", result.number());
        data.put("chartColumns", table.columns());
        byNumber.put(result.number(), result);
        fresh.add(result);
    }

    Chart select(AssistantChartRequest request, AssistantQueryContext context, AssistantPrivacy privacy) {
        Result result = byNumber.get(request.resultNumber());
        if (result == null) throw new Invalid("没有这个结果编号，结果可能已过期，请先查数");
        var selected = new ArrayList<String>();
        selected.add(request.xColumn());
        selected.addAll(request.yColumns());
        if (!result.table().columns().containsAll(selected)) throw new Invalid("所选列不在这个查数结果里");
        if (!context.amountVisible() && !result.safeColumns().containsAll(selected))
            throw new Invalid(AssistantAggregateRequest.HIDDEN_AMOUNT + "，不能绘制涉及金额的列");

        int available = result.table().rows().size();
        int count = request.tableRows() == null ? available : Math.min(request.tableRows(), available);
        if (count == 0) throw new Invalid("这个查数结果没有可绘制的行");
        if (request.type() == Type.TABLE && count > MAX_TABLE_ROWS)
            throw new Invalid("表格最多 20 行，请指定要展示的行数");
        int limit = request.type() == Type.LINE ? MAX_LINE_POINTS : MAX_BARS;
        String limitMessage = request.type() == Type.LINE ? "折线合计最多 400 个点" : "柱状合计最多 60 根";
        if (request.type() != Type.TABLE && count > limit) throw new Invalid(limitMessage);

        var rows = new ArrayList<Row>();
        var sourceRows = result.table().rows().subList(0, count);
        if (AssistantSqlRequest.NAME.equals(result.tool())) {
            privacy.learnSqlAudience(sourceRows.stream().flatMap(row -> selected.stream().map(row::get)));
        }
        UnaryOperator<String> display = privacy::displayChart;
        boolean table = request.type() == Type.TABLE;
        int points = 0;
        for (var row : sourceRows) {
            Object x = row.get(request.xColumn());
            if (table && x instanceof Number) x = displayNumber(result, request.xColumn(), number(x));
            var values = new ArrayList<Value>();
            for (String column : request.yColumns()) {
                Object cell = row.get(column);
                BigDecimal numeric = number(cell);
                if (!table && cell != null) {
                    if (numeric == null) throw new Invalid("折线和柱状的纵轴列需要数值");
                    if (++points > limit) throw new Invalid(limitMessage);
                }
                BigDecimal shown = displayNumber(result, column, numeric);
                String text = table ? cellText(column, cell instanceof Number ? shown : cell, display) : numberText(shown);
                values.add(new Value(text, table ? null : numeric));
            }
            rows.add(new Row(cellText(request.xColumn(), x, display), List.copyOf(values)));
        }
        if (!table && points == 0) throw new Invalid("所选纵轴没有已知数值");

        var series = request.yColumns().stream().map(column -> columnLabel(result, column)).toList();
        var target = request.referenceLine();
        String title = label(request.title(), MAX_TITLE, context, display);
        String xLabel = request.xLabel() == null ? columnLabel(result, request.xColumn())
                : axisLabel(result, request.xLabel(), context, display);
        String yLabel = axisLabel(result, request.yLabel(), context, display);
        if (target != null) target = new ReferenceLine(target.value(), label(target.label(), MAX_LABEL, context, display));
        return new Chart(request.type(), title, xLabel, yLabel, series, List.copyOf(rows), target,
                result.source() == null ? "" : clean(result.source(), 48), available);
    }

    Sketch selectSketch(AssistantSketchRequest request, AssistantQueryContext context, AssistantPrivacy privacy) {
        Result result = byNumber.get(request.resultNumber());
        if (result == null) throw new Invalid("没有这个结果编号，结果可能已过期，请先查数");
        if (result.table().rows().isEmpty()) throw new Invalid("这个查数结果没有可绘制的行");
        Collection<String> columns = result.safeColumns();
        if (context.amountVisible() && AssistantSqlRequest.NAME.equals(result.tool())) {
            columns = result.table().columns().stream()
                    .filter(column -> !MONEY_LABEL.matcher(column).find() && !SQL_MONEY_COLUMN.matcher(column).find()).toList();
        }
        if ("revenue".equals(result.tool()) || columns.isEmpty())
            throw new Invalid("手绘须引用可确认不涉及金额的数据，请用模板绘制这份结果");

        var numbers = new AssistantSketchNumbers();
        for (var row : result.table().rows()) {
            for (String column : columns) {
                Object cell = row.get(column);
                BigDecimal value = number(cell);
                if (value != null) {
                    numbers.include(value);
                    if (TIMESTAMPS.contains(column) || "hour".equals(column))
                        numbers.includeText(cellText(column, cell, privacy::mask));
                } else if (cell instanceof String text) {
                    numbers.includeText(privacy.mask(text));
                }
            }
        }
        UnaryOperator<String> checkedLabel = text -> {
            String masked = privacy.mask(text);
            String shown = privacy.displayChart(masked);
            if (containsAmount(shown)) {
                if (!context.amountVisible()) throw new Invalid(AssistantAggregateRequest.HIDDEN_AMOUNT + "，图表文字不能包含金额");
                throw new Invalid("收入等金额数据须使用模板图");
            }
            numbers.verify(masked);
            return shown;
        };
        String title = checkedLabel.apply(request.title());
        if (title.codePointCount(0, title.length()) > MAX_TITLE) throw new Invalid("手绘标题展开后过长，请缩短标题");
        title = clean(title, MAX_TITLE);
        return new Sketch(title, AssistantSketchSvg.prepare(request.svg(), checkedLabel));
    }

    private static String columnLabel(Result result, String column) {
        if ("audience".equals(column))
            return "aggregate".equals(result.tool()) || AssistantSqlRequest.NAME.equals(result.tool()) ? "观众" : "观众人数";
        if ("value".equals(column) && "fans".equals(result.tool())) return "粉丝数";
        String translated = LABELS.get(column);
        if (translated != null) return translated;
        if (column.endsWith(SESSION_COUNT_SUFFIX)) {
            String field = column.substring(0, column.length() - SESSION_COUNT_SUFFIX.length());
            String label = LABELS.getOrDefault("avg:" + field, LABELS.get(field));
            if (label != null) return label + LABELS.get("sessionCount");
        }
        int separator = column.indexOf(':');
        if (separator > 0) {
            String operation = OPERATIONS.get(column.substring(0, separator));
            String field = LABELS.get(column.substring(separator + 1));
            if (operation != null && field != null) return operation + field;
        }
        return clean(column, MAX_LABEL);
    }

    private static String axisLabel(Result result, String text, AssistantQueryContext context, UnaryOperator<String> renderer) {
        if (text != null && result.table().columns().contains(text)) text = columnLabel(result, text);
        return label(text, MAX_LABEL, context, renderer);
    }

    private static String label(String text, int max, AssistantQueryContext context, UnaryOperator<String> renderer) {
        if (text == null) return null;
        String display = renderer.apply(text);
        if (!context.amountVisible() && containsAmount(display))
            throw new Invalid(AssistantAggregateRequest.HIDDEN_AMOUNT + "，图表文字不能包含金额");
        return clean(display, max);
    }

    private static boolean containsAmount(String text) {
        return MONEY_LABEL.matcher(text).find() || AssistantAskService.containsAmount(text)
                || AssistantAskService.containsColloquialAmount(text);
    }

    private static BigDecimal displayNumber(Result result, String column, BigDecimal number) {
        if (number == null || number.scale() <= 0) return number;
        if (AVERAGE_COLUMN.matcher(column).matches() || column.contains("平均")) {
            return number.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros();
        }
        String field = "value".equals(column) && "curve".equals(result.tool())
                ? result.arguments().getString("metric") : column;
        if (field != null && metricColumn(field)) field = field.split("\\.", 3)[1];
        if (field != null && (MONEY_COLUMNS.contains(field) || SQL_MONEY_COLUMN.matcher(field).find()
                || MONEY_LABEL.matcher(field).find())) {
            return number.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        }
        return number;
    }

    private static String cellText(String column, Object cell, UnaryOperator<String> display) {
        if (cell == null) return "—";
        if (cell instanceof Number n) {
            if (TIMESTAMPS.contains(column) && n.longValue() >= 100_000_000_000L && n.longValue() < 10_000_000_000_000L)
                return TIME.format(Instant.ofEpochMilli(n.longValue()));
            BigDecimal number = number(n);
            if ("hour".equals(column) && number != null && number.scale() <= 0 && number.signum() >= 0 && number.compareTo(BigDecimal.valueOf(24)) < 0)
                return String.format(Locale.ROOT, "%02d:00", number.intValueExact());
            return numberText(number);
        }
        String text = cell.toString();
        return clean(display.apply(CATEGORY_LABELS.getOrDefault(column, Map.of()).getOrDefault(text, text)), 36);
    }

    private static String numberText(BigDecimal number) {
        if (number == null) return "—";
        return number.scale() > 16 || number.scale() < -16 ? number.toEngineeringString() : number.toPlainString();
    }

    private static boolean safeColumn(String tool, String column, String curveMetric, Set<String> nonMonetaryMetrics) {
        if ("aggregate".equals(tool)) {
            if (AGGREGATE_DURATION_HOURS.contains(column)) return true;
            int separator = column.indexOf(':');
            return separator < 0 ? AGGREGATE_GROUPS.contains(column) || AGGREGATE_COUNTS.contains(column)
                    : AGGREGATE_NUMBERS.contains(column.substring(separator + 1));
        }
        if ("value".equals(column)) return "fans".equals(tool) || "curve".equals(tool) && nonMonetaryMetrics.contains(curveMetric);
        if (metricColumn(column)) {
            String metric = column.split("\\.", 3)[1];
            return nonMonetaryMetrics.contains(metric);
        }
        return COUNT_COLUMNS.contains(column);
    }

    private static boolean metricColumn(String column) { return column.startsWith("metrics.") || column.startsWith("peaks."); }

    private static JSONArray items(JSONObject data, String group, String key) {
        var nested = data.getJSONObject(group);
        return nested == null ? null : nested.getJSONArray(key);
    }

    private static Table aggregate(JSONObject data) {
        var columns = data.getJSONArray("columns");
        var rows = data.getJSONArray("rows");
        if (columns == null || rows == null) return Table.EMPTY;
        List<String> names = columns.toJavaList(String.class);
        var values = new ArrayList<Map<String, Object>>();
        for (Object item : rows) {
            if (!(item instanceof List<?> row) || row.size() != names.size()) return Table.EMPTY;
            var mapped = new LinkedHashMap<String, Object>();
            for (int i = 0; i < names.size(); i++) {
                Object cell = row.get(i);
                if (cell instanceof Number || cell instanceof String) mapped.put(names.get(i), cell);
            }
            values.add(Map.copyOf(mapped));
        }
        return new Table(List.copyOf(names), List.copyOf(values));
    }

    private static Table compare(JSONObject current, JSONObject previous, JSONObject median, String currentLabel, String previousLabel) {
        var rows = new JSONArray();
        if (current != null) rows.add(period(currentLabel, current));
        if (previous != null) rows.add(period(previousLabel, previous));
        if (median != null) rows.add(period("此前中位数", median));
        return table(rows);
    }

    private static JSONObject period(String label, JSONObject row) {
        var fields = JSONObject.of("period", label);
        fields.putAll(row);
        fields.remove("previous");
        fields.remove("comparisons");
        return fields;
    }

    private static Table counts(JSONObject counts) {
        if (counts == null) return Table.EMPTY;
        var rows = new JSONArray();
        counts.forEach((type, count) -> rows.add(JSONObject.of("type", type, "count", count)));
        return table(rows);
    }

    private static Table table(JSONArray array) {
        if (array == null) return Table.EMPTY;
        var columns = new LinkedHashSet<String>();
        var rows = new ArrayList<Map<String, Object>>();
        for (Object item : array) {
            if (!(item instanceof JSONObject row)) continue;
            var flattened = new LinkedHashMap<String, Object>();
            flatten("", row, flattened, columns);
            if (!flattened.isEmpty()) rows.add(Map.copyOf(flattened));
        }
        return new Table(List.copyOf(columns), List.copyOf(rows));
    }

    private static void flatten(String prefix, JSONObject row, Map<String, Object> values, Set<String> columns) {
        row.forEach((key, value) -> {
            if (Set.of("reason", "source", "titles").contains(key)) return;
            String path = prefix + key;
            if (value instanceof JSONObject nested) flatten(path + ".", nested, values, columns);
            else if (value == null || value instanceof Number || value instanceof String) {
                columns.add(path);
                if (value != null) values.put(path, value);
            }
        });
    }

    record Table(List<String> columns, List<Map<String, Object>> rows) {
        private static final Table EMPTY = new Table(List.of(), List.of());
    }

    record Result(int number, String tool, JSONObject arguments, String source, Table table, Set<String> safeColumns) {
        JSONObject description() {
            return JSONObject.of("resultNumber", number, "function", tool, "arguments", arguments,
                    "source", source, "chartColumns", table.columns());
        }
    }
}
