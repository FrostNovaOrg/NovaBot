package org.frostnova.nova.console.assistant;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.analytics.StatisticsDay;
import org.frostnova.nova.core.assistant.AssistantChartRenderer.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
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
    private static final Set<String> TIMESTAMPS = Set.of("at", "startTime", "endTime", "start", "end", "from", "to",
            "session", "minute", "firstRecordedAt", "fans.fromAt", "fans.toAt");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(StatisticsDay.ZONE);
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("day", "统计日"), Map.entry("week", "统计周"), Map.entry("month", "统计月"), Map.entry("hour", "北京时间"),
            Map.entry("at", "时间"), Map.entry("startTime", "开播时间"), Map.entry("session", "场次"), Map.entry("period", "时段"),
            Map.entry("alias", "观众"), Map.entry("name", "名称"), Map.entry("share", "贡献占比"), Map.entry("type", "分类"),
            Map.entry("messages", "弹幕数"), Map.entry("peakOnline", "在线峰值"), Map.entry("durationSeconds", "开播时长（秒）"),
            Map.entry("count", "条数"), Map.entry("distinctAudience", "观众人数"), Map.entry("distinctPayingAudience", "付费人数"),
            Map.entry("avg:online", "平均在线"), Map.entry("avg:watched", "平均看过人数"), Map.entry("sum:quantity", "个数合计"),
            Map.entry("revenue", "流水（元）"), Map.entry("sum:amount", "金额合计（元）"));
    private static final Set<String> AGGREGATE_GROUPS = Arrays.stream(AssistantAggregateRequest.Group.values())
            .map(group -> group.key).collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Set<String> AGGREGATE_COUNTS = Set.of("count", "distinctAudience", "distinctPayingAudience");
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
        var result = new Result(++lastNumber, tool, arguments, data.getString("source"), table, Set.copyOf(safe));
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
        int points = 0;
        for (var row : result.table().rows().subList(0, count)) {
            Object x = row.get(request.xColumn());
            var values = new ArrayList<Value>();
            for (String column : request.yColumns()) {
                Object cell = row.get(column);
                BigDecimal numeric = number(cell);
                if (request.type() != Type.TABLE && cell != null) {
                    if (numeric == null) throw new Invalid("折线和柱状的纵轴列需要数值");
                    if (++points > limit) throw new Invalid(limitMessage);
                }
                values.add(new Value(cellText(column, cell, privacy), numeric));
            }
            rows.add(new Row(cellText(request.xColumn(), x, privacy), List.copyOf(values)));
        }
        if (request.type() != Type.TABLE && points == 0) throw new Invalid("所选纵轴没有已知数值");

        var series = request.yColumns().stream().map(column -> columnLabel(result, column)).toList();
        var target = request.referenceLine();
        String title = label(request.title(), MAX_TITLE, context, privacy);
        String xLabel = request.xLabel() == null ? columnLabel(result, request.xColumn())
                : label(request.xLabel(), MAX_LABEL, context, privacy);
        String yLabel = label(request.yLabel(), MAX_LABEL, context, privacy);
        if (target != null) target = new ReferenceLine(target.value(), label(target.label(), MAX_LABEL, context, privacy));
        return new Chart(request.type(), title, xLabel, yLabel, series, List.copyOf(rows), target,
                result.source() == null ? "" : clean(result.source(), 48), available);
    }

    private static String columnLabel(Result result, String column) {
        if ("audience".equals(column)) return "aggregate".equals(result.tool()) ? "观众" : "观众人数";
        if ("value".equals(column) && "fans".equals(result.tool())) return "粉丝数";
        return clean(LABELS.getOrDefault(column, column), MAX_LABEL);
    }

    private static String label(String text, int max, AssistantQueryContext context, AssistantPrivacy privacy) {
        if (text == null) return null;
        String display = privacy.displayChart(text);
        if (!context.amountVisible() && (MONEY_LABEL.matcher(display).find() || AssistantAskService.containsAmount(display)))
            throw new Invalid(AssistantAggregateRequest.HIDDEN_AMOUNT + "，图表文字不能包含金额");
        return clean(display, max);
    }

    private static String cellText(String column, Object cell, AssistantPrivacy privacy) {
        if (cell == null) return "—";
        if (cell instanceof Number n) {
            if (TIMESTAMPS.contains(column) && n.longValue() >= 100_000_000_000L && n.longValue() < 10_000_000_000_000L)
                return TIME.format(Instant.ofEpochMilli(n.longValue()));
            BigDecimal number = number(n);
            if ("hour".equals(column) && number != null && number.scale() <= 0 && number.signum() >= 0 && number.compareTo(BigDecimal.valueOf(24)) < 0)
                return String.format(Locale.ROOT, "%02d:00", number.intValueExact());
            return number == null ? "—" : number.scale() > 16 || number.scale() < -16
                    ? number.toEngineeringString() : number.toPlainString();
        }
        return clean(privacy.displayChart(cell.toString()), 36);
    }

    private static boolean safeColumn(String tool, String column, String curveMetric, Set<String> nonMonetaryMetrics) {
        if ("aggregate".equals(tool)) {
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
