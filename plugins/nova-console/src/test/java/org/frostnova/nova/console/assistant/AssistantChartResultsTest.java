package org.frostnova.nova.console.assistant;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.console.assistant.AssistantQueryService.AudienceIdentity;
import org.frostnova.nova.core.assistant.AssistantChartRenderer.Chart;
import org.frostnova.nova.core.assistant.AssistantChartRenderer.Row;
import org.frostnova.nova.core.assistant.AssistantLocalStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AssistantChartResultsTest {
    @TempDir Path directory;

    // 图上露出英文字段名：本期上期对比的互动人数字段出现在图例中。
    @Test void insightsChartLabelsInteractionAudienceInChinese() {
        var results = new AssistantChartResults(List.of());
        results.add("insights", new JSONObject(), JSONObject.of("insights", JSONObject.of(
                "interactionAudience", 31, "previous", JSONObject.of("interactionAudience", 27))), Set::of);
        var request = AssistantChartRequest.parse(JSONObject.of("resultNumber", 1, "type", "bar", "xColumn", "period",
                "yColumns", List.of("interactionAudience"), "title", "本期上期互动人数"));

        var chart = results.select(request, AssistantQueryContext.CONSOLE, privacy());
        assertEquals(List.of("互动人数"), chart.series());
    }

    // 图上露出英文字段名：只读查询的小写互动人数别名出现在图例中。
    @Test void sqlChartLabelsLowercaseInteractionAudienceInChinese() {
        var results = new AssistantChartResults(List.of());
        results.addSql(new JSONObject(), JSONObject.of("columns", List.of("day", "interactionaudience"),
                "rows", JSONArray.of(JSONArray.of("2026-11-01", 31))), AssistantQueryContext.CONSOLE);
        var request = AssistantChartRequest.parse(JSONObject.of("resultNumber", 1, "type", "bar", "xColumn", "day",
                "yColumns", List.of("interactionaudience"), "title", "interaction.audience", "yLabel", "INTERACTION_AUDIENCE"));

        var chart = results.select(request, AssistantQueryContext.CONSOLE, privacy());
        assertAll(
                () -> assertEquals(List.of("互动人数"), chart.series()),
                () -> assertEquals("互动人数", chart.title()),
                () -> assertEquals("互动人数", chart.yLabel()));
    }

    // 图上露出英文字段名：只读查询的礼物金额字段出现在表头中。
    @Test void sqlTableLabelsGiftAmountInChinese() {
        var results = new AssistantChartResults(List.of());
        results.addSql(new JSONObject(), JSONObject.of("columns", List.of("day", "gift_amount"),
                "rows", JSONArray.of(JSONArray.of("2026-11-01", 31))), AssistantQueryContext.CONSOLE);
        var request = AssistantChartRequest.parse(JSONObject.of("resultNumber", 1, "type", "table", "xColumn", "day",
                "yColumns", List.of("gift_amount"), "title", "礼物金额明细"));

        var chart = results.select(request, AssistantQueryContext.CONSOLE, privacy());
        assertEquals(List.of("礼物金额（元）"), chart.series());
    }

    // 图上露出英文字段名：查不到中文名的轴名仍被画出来。
    @Test void rejectsUnknownEnglishAxisLabel() {
        var results = new AssistantChartResults(List.of());
        results.addSql(new JSONObject(), JSONObject.of("columns", List.of("day", "count"),
                "rows", JSONArray.of(JSONArray.of("2026-11-01", 31))), AssistantQueryContext.CONSOLE);
        var args = JSONObject.of("resultNumber", 1, "type", "bar", "xColumn", "day",
                "yColumns", List.of("count"), "title", "互动人数趋势", "yLabel", "foo_bar");
        var request = AssistantChartRequest.parse(args);

        var error = assertThrows(AssistantChartRequest.Invalid.class,
                () -> results.select(request, AssistantQueryContext.CONSOLE, privacy()));
        assertTrue(error.getMessage().startsWith("图上的名称要用中文："));
        assertTrue(error.getMessage().endsWith("foo_bar"));

        args.put("yLabel", "互动人数");
        assertEquals("互动人数", results.select(AssistantChartRequest.parse(args), AssistantQueryContext.CONSOLE, privacy()).yLabel());
    }

    @Test void rejectsUnknownEnglishChartTitleAndSqlHeader() {
        var results = new AssistantChartResults(List.of());
        results.addSql(new JSONObject(), JSONObject.of("columns", List.of("day", "count", "custom_metric"),
                "rows", JSONArray.of(JSONArray.of("2026-11-01", 31, 42))), AssistantQueryContext.CONSOLE);
        var args = JSONObject.of("resultNumber", 1, "type", "table", "xColumn", "day",
                "yColumns", List.of("count"), "title", "metrics.x");
        assertRejected("metrics.x", () -> results.select(AssistantChartRequest.parse(args), AssistantQueryContext.CONSOLE, privacy()));
        args.put("title", "查询结果");
        args.put("yColumns", List.of("custom_metric"));
        assertRejected("custom_metric", () -> results.select(AssistantChartRequest.parse(args), AssistantQueryContext.CONSOLE, privacy()));

        args.put("yLabel", "统计值");
        args.put("yColumns", List.of("count", "custom_metric"));
        assertRejected("custom_metric", () -> results.select(AssistantChartRequest.parse(args), AssistantQueryContext.CONSOLE, privacy()));
    }

    @Test void sqlTableLocalizesAttendanceAndMixedPkResults() {
        var results = new AssistantChartResults(List.of());
        results.addSql(new JSONObject(), JSONObject.of("columns", List.of("kind", "pk_result"),
                "rows", JSONArray.of(JSONArray.of("attendance", "mixed"))), AssistantQueryContext.CONSOLE);
        var request = AssistantChartRequest.parse(JSONObject.of("resultNumber", 1, "type", "table", "xColumn", "kind",
                "yColumns", List.of("pk_result"), "title", "记录明细"));

        var chart = results.select(request, AssistantQueryContext.CONSOLE, privacy());
        assertEquals(List.of("PK 结果"), chart.series());
        assertEquals("名单到场", chart.rows().get(0).category());
        assertEquals("多种结果", chart.rows().get(0).values().get(0).text());
    }

    @Test void categoryTranslationsUseTheColumnToResolveConflictingMeanings() {
        var results = new AssistantChartResults(List.of());
        var columns = List.of("list_quality", "status", "类别", "其他记录");
        results.addSql(new JSONObject(), JSONObject.of("columns", columns,
                "rows", JSONArray.of(JSONArray.of("unknown", "unknown", "unknown", "attendance_note"))), AssistantQueryContext.CONSOLE);
        var request = AssistantChartRequest.parse(JSONObject.of("resultNumber", 1, "type", "table", "xColumn", "list_quality",
                "yColumns", columns.subList(1, columns.size()), "title", "名单状态", "yLabel", "unused_label"));

        var row = results.select(request, AssistantQueryContext.CONSOLE, privacy()).rows().get(0);
        assertEquals("名单完整性未知", row.category());
        assertEquals(List.of("说不准", "unknown", "attendance_note"), row.values().stream().map(value -> value.text()).toList());
    }

    @Test void sketchesLocalizeKnownLabelsAndRejectUnknownFieldNames() {
        var results = new AssistantChartResults(List.of());
        results.add("sessions", new JSONObject(), JSONObject.of("items", JSONArray.of(JSONObject.of("interactionAudience", 31))), Set::of);
        var privacy = privacy();
        var sketch = results.selectSketch(new AssistantSketchRequest(1, "interactionaudience",
                "<svg><text>Interaction_Audience</text><text>31</text></svg>"), AssistantQueryContext.CONSOLE, privacy);
        assertEquals("互动人数", sketch.title());
        assertTrue(sketch.svg().contains("互动人数"));
        assertTrue(sketch.svg().contains(">31<"));
        var unknown = new AssistantSketchRequest(1, "场次示意", "<svg><text>field_12</text></svg>");
        assertRejected("图上的名称要用中文：field_12", () -> results.selectSketch(unknown, AssistantQueryContext.CONSOLE, privacy));
    }

    @Test
    @DisplayName("出图换回允许显示的观众昵称，并隐藏追问前新加入拒绝名单的观众")
    void restoresNicknamesAndHidesNewExclusionsInSavedResults() {
        var local = new AssistantLocalStore(directory, Clock.systemUTC());
        long allowed = 9300000021L, denied = 9300000022L;
        String known = local.alias("bilibili", allowed), hidden = local.alias("bilibili", denied);
        String unknown = local.alias("bilibili", 9300000023L);
        local.rememberNickname(known, "合成观众甲");
        local.rememberNickname(hidden, "合成观众乙");
        var initial = new AssistantChartResults(List.of());
        initial.add("aggregate", new JSONObject(), JSONObject.of("source", "测试礼物记录", "columns", List.of("audience", "sum:quantity"),
                "rows", JSONArray.of(JSONArray.of(known, 31), JSONArray.of(hidden, 27), JSONArray.of(unknown, 15))), Set::of);

        var followUp = new AssistantChartResults(initial.fresh());
        var privacy = new AssistantPrivacy(local, "bilibili", 1L, List.of(denied),
                () -> Map.of(denied, new AudienceIdentity(Set.of("合成观众乙"), "合成观众乙")));
        var args = JSONObject.of("resultNumber", 1, "type", "table", "xColumn", "audience", "yColumns", List.of("sum:quantity"),
                "title", hidden + "与其他观众");
        Chart chart = followUp.select(AssistantChartRequest.parse(args), AssistantQueryContext.CONSOLE, privacy);
        assertEquals(List.of("合成观众甲", "[已隐藏]", "[昵称未知]"), chart.rows().stream().map(Row::category).toList());
        assertTrue(chart.title().startsWith("[已隐藏]"));
        assertFalse(chart.toString().contains("合成观众乙"));
        assertFalse(chart.toString().contains("观众-"));
    }

    @Test
    @DisplayName("曲线通用 value 列不能在隐藏金额时把金额指标画出来")
    void curvePermissionDependsOnActualMetricRatherThanValueColumnName() {
        var results = new AssistantChartResults(List.of());
        var toolData = JSONObject.of("source", "曲线归档", "curve", JSONObject.of("items",
                JSONArray.of(JSONObject.of("at", 1794000000000L, "value", 175.25))));
        results.add("curve", JSONObject.of("metric", "gift_value"), toolData, () -> Set.of("online_count"));
        results.add("curve", JSONObject.of("metric", "online_count"), JSONObject.of("source", "曲线归档", "curve", JSONObject.of("items",
                JSONArray.of(JSONObject.of("at", 1794000000000L, "value", 59)))), () -> Set.of("online_count"));
        var args = JSONObject.of("resultNumber", 1, "type", "line", "xColumn", "at", "yColumns", List.of("value"), "title", "本场曲线");
        var restriction = new AssistantQueryContext(false);
        var privacy = privacy();
        assertTrue(assertThrows(AssistantChartRequest.Invalid.class,
                () -> results.select(AssistantChartRequest.parse(args), restriction, privacy)).getMessage().contains("金额"));
        args.put("resultNumber", 2);
        var chart = results.select(AssistantChartRequest.parse(args), restriction, privacy);
        assertEquals(List.of("在线人数"), chart.series());
        assertEquals(59, chart.rows().get(0).values().get(0).number().intValueExact());
    }

    @Test
    @DisplayName("钟点平均与流水的模板图要缩短标注，同时避免把柱高和折线点的位置取整")
    void chartAndTableUseShortNumbersForAveragesAndAmounts() {
        var results = new AssistantChartResults(List.of());
        results.add("aggregate", new JSONObject(), JSONObject.of("columns", List.of("hour", "avg:online", "sum:amount"),
                "rows", JSONArray.of(
                        JSONArray.of(8, new BigDecimal("189.16666666666670"), new BigDecimal("10463.10000000000004")),
                        JSONArray.of(9, new BigDecimal("44.02503293807642"), new BigDecimal("149057.90000000002882")),
                        JSONArray.of(10, 45, new BigDecimal("15113.70000000000014")),
                        JSONArray.of(11, 31, new BigDecimal("156.12600000000004")))), Set::of);
        var args = JSONObject.of("resultNumber", 1, "type", "line", "xColumn", "hour",
                "yColumns", List.of("avg:online", "sum:amount"), "title", "分时直播数据");
        var expected = List.of(List.of("189.2", "10463.1"), List.of("44", "149057.9"), List.of("45", "15113.7"), List.of("31", "156.13"));
        var raw = List.of(List.of("189.1666666666667", "10463.10000000000004"),
                List.of("44.02503293807642", "149057.90000000002882"),
                List.of("45", "15113.70000000000014"), List.of("31", "156.12600000000004"));
        for (String type : List.of("line", "bar", "table")) {
            args.put("type", type);
            var chart = results.select(AssistantChartRequest.parse(args), AssistantQueryContext.CONSOLE, privacy());
            assertEquals(expected, chart.rows().stream()
                    .map(row -> row.values().stream().map(value -> value.text()).toList()).toList(), type);
            if (!type.equals("table")) {
                assertEquals(raw, chart.rows().stream()
                        .map(row -> row.values().stream().map(value -> value.number().toPlainString()).toList()).toList(), type);
            }
        }
    }

    @Test
    @DisplayName("钟点柱状图和表格把场数显示成英文字段名")
    void chartAndTableLocalizeSessionCounts() {
        var results = new AssistantChartResults(List.of());
        results.add("aggregate", new JSONObject(), JSONObject.of("columns", List.of("hour", "avg:online", "sessionCount"),
                "rows", JSONArray.of(JSONArray.of(13, 65, 3), JSONArray.of(21, 189, 1))), Set::of);
        results.add("aggregate", new JSONObject(), JSONObject.of("columns",
                List.of("hour", "avg:online", "avg:watched", "onlineSessionCount", "watchedSessionCount"),
                "rows", JSONArray.of(JSONArray.of(13, 65, 110, 3, 2))), Set::of);
        var privacy = privacy();
        var args = JSONObject.of("resultNumber", 1, "type", "bar", "xColumn", "hour",
                "yColumns", List.of("sessionCount"), "yLabel", "sessionCount", "title", "各钟点直播场数");
        var bar = results.select(AssistantChartRequest.parse(args), AssistantQueryContext.CONSOLE, privacy);
        args.put("type", "table");
        args.remove("yLabel");
        var table = results.select(AssistantChartRequest.parse(args), AssistantQueryContext.CONSOLE, privacy);
        args.put("resultNumber", 2);
        args.put("yColumns", List.of("onlineSessionCount", "watchedSessionCount"));
        var multiple = results.select(AssistantChartRequest.parse(args), AssistantQueryContext.CONSOLE, privacy);
        assertAll(
                () -> assertEquals(List.of("场数"), bar.series()),
                () -> assertEquals("场数", bar.yLabel()),
                () -> assertEquals(List.of("场数"), table.series()),
                () -> assertEquals(List.of("平均在线场数", "平均看过人数场数"), multiple.series()));
    }

    @Test
    @DisplayName("新老观众柱状图会把分组字段和英文取值直接画在横轴上")
    void chartLocalizesKnownCategoriesAndLeavesUnknownValuesReadable() {
        var results = new AssistantChartResults(List.of());
        results.add("aggregate", new JSONObject(), JSONObject.of("columns", List.of("audienceAge", "sum:amount"),
                "rows", JSONArray.of(JSONArray.of("new", 12), JSONArray.of("returning", 34), JSONArray.of("other", 5))), Set::of);
        var args = JSONObject.of("resultNumber", 1, "type", "bar", "xColumn", "audienceAge",
                "yColumns", List.of("sum:amount"), "title", "本月新老观众贡献流水");
        for (String explicit : List.of("", "audienceAge", "观察对象")) {
            if (!explicit.isEmpty()) args.put("xLabel", explicit);
            var chart = results.select(AssistantChartRequest.parse(args), AssistantQueryContext.CONSOLE, privacy());
            assertEquals(explicit.equals("观察对象") ? explicit : "观众", chart.xLabel());
            assertEquals(List.of("新观众", "老观众", "other"), chart.rows().stream().map(Row::category).toList());
        }
    }

    @Test
    @DisplayName("可见聊天能用只读查询算出的礼物数量占比出示意图，金额列和收紧权限后的结果不能用于手绘")
    void sketchesCanUseSqlSharesWithoutMakingMoneyColumnsAvailable() {
        var results = new AssistantChartResults(List.of());
        results.addSql(new JSONObject(), JSONObject.of("source", "只读查询",
                "columns", List.of("gift_share", "amount_yuan"),
                "rows", JSONArray.of(JSONArray.of(new BigDecimal("0.5037"), 182.32))), AssistantQueryContext.CONSOLE);
        var share = new AssistantSketchRequest(1, "礼物数量占比", "<svg><text>50.4%</text></svg>");
        var amount = new AssistantSketchRequest(1, "礼物数量占比", "<svg><text>182.32</text></svg>");
        assertTrue(results.selectSketch(share, AssistantQueryContext.CONSOLE, privacy()).svg().contains("50.4%"));
        assertRejected("182.32", () -> results.selectSketch(amount, AssistantQueryContext.CONSOLE, privacy()));
        assertRejected("模板", () -> results.selectSketch(share, new AssistantQueryContext(false), privacy()));
    }

    @Test
    @DisplayName("分组图按所有系列计点和柱，边界内仍可画，表格必须显式收进二十行")
    void refusesOverlyDenseChartAndTableAtExactLimits() {
        var privacy = privacy();
        assertEquals(100, select("line", 100, null, privacy).rows().size());
        assertRejected("400", () -> select("line", 101, null, privacy));
        assertEquals(15, select("bar", 15, null, privacy).rows().size());
        assertRejected("60", () -> select("bar", 16, null, privacy));
        assertEquals(20, select("table", 21, 20, privacy).rows().size());
        assertRejected("20", () -> select("table", 21, 21, privacy));
        assertRejected("20", () -> select("table", 21, null, privacy));
    }

    private AssistantPrivacy privacy() {
        return new AssistantPrivacy(new AssistantLocalStore(directory, Clock.systemUTC()), "bilibili", 1L, List.of(), Map::of);
    }

    private static Chart select(String type, int rows, Integer tableRows, AssistantPrivacy privacy) {
        var queryRows = new JSONArray();
        List<String> keys = List.of("count", "sum:quantity", "min:quantity", "max:quantity");
        for (int i = 0; i < rows; i++) queryRows.add(JSONArray.of("样本 " + i, i + 1, i + 2, i + 3, i + 4));
        var results = new AssistantChartResults(List.of());
        results.add("aggregate", new JSONObject(), JSONObject.of("columns", List.of("name", "count", "sum:quantity", "min:quantity", "max:quantity"),
                "rows", queryRows, "source", "合成归档"), Set::of);
        var args = JSONObject.of("resultNumber", 1, "type", type, "xColumn", "name", "yColumns", keys, "title", "记录总览");
        if (tableRows != null) args.put("tableRows", tableRows);
        return results.select(AssistantChartRequest.parse(args), AssistantQueryContext.CONSOLE, privacy);
    }

    private static void assertRejected(String limit, org.junit.jupiter.api.function.Executable drawing) {
        assertTrue(assertThrows(AssistantChartRequest.Invalid.class, drawing).getMessage().contains(limit));
    }
}
