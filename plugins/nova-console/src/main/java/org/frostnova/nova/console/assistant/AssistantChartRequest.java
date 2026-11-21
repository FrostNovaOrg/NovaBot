package org.frostnova.nova.console.assistant;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.assistant.AssistantChartRenderer.ReferenceLine;
import org.frostnova.nova.core.assistant.AssistantChartRenderer.Type;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

record AssistantChartRequest(int resultNumber, Type type, String xColumn, List<String> yColumns, String title,
                             String xLabel, String yLabel, ReferenceLine referenceLine, Integer tableRows) {
    static final String NAME = "draw_chart";
    static final String DESCRIPTION = "把查数结果画成折线、柱状或表格。只引用 resultNumber 和 chartColumns 中的列名，"
            + "数据由本机填入。折线总计最多 400 个点，柱状总计最多 60 根，表格最多 20 行；每问最多两张。";
    static final List<String> REQUIRED = List.of("resultNumber", "type", "xColumn", "yColumns", "title");
    private static final Set<String> FIELDS = Set.of("resultNumber", "type", "xColumn", "yColumns", "title",
            "xLabel", "yLabel", "referenceLine", "tableRows");
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]+");
    static final int MAX_TITLE = 30;
    static final int MAX_LABEL = 24;
    static final int MAX_TABLE_ROWS = 20;
    static final int MAX_LINE_POINTS = 400;
    static final int MAX_BARS = 60;

    static JSONObject fields() {
        return JSONObject.of(
                "resultNumber", JSONObject.of("type", "integer", "minimum", 1, "description", "查数返回的 resultNumber"),
                "type", JSONObject.of("type", "string", "enum", List.of("line", "bar", "table"),
                        "description", "line 折线、bar 柱状、table 表格"),
                "xColumn", string(80, "横轴列或表格首列，使用 chartColumns 中的列名"),
                "yColumns", JSONObject.of("type", "array", "minItems", 1, "maxItems", 4, "uniqueItems", true,
                        "items", string(80, "使用 chartColumns 中的列名"),
                        "description", "纵轴或其余表格列；多列画多条线或分组柱"),
                "title", string(MAX_TITLE, "标题，最多 30 字"),
                "xLabel", string(MAX_LABEL, "可选横轴名，表格中用作首列标题"),
                "yLabel", string(MAX_LABEL, "可选纵轴名，表格中仅在单个纵轴列时用作列标题"),
                "referenceLine", JSONObject.of("type", "object", "additionalProperties", false, "required", List.of("value", "label"),
                        "description", "折线或柱状的可选参考线，只填用户给定的参考值和说明",
                        "properties", JSONObject.of("value", JSONObject.of("type", "number", "description", "参考值"),
                                "label", string(MAX_LABEL, "参考线说明，例如任务目标"))),
                "tableRows", JSONObject.of("type", "integer", "minimum", 1, "maximum", MAX_TABLE_ROWS,
                        "description", "仅表格可填，取前 1–20 行；省略时使用全部结果行"));
    }

    static AssistantChartRequest parse(JSONObject raw) {
        if (raw == null || !FIELDS.containsAll(raw.keySet())) throw new Invalid("画图只接受结果编号、图型、列名及图表选项");
        int resultNumber = integer(raw.get("resultNumber"), Integer.MAX_VALUE, "结果编号须为正整数");
        Type type;
        try {
            String kind = requiredText(raw.get("type"), 5, "图型");
            type = Type.valueOf(kind.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new Invalid("图型须为 line、bar 或 table");
        }
        String xColumn = requiredText(raw.get("xColumn"), 80, "横轴列名");
        if (!(raw.get("yColumns") instanceof List<?> list) || list.isEmpty() || list.size() > 4)
            throw new Invalid("纵轴须选 1–4 列");
        List<String> yColumns = list.stream().map(item -> requiredText(item, 80, "纵轴列名")).toList();
        if (yColumns.contains(xColumn) || yColumns.stream().distinct().count() != yColumns.size())
            throw new Invalid("横轴和纵轴列不可重复");
        String title = requiredText(raw.get("title"), MAX_TITLE, "标题");
        String xLabel = optionalText(raw, "xLabel", "横轴名");
        String yLabel = optionalText(raw, "yLabel", "纵轴名");

        ReferenceLine reference = null;
        if (raw.containsKey("referenceLine")) {
            if (type == Type.TABLE) throw new Invalid("表格不支持参考线");
            if (!(raw.get("referenceLine") instanceof JSONObject line) || !line.keySet().equals(Set.of("value", "label")))
                throw new Invalid("参考线需要数值和说明");
            BigDecimal value = number(line.get("value"));
            if (value == null) throw new Invalid("参考线需要有限数值");
            reference = new ReferenceLine(value, requiredText(line.get("label"), MAX_LABEL, "参考线说明"));
        }
        Integer tableRows = null;
        if (raw.containsKey("tableRows")) {
            if (type != Type.TABLE) throw new Invalid("只有表格可指定行数");
            tableRows = integer(raw.get("tableRows"), MAX_TABLE_ROWS, "表格行数须在 1–20 之间");
        }
        return new AssistantChartRequest(resultNumber, type, xColumn, yColumns, title, xLabel, yLabel, reference, tableRows);
    }

    static BigDecimal number(Object value) {
        if (!(value instanceof Number)) return null;
        try {
            var number = new BigDecimal(value.toString()).stripTrailingZeros();
            return number.precision() <= 100 && Math.abs((long) number.scale()) <= 308
                    && Double.isFinite(number.doubleValue()) ? number : null;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    static String clean(String text, int max) {
        String cleaned = INVISIBLE.matcher(text).replaceAll(" ").strip();
        return cleaned.substring(0, cleaned.offsetByCodePoints(0, Math.min(max, length(cleaned))));
    }

    private static int length(String text) { return text.codePointCount(0, text.length()); }

    private static JSONObject string(int max, String description) {
        return JSONObject.of("type", "string", "minLength", 1, "maxLength", max, "description", description);
    }

    private static String requiredText(Object value, int max, String label) {
        if (!(value instanceof String text) || text.isBlank() || length(text) > max) {
            throw new Invalid(label + "须填写 1–" + max + " 字");
        }
        String cleaned = clean(text, max);
        if (cleaned.isBlank()) throw new Invalid(label + "不能为空");
        return cleaned;
    }

    private static String optionalText(JSONObject raw, String key, String label) {
        return raw.containsKey(key) ? requiredText(raw.get(key), MAX_LABEL, label) : null;
    }

    private static int integer(Object input, int max, String message) {
        BigDecimal number = number(input);
        try {
            int value = number == null ? 0 : number.intValueExact();
            if (value >= 1 && value <= max) return value;
        } catch (ArithmeticException error) {
            throw new Invalid(message);
        }
        throw new Invalid(message);
    }

    static final class Invalid extends IllegalArgumentException {
        Invalid(String message) { super(message); }
    }
}
