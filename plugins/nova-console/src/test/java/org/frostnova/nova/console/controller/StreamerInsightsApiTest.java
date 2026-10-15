package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.LiveEndReason;
import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.model.StreamerSnapshot;
import org.frostnova.nova.core.service.*;
import org.frostnova.nova.core.analytics.LiveMetricCatalog;
import org.frostnova.nova.report.analytics.BilibiliLiveMetricCatalog;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("主播近七天概况接口")
class StreamerInsightsApiTest {
    @TempDir Path dir;
    StreamerController controller;
    LiveSessionArchive archive;
    StreamerSnapshotArchive snapshots;
    final Instant now = Instant.parse("2026-10-21T07:00:00Z");
    final Instant from = Instant.parse("2026-10-14T22:00:00Z");

    @BeforeEach @SuppressWarnings("unchecked")
    void setUp() {
        var props = new NovaCoreProperties();
        props.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        props.getDatasource().setJsonPath(dir.resolve("datasource.json").toString());
        archive = new LiveSessionArchive(props);
        snapshots = new StreamerSnapshotArchive(props);
        var data = mock(AbstractDataSource.class);
        when(data.getAllUsers()).thenReturn(List.of());
        var live = mock(LiveDataService.class);
        when(live.getLiveStatus(any(), any())).thenReturn(Optional.of(false));
        ObjectProvider<LiveMetricCatalog> catalogs = mock(ObjectProvider.class);
        when(catalogs.orderedStream()).thenAnswer(i -> java.util.stream.Stream.of(new BilibiliLiveMetricCatalog()));
        ObjectProvider<LiveReportRedrawer> redrawers = mock(ObjectProvider.class);
        controller = new StreamerController(data, new StreamerDirectory(props), live, archive,
                snapshots, new LiveDetailArchive(props), catalogs, redrawers, Clock.fixed(now, ZoneOffset.UTC));
    }
    void append(Instant start) {
        archive.append(new LiveSession("bilibili", 1L, "sample", 2L,
                start.toEpochMilli(), start.plusSeconds(600).toEpochMilli(), 600,
                Map.of("gift_value", 10d, "super_chat_value", 30d, "guard_value", 198d,
                        "danmu_count", 4d), Map.of("enter_users", 1), LiveEndReason.NORMAL,
                List.of(), 0, Map.of("enter_users", List.of(9200000001L)), 0, Map.of(), Map.of()));
    }
    void comparisonSession(Instant start, double revenue, double messages, boolean listed, boolean known, String platform) {
        archive.append(new LiveSession(platform, 1L, "sample", 2L,
                start.toEpochMilli(), start.plusSeconds(600).toEpochMilli(), 600,
                Map.of("gift_value", revenue, "danmu_count", messages), Map.of(), LiveEndReason.NORMAL,
                List.of(), 0, listed ? Map.of("danmu_users", List.of(9200000001L)) : Map.of(),
                0, Map.of(), known ? Map.of() : null));
    }
    void pair(Instant start, double revenue, double messages, boolean listed, boolean known, String platform) {
        comparisonSession(start, revenue / 2, Math.floor(messages / 2), listed, known, platform);
        comparisonSession(start.plusSeconds(3600), revenue / 2, messages - Math.floor(messages / 2), true, true, platform);
    }
    JSONObject insights(String days, String platform) {
        return JSON.parseObject(JSON.toJSONString(controller.insights(platform, 1L, days).getBody().get("insights")));
    }
    JSONObject comparisons(JSONObject data) {
        var value = data.getJSONObject("comparisons");
        assertNotNull(value, "概况必须给出五项的比较结果");
        return value;
    }
    void reason(JSONObject data, String key, String code, String text) {
        var item = comparisons(data).getJSONObject(key);
        assertEquals(code, item.getString("reasonCode"));
        assertEquals(text, item.getString("reason"));
        assertNull(item.get("changeRatio"), "未比较时不得给百分比");
    }
    @Test @DisplayName("上期与本期同进度，七三十九十天均平移整个区间")
    void previousRange() {
        append(from);
        append(from.minusSeconds(7 * 86400));
        append(now.minusSeconds(7 * 86400 + 60));
        append(now.minusSeconds(7 * 86400));
        append(from.minusSeconds(60));
        for (int days : List.of(7, 30, 90)) {
            var data = insights(Integer.toString(days), "bilibili");
            var previous = data.getJSONObject("previous");
            assertNotNull(previous, "上一期必须返回同进度的起止时刻");
            assertEquals(data.getLongValue("from") - days * 86400000L, previous.getLongValue("from"));
            assertEquals(data.getLongValue("to") - days * 86400000L, previous.getLongValue("to"));
            if (days == 7) {
                assertEquals(Instant.parse("2026-10-14T07:00:00Z").toEpochMilli(), previous.getLongValue("to"));
                assertEquals(2, previous.getIntValue("sessions"), "上期同一时刻之后的场次不得混入");
            }
        }
    }
    @Test @DisplayName("流水上涨与弹幕下降方向和比例正确，并保留其余五格口径")
    void comparisonRatios() {
        pair(from.minusSeconds(7 * 86400), 100, 100, true, true, "bilibili");
        pair(from, 112, 95, true, true, "bilibili");
        var data = insights("7", "bilibili");
        var c = comparisons(data);
        assertEquals(.12, c.getJSONObject("revenue").getDoubleValue("changeRatio"), 1e-10);
        assertEquals(-.05, c.getJSONObject("messages").getDoubleValue("changeRatio"), 1e-10);
        assertEquals(0, c.getJSONObject("durationSeconds").getDoubleValue("changeRatio"));
        assertEquals(5, c.size(), "粉丝净增不得参与比较");
        assertEquals(112, data.getDoubleValue("revenue"));
    }
    @Test @DisplayName("微小变化保留精确比例供显示持平")
    void smallChange() {
        pair(from.minusSeconds(7 * 86400), 100, 100, true, true, "bilibili");
        pair(from, 100.3, 100, true, true, "bilibili");
        assertEquals(.003, comparisons(insights("7", "bilibili")).getJSONObject("revenue")
                .getDoubleValue("changeRatio"), 1e-10);
    }
    @Test @DisplayName("上期零流水说明原因，不返回无穷或非数百分比")
    void zeroBaseline() {
        pair(from.minusSeconds(7 * 86400), 0, 100, true, true, "bilibili");
        pair(from, 112, 100, true, true, "bilibili");
        reason(insights("7", "bilibili"), "revenue", "PREVIOUS_ZERO", "上期为 0");
    }
    @Test @DisplayName("上期没有开播优先于场次不足和平台口径")
    void noPreviousSessions() {
        pair(from, 100, 100, false, true, "other");
        var data = insights("7", "other");
        for (String key : List.of("durationSeconds", "revenue", "messages", "audience", "interactionAudience"))
            reason(data, key, "NO_PREVIOUS_SESSIONS", "上期没有开播");
    }
    @Test @DisplayName("任一期零或一场时五项都不下结论")
    void insufficientSessions() {
        for (int[] counts : List.of(new int[]{0, 2}, new int[]{1, 2}, new int[]{2, 1})) {
            String platform = "sample" + counts[0] + counts[1];
            for (int i = 0; i < counts[0]; i++)
                comparisonSession(from.plusSeconds(i * 3600), 100, 100, true, true, platform);
            for (int i = 0; i < counts[1]; i++)
                comparisonSession(from.minusSeconds(7 * 86400).plusSeconds(i * 3600), 100, 100, true, true, platform);
            var data = insights("7", platform);
            for (String key : List.of("durationSeconds", "revenue", "messages", "audience", "interactionAudience"))
                reason(data, key, "TOO_FEW_SESSIONS", "本期或上期少于 2 场");
        }
    }
    @Test @DisplayName("平台没有口径时只比较直播时长")
    void unknownPlatformComparison() {
        pair(from.minusSeconds(7 * 86400), 100, 100, true, true, "other");
        pair(from, 100, 100, true, true, "other");
        var data = insights("7", "other");
        assertEquals(0, comparisons(data).getJSONObject("durationSeconds").getDoubleValue("changeRatio"));
        for (String key : List.of("revenue", "messages", "audience", "interactionAudience"))
            reason(data, key, "UNSUPPORTED_METRIC", "平台没有这项口径");
    }
    @Test @DisplayName("上期缺一场名单时仅阻止两项人数比较")
    void previousMissingList() {
        pair(from.minusSeconds(7 * 86400), 100, 100, false, true, "bilibili");
        pair(from, 100, 100, true, true, "bilibili");
        var data = insights("7", "bilibili");
        for (String key : List.of("audience", "interactionAudience"))
            reason(data, key, "MISSING_AUDIENCE_LIST", "有场次没有观众名单");
        for (String key : List.of("durationSeconds", "revenue", "messages"))
            assertNotNull(comparisons(data).getJSONObject(key).get("changeRatio"));
        System.out.println("COMPARISON_SAMPLE: " + JSON.toJSONString(controller.insights("bilibili", 1L, "7").getBody()));
    }
    @Test @DisplayName("旧场次名单完整性未知仍比较人数，并给参考说明")
    void unknownListCompleteness() {
        pair(from.minusSeconds(7 * 86400), 100, 100, true, false, "bilibili");
        pair(from, 100, 100, true, true, "bilibili");
        var data = insights("7", "bilibili");
        assertNotNull(comparisons(data).getJSONObject("audience").get("changeRatio"));
        assertEquals("6.0 之前开播的场次名单完整性未知，观众数对比仅供参考", data.getString("comparisonNote"));
    }
    @Test @DisplayName("同一归档与时钟下列表和详情七天场次时长一致")
    void listMatchesInsights() throws Exception {
        java.nio.file.Files.writeString(dir.resolve("datasource.json"), "[{\"platform\":\"bilibili\",\"uid\":1,\"enabled\":true,\"targets\":[]}]");
        append(from.minusSeconds(60)); append(from); append(now.minusSeconds(14400));
        var result = controller.insights("bilibili", 1L, "7").getBody();
        assertNotNull(result.get("insights"), "详情必须提供范围统计");
        var insights = JSON.parseObject(JSON.toJSONString(result.get("insights")));
        var list = controller.streamers().getJSONArray("streamers");
        var summary = list.getJSONObject(0).getJSONObject("summary");
        assertEquals(summary.getIntValue("sessions"), insights.getIntValue("sessions"));
        assertEquals(summary.getLongValue("durationSeconds"), insights.getLongValue("durationSeconds"));
        assertEquals(238d * 2, insights.getDoubleValue("revenue"));
        snapshots.append(new StreamerSnapshot("bilibili", 1L, "sample", from.minusSeconds(7200).toEpochMilli(), Map.of("fans", 1000d)));
        snapshots.append(new StreamerSnapshot("bilibili", 1L, "sample", now.minusSeconds(60).toEpochMilli(), Map.of("fans", 1050d)));
        System.out.println("INSIGHTS_SAMPLE: " + JSON.toJSONString(controller.insights("bilibili", 1L, "7").getBody()));
    }
    @Test @DisplayName("接口只给人数，不返回观众标识与名单")
    void privacy() throws Exception {
        append(from);
        append(from.minusSeconds(7 * 86400));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        String raw = mvc.perform(get("/config/api/streamers/bilibili/1/insights").param("days", "7"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var body = JSON.parseObject(raw);
        assertNotNull(body.get("insights"), "必须返回统计才能检查名单未泄露");
        assertFalse(raw.contains("9200000001"));
        assertFalse(raw.contains("userSets"));
        assertFalse(raw.contains("enter_users"));
    }
    @Test @DisplayName("天数仅限七三十九十，不存在主播与详情使用相同提示")
    void validation() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        for (String days : List.of("8", "abc", "0", "-1", "7.0")) {
            String raw = mvc.perform(get("/config/api/streamers/bilibili/1/insights").param("days", days))
                    .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
            assertTrue(raw.contains("7、30、90"));
        }
        for (String days : List.of("7", "30", "90")) {
            assertEquals(404, controller.insights("bilibili", 1L, days).getStatusCode().value());
            assertEquals(controller.streamer("bilibili", 1L, "week", 1, 0).getBody().getString("message"),
                    controller.insights("bilibili", 1L, days).getBody().getString("message"));
        }
    }
}
