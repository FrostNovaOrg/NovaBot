package org.frostnova.nova.console.assistant;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.assistant.AssistantChartRenderer.Chart;
import org.frostnova.nova.core.assistant.AssistantChartRenderer.Row;
import org.frostnova.nova.core.assistant.AssistantLocalStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AssistantChartResultsTest {
    @TempDir Path directory;

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
        var privacy = new AssistantPrivacy(local, "bilibili", 1L, List.of(denied), () -> Map.of(denied, Set.of("合成观众乙")));
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
        assertEquals(59, chart.rows().get(0).values().get(0).number().intValueExact());
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
