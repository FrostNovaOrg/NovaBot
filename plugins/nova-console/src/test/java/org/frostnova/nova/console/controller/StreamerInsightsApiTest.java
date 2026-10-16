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

@DisplayName("主播范围概况与观众接口")
class StreamerInsightsApiTest {
    @TempDir Path dir;
    StreamerController controller;
    LiveSessionArchive archive;
    StreamerSnapshotArchive snapshots;
    LiveDetailArchive details;
    LiveDataService live;
    ObjectProvider<LiveMetricCatalog> catalogs;
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
        live = mock(LiveDataService.class);
        when(live.getLiveStatus(any(), any())).thenReturn(Optional.of(false));
        catalogs = mock(ObjectProvider.class);
        when(catalogs.orderedStream()).thenAnswer(i -> java.util.stream.Stream.of(new BilibiliLiveMetricCatalog()));
        ObjectProvider<LiveReportRedrawer> redrawers = mock(ObjectProvider.class);
        details = new LiveDetailArchive(props);
        controller = new StreamerController(data, new StreamerDirectory(props), live, archive,
                snapshots, details, catalogs, redrawers, Clock.fixed(now, ZoneOffset.UTC));
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
    @Test @DisplayName("缺少口径或名单时旧字段仍返回键和值为null")
    void nullFieldsRemainInResponse() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        for (String platform : List.of("other", "bilibili")) {
            comparisonSession(from, 100, 100, false, true, platform);
            String raw = mvc.perform(get("/config/api/streamers/" + platform + "/1/insights").param("days", "7"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var data = JSON.parseObject(raw).getJSONObject("insights");
            assertEquals(Set.of("from", "to", "metricsKnown", "sessions", "durationSeconds", "revenue",
                    "messages", "audience", "interactionAudience", "composition", "firstRecordedAt", "coverage",
                    "fans", "daily", "follows", "capture", "previous", "comparisons", "comparisonNote"), data.keySet(), "旧字段不得丢键");
            var nullable = new ArrayList<>(List.of("audience", "interactionAudience", "composition", "firstRecordedAt", "fans"));
            if (platform.equals("other")) nullable.addAll(List.of("revenue", "messages"));
            for (String key : nullable) assertNull(data.get(key), key + "应保留null值");
            if (platform.equals("other")) {
                var day = data.getJSONArray("daily").getJSONObject(0);
                for (String key : List.of("revenue", "messages", "audience")) {
                    assertTrue(day.containsKey(key), "每日旧字段不得丢键：" + key);
                    assertNull(day.get(key));
                }
            }
        }
    }
    @Test @DisplayName("上期观众为零且缺名单时优先说明上期为0")
    void zeroAudienceBeforeMissingList() {
        Instant previous = from.minusSeconds(7 * 86400);
        comparisonSession(previous, 50, 50, false, true, "bilibili");
        archive.append(new LiveSession("bilibili", 1L, "sample", 2L,
                previous.plusSeconds(3600).toEpochMilli(), previous.plusSeconds(4200).toEpochMilli(), 600,
                Map.of("gift_value", 50d, "danmu_count", 50d), Map.of(), LiveEndReason.NORMAL,
                List.of(), 0, Map.of("danmu_users", List.of()), 0, Map.of(), Map.of()));
        pair(from, 100, 100, true, true, "bilibili");
        var data = insights("7", "bilibili");
        var baseline = data.getJSONObject("previous");
        assertEquals(1, baseline.getJSONObject("coverage").getIntValue("missing"));
        for (String key : List.of("audience", "interactionAudience")) {
            assertEquals(0, baseline.getIntValue(key));
            reason(data, key, "PREVIOUS_ZERO", "上期为 0");
        }
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
    @Test @DisplayName("观众接口只返回当页二十人，不泄露观众标识或完整名单")
    void audiencePagePrivacy() throws Exception {
        var ids = java.util.stream.LongStream.rangeClosed(9200000001L, 9200000045L).boxed().toList();
        long start = from.toEpochMilli();
        archive.append(new LiveSession("bilibili", 1L, "sample", 2L, start, start+60000, 60,
                Map.of(), Map.of(), LiveEndReason.NORMAL, List.of(), 0,
                Map.of("enter_users", ids), 0, Map.of(), Map.of()));
        details.store(new org.frostnova.nova.core.analytics.LiveDetail(1, "bilibili", 1L, "sample", 2L,
                start, start+60000, 60, Map.of(), Map.of(), Map.of(),
                Map.of("revenue_users", ids.stream().map(id -> new org.frostnova.nova.core.model.UserScore(id, "viewer"+(id-9200000000L), 10.5)).toList()),
                Map.of(), List.of(), List.of(), List.of(), Map.of(), List.of()));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        String raw = mvc.perform(get("/config/api/streamers/bilibili/1/audience").param("days", "7"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var data = JSON.parseObject(raw).getJSONObject("audience");
        assertEquals(20, data.getJSONArray("items").size(), "只返回当页的人");
        assertEquals(45, data.getIntValue("total"));
        assertFalse(raw.contains("viewer21"), "下一页的人不能提前返回");
        assertFalse(raw.contains("92000000"), "不返回观众UID");
        assertFalse(raw.contains("userSets"));
        assertFalse(raw.contains("rankings"));
        assertFalse(raw.contains("enter_users"));
        System.out.println("AUDIENCE_PAGE_SAMPLE: " + raw);
    }

    @Test @DisplayName("观众范围分组排序参数校验，越界返回末页且与概况人数一致")
    void audienceParameters() throws Exception {
        append(from.minusSeconds(60)); append(from);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        for (var param : Map.of("days", "8", "group", "other", "sort", "other").entrySet()) {
            mvc.perform(get("/config/api/streamers/bilibili/1/audience").param(param.getKey(), param.getValue()))
                    .andExpect(status().isBadRequest());
        }
        var raw = mvc.perform(get("/config/api/streamers/bilibili/1/audience").param("days", "7").param("page", "999"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var data = JSON.parseObject(raw).getJSONObject("audience");
        assertEquals(1, data.getIntValue("page"));
        assertEquals(1, data.getJSONObject("counts").getIntValue("returning"));
        assertEquals(0, data.getJSONObject("counts").getIntValue("first"));
        System.out.println("AUDIENCE_SAMPLE: " + raw);
        var defaults = mvc.perform(get("/config/api/streamers/bilibili/1/audience"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(30, JSON.parseObject(defaults).getIntValue("days"));
        mvc.perform(get("/config/api/streamers/bilibili/999/audience")).andExpect(status().isNotFound());
    }

    @Test @DisplayName("特别大的数字页码仍回末页，非数字页码返回400")
    void audienceHugePage() throws Exception {
        long start = from.toEpochMilli();
        var ids = java.util.stream.LongStream.rangeClosed(1, 45).boxed().toList();
        archive.append(new LiveSession("bilibili", 1L, "sample", 2L, start, start+60000, 60,
                Map.of(), Map.of(), LiveEndReason.NORMAL, List.of(), 0, Map.of("enter_users", ids), 0, Map.of(), Map.of()));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        for (String page : List.of("99999999999", "9999999999999999999999999999999999999999")) {
            var raw = mvc.perform(get("/config/api/streamers/bilibili/1/audience").param("page", page))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var data = JSON.parseObject(raw).getJSONObject("audience");
            assertEquals(3, data.getIntValue("page"), "大页码回末页");
            assertEquals(5, data.getJSONArray("items").size());
        }
        mvc.perform(get("/config/api/streamers/bilibili/1/audience").param("page", "abc"))
                .andExpect(status().isBadRequest());
    }

    @Test @DisplayName("三百场明细归档的观众计算耗时")
    void audienceArchiveTiming() throws Exception {
        var ids = java.util.stream.LongStream.rangeClosed(9200000001L, 9200000045L).boxed().toList();
        for (int n=0; n<300; n++) {
            long start = now.minusSeconds(3600L*(n+1)).toEpochMilli();
            archive.append(new LiveSession("bilibili", 1L, "sample", 2L, start, start+60000, 60,
                    Map.of(), Map.of(), LiveEndReason.NORMAL, List.of(), 0, Map.of("enter_users", ids), 0, Map.of(), Map.of()));
            details.store(new org.frostnova.nova.core.analytics.LiveDetail(1, "bilibili", 1L, "sample", 2L,
                    start, start+60000, 60, Map.of(), Map.of(), Map.of(),
                    Map.of("revenue_users", ids.stream().map(id -> new org.frostnova.nova.core.model.UserScore(id, "viewer"+(id-9200000000L), 10.5)).toList()),
                    Map.of(), List.of(), List.of(), List.of(), Map.of(), List.of()));
        }
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        long started = System.nanoTime();
        String raw = mvc.perform(get("/config/api/streamers/bilibili/1/audience").param("days", "30"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        double millis = (System.nanoTime()-started)/1_000_000d;
        var data = JSON.parseObject(raw).getJSONObject("audience");
        assertEquals(45, data.getIntValue("total"));
        assertEquals(300, data.getJSONArray("items").getJSONObject(0).getIntValue("attendance"));
        System.out.printf(java.util.Locale.ROOT, "AUDIENCE_TIMING: sessions=300 viewers=45 detailRows=13500 elapsedMs=%.3f%n", millis);
    }

    @Test @DisplayName("开播时段接口返回84格及整场明细且180天独立筛选")
    void startSlotsApi() throws Exception {
        append(Instant.parse("2026-06-01T02:00:00Z"));
        append(Instant.parse("2026-09-07T02:00:00Z"));
        append(Instant.parse("2026-09-14T02:00:00Z"));
        append(Instant.parse("2026-09-21T02:00:00Z"));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        for (String days : List.of("90", "180")) {
            String raw = mvc.perform(get("/config/api/streamers/bilibili/1/start-slots").param("days", days))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var body = JSON.parseObject(raw);
            // 防止分区计算存在但接口漏传，页面静默显示空表。
            assertTrue(body.containsKey("areas"), "开播节奏接口必须返回areas");
            var areas = body.getJSONArray("areas");
            assertEquals(1, areas.size());
            var area = areas.getJSONObject(0);
            assertEquals("未记录分区", area.getString("area"));
            assertEquals(days.equals("90") ? 3 : 4, area.getIntValue("sessions"));
            assertEquals(area.getIntValue("sessions"), area.getJSONArray("starts").size());
            assertEquals(1428d, area.getDouble("revenuePerHour"));
            var data = body.getJSONObject("startSlots");
            assertEquals(Integer.parseInt(days), body.getIntValue("days"));
            assertEquals(84, data.getJSONArray("cells").size(), "接口须给完整热力表");
            var cell = data.getJSONArray("cells").getJSONObject(5);
            assertEquals(days.equals("90") ? 3 : 4, cell.getIntValue("sessions"), "180天须含六月场次");
            assertEquals(1428d, cell.getDoubleValue("revenuePerHour"));
            assertEquals(24d, cell.getDoubleValue("messagesPerHour"));
            assertEquals(238d, cell.getJSONArray("sessionItems").getJSONObject(0).getDoubleValue("revenue"));
            assertEquals(1, data.getJSONArray("topRevenue").size());
            assertFalse(raw.contains("userSets"));
            assertFalse(raw.contains("enter_users"));
            if (days.equals("90")) System.out.println("START_SLOTS_SAMPLE: " + raw);
        }
        mvc.perform(get("/config/api/streamers/bilibili/1/start-slots"))
                .andExpect(status().isOk());
    }

    @Test @DisplayName("开播时段只接受90或180天且不存在主播返回404")
    void startSlotsValidation() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        for (String days : List.of("7", "30", "abc", "0", "90.0")) {
            mvc.perform(get("/config/api/streamers/bilibili/1/start-slots").param("days", days))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/config/api/streamers/bilibili/1/start-slots"))
                .andExpect(status().isNotFound());
    }

    @Test @DisplayName("开播时段接口和主播概况一样要求登录")
    void startSlotsRequiresLogin() throws Exception {
        var auth = mock(org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService.class);
        when(auth.isEnabled()).thenReturn(true);
        var filter = new org.frostnova.nova.core.config.ui.ConfigUiSecurityFilter("sample-token",
                new org.frostnova.nova.core.util.IpMatcher(List.of("0.0.0.0/0", "::/0")), auth,
                new NovaCoreProperties.ConfigUi.Auth(), new NovaCoreProperties.ConfigUi.Agreement());
        var mvc = MockMvcBuilders.standaloneSetup(controller).addFilters(filter).build();
        mvc.perform(get("/config/api/streamers/bilibili/1/start-slots"))
                .andExpect(status().isUnauthorized());
    }

    // 周月数字必须与同范围概况一致，观众跨周重复出现时仍只算一人。
    @Test @DisplayName("周月趋势沿用概况口径与名单去重")
    void trendSummaryRules() throws Exception {
        append(from); append(now.minusSeconds(3600));
        double overviewRevenue = insights("7", "bilibili").getDoubleValue("revenue");
        for (String period : List.of("day", "week", "month")) {
            var raw = MockMvcBuilders.standaloneSetup(controller).build().perform(
                    get("/config/api/streamers/bilibili/1/trend").param("period", period).param("days", "7"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var buckets = JSON.parseObject(raw).getJSONObject("trend").getJSONArray("buckets");
            double sum = 0;
            for (int i = 0; i < buckets.size(); i++) {
                var bucket = buckets.getJSONObject(i);
                assertTrue(bucket.containsKey("revenue"), "趋势桶必须给概况口径流水");
                sum += bucket.getDoubleValue("revenue");
                if (bucket.getIntValue("sessions") > 0) assertEquals(1, bucket.getIntValue("audience"));
            }
            assertEquals(overviewRevenue, sum);
            if (period.equals("week")) assertEquals(2, buckets.size());
            if (period.equals("month")) assertEquals(1, buckets.size());
            System.out.println("TREND_" + period.toUpperCase(Locale.ROOT) + "_SAMPLE: " + raw);
        }
        assertEquals(1, insights("7", "bilibili").getIntValue("audience"), "概况观众不能相加各周人数");
    }

    // 范围从周中、月中开始且在周期内截止，接口边界不能多带范围外的日期。
    @Test @DisplayName("周趋势首尾边界截到全页范围")
    void trendWeekClipsRange() throws Exception {
        assertTrendClipsRange("week");
    }

    @Test @DisplayName("月趋势首尾边界截到全页范围")
    void trendMonthClipsRange() throws Exception {
        assertTrendClipsRange("month");
    }

    private void assertTrendClipsRange(String period) throws Exception {
        append(from);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        var response = mvc.perform(get("/config/api/streamers/bilibili/1/trend")
                        .param("period", period).param("days", "30"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var buckets = JSON.parseObject(response).getJSONObject("trend").getJSONArray("buckets");
        var range = org.frostnova.nova.core.analytics.StreamerInsights.recent(30, Clock.fixed(now, ZoneOffset.UTC));
        assertAll(
                () -> assertEquals(range.from().toEpochMilli(), buckets.getJSONObject(0).getLongValue("start"),
                        period + " first start must equal page from"),
                () -> assertEquals(range.to().toEpochMilli(), buckets.getJSONObject(buckets.size() - 1).getLongValue("end"),
                        period + " last end must equal page to"),
                () -> {
                    for (int i = 0; i < buckets.size(); i++) {
                        var bucket = buckets.getJSONObject(i);
                        assertTrue(bucket.getLongValue("start") >= range.from().toEpochMilli());
                        assertTrue(bucket.getLongValue("end") <= range.to().toEpochMilli());
                        if (i > 0) assertEquals(buckets.getJSONObject(i - 1).getLongValue("end"), bucket.getLongValue("start"));
                    }
                });
    }

    // 空周、空月也必须占据页面范围，不能只保留有场次的两端。
    @Test @DisplayName("周月趋势保留范围内所有空桶")
    void trendEmptyPeriods() {
        append(now.minusSeconds(3600));
        for (String period : List.of("week", "month")) {
            var raw = JSON.parseObject(JSON.toJSONString(controller.rangeTrend("bilibili", 1L, period, "90").getBody()));
            var buckets = raw.getJSONObject("trend").getJSONArray("buckets");
            assertEquals(period.equals("week") ? 14 : 4, buckets.size());
            assertTrue(buckets.getJSONObject(0).containsKey("revenue"), "空周期也须有概况口径");
            assertEquals(0d, buckets.getJSONObject(0).getDoubleValue("revenue"));
            assertEquals(0, buckets.getJSONObject(0).getIntValue("sessions"));
        }
    }

    // 新增关注须是归档计数，不能误用粉丝快照差值；没有规则则保留null。
    @Test @DisplayName("新增关注合计与每日值且未知平台不伪装零")
    void followsTotals() throws Exception {
        archive.append(new LiveSession("bilibili", 1L, "sample", 2L, from.toEpochMilli(),
                from.plusSeconds(600).toEpochMilli(), 600, Map.of("follow_count", 9d), Map.of()));
        var raw = MockMvcBuilders.standaloneSetup(controller).build().perform(
                get("/config/api/streamers/bilibili/1/insights").param("days", "7"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var data = JSON.parseObject(raw).getJSONObject("insights");
        assertEquals(9L, data.getLong("follows"), "本期必须返回新增关注计数");
        assertEquals(9L, data.getJSONArray("daily").getJSONObject(0).getLong("follows"));
        comparisonSession(from, 0, 0, false, false, "other");
        var unsupported = JSON.parseObject(JSON.toJSONString(controller.insights("other", 1L, "7").getBody(),
                com.alibaba.fastjson2.JSONWriter.Feature.WriteNulls)).getJSONObject("insights");
        assertTrue(unsupported.containsKey("follows"));
        assertNull(unsupported.get("follows"));
        System.out.println("FOLLOWS_INSIGHTS_SAMPLE: " + raw);
    }

    // 空周期不能混入跨界长场；明确选择空开播列表应得到零场。
    @Test @DisplayName("趋势空周期下钻返回空列表")
    void emptyTrendDrill() throws Exception {
        append(from);
        var raw = MockMvcBuilders.standaloneSetup(controller).build().perform(
                get("/config/api/streamers/bilibili/1/sessions").param("from", "" + from.toEpochMilli())
                        .param("to", "" + now.toEpochMilli()).param("starts", ""))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(0, JSON.parseObject(raw).getJSONObject("sessions").getIntValue("total"));
    }

    // 本场流水只能采用概况声明的收入项，不能把所有金额都相加。
    @Test @DisplayName("直播流水与归档概况共用收入口径")
    void liveRevenueRules() throws Exception {
        append(from);
        java.nio.file.Files.writeString(dir.resolve("datasource.json"),
                "[{\"platform\":\"bilibili\",\"uid\":1,\"enabled\":true,\"targets\":[]}]");
        when(live.getLiveStatus("bilibili", 1L)).thenReturn(Optional.of(true));
        when(live.getLiveMetrics("bilibili", 1L)).thenReturn(Map.of("gift_value", 10d,
                "super_chat_value", 30d, "guard_value", 198d, "box_profit", 999d));
        var raw = MockMvcBuilders.standaloneSetup(controller).build().perform(get("/config/api/streamers/bilibili/1/live"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var data = JSON.parseObject(raw);
        assertEquals(238d, data.getDouble("revenue"), "本场必须下发按规则计算的流水");
        assertEquals(insights("7", "bilibili").getDoubleValue("revenue"), data.getDoubleValue("revenue"));
        assertEquals("danmu_count", controller.streamer("bilibili", 1L, "week", 1, 20).getBody()
                .getJSONObject("sessions").getString("messageKey"), "最近归档弹幕也使用平台规则");
        System.out.println("LIVE_REVENUE_SAMPLE: " + raw);
        for (var metrics : List.of(Map.<String, Double>of(), Map.of("danmu_count", 2d), Map.of("gift_value", 0d))) {
            when(live.getLiveMetrics("bilibili", 1L)).thenReturn(metrics);
            var sample = controller.live("bilibili", 1L, null).getBody();
            assertEquals(metrics.isEmpty() ? null : Double.valueOf(0), sample.getDouble("revenue"));
            System.out.println("LIVE_BILIBILI_SAMPLE: " + JSON.toJSONString(sample, com.alibaba.fastjson2.JSONWriter.Feature.WriteNulls));
        }
        when(live.getLiveStatus("bilibili", 1L)).thenReturn(Optional.of(false));
        System.out.println("LIVE_BILIBILI_OFF_SAMPLE: " + JSON.toJSONString(controller.live("bilibili", 1L, null).getBody(),
                com.alibaba.fastjson2.JSONWriter.Feature.WriteNulls));
    }

    // 抓住未收到收入事件的快照被写成未知；空快照仍表示尚未采集。
    @Test @DisplayName("直播流水与弹幕区分缺失和真实零并遵循平台规则")
    void liveMissingAndZero() throws Exception {
        append(from);
        java.nio.file.Files.writeString(dir.resolve("datasource.json"),
                "[{\"platform\":\"bilibili\",\"uid\":1,\"enabled\":true,\"targets\":[]}]");
        var catalog = mock(LiveMetricCatalog.class);
        when(catalog.platform()).thenReturn("bilibili");
        when(catalog.insights()).thenReturn(new LiveMetricCatalog.Insights(
                List.of("income_a", "income_b"), "chat_total", List.of(), List.of(), null));
        when(catalogs.orderedStream()).thenAnswer(i -> java.util.stream.Stream.of(catalog));
        when(live.getLiveStatus("bilibili", 1L)).thenReturn(Optional.of(true));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        for (var metrics : List.of(Map.<String, Double>of(), Map.of("unrelated", 5d),
                Map.of("income_b", 0d, "chat_total", 0d), Map.of("income_a", 12d, "chat_total", 7d))) {
            when(live.getLiveMetrics("bilibili", 1L)).thenReturn(metrics);
            var raw = mvc.perform(get("/config/api/streamers/bilibili/1/live"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var data = JSON.parseObject(raw);
            assertTrue(data.containsKey("revenue"));
            assertEquals(metrics.containsKey("income_a") ? Double.valueOf(12) : !metrics.isEmpty() ? Double.valueOf(0) : null,
                    data.getDouble("revenue"), "有快照没收入键时流水为零，没有快照才未知");
            assertTrue(data.containsKey("messages"), "直播必须下发按规则计算的弹幕");
            assertEquals(metrics.get("chat_total"), data.getDouble("messages"), "弹幕只读取插件声明的键");
        }
        when(live.getLiveStatus("bilibili", 1L)).thenReturn(Optional.of(false));
        var raw = mvc.perform(get("/config/api/streamers/bilibili/1/live"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertNull(JSON.parseObject(raw).getDouble("revenue"));
        assertNull(JSON.parseObject(raw).getDouble("messages"));
    }

    // 每个桶用不同金额，防止合并概况字段时把相邻周期的数字移过来。
    @Test @DisplayName("趋势概况字段与原始周期逐桶对齐")
    void trendBucketAlignment() {
        for (int days : List.of(2, 12, 40, 70))
            comparisonSession(now.minusSeconds(days * 86400L), days * 3d, days, true, true, "bilibili");
        for (String period : List.of("day", "week", "month")) {
            var raw = JSON.parseObject(JSON.toJSONString(controller.rangeTrend("bilibili", 1L, period, "90").getBody()));
            var buckets = raw.getJSONObject("trend").getJSONArray("buckets");
            for (int i = 0; i < buckets.size(); i++) {
                var bucket = buckets.getJSONObject(i);
                assertEquals(bucket.getJSONObject("metrics").getDoubleValue("gift_value"), bucket.getDoubleValue("revenue"),
                        "周期流水必须与本桶原始数据对齐：" + period + " " + i);
                assertEquals(bucket.getJSONObject("metrics").getDoubleValue("danmu_count"), bucket.getDoubleValue("messages"));
            }
        }
    }

    // 重叠缺口只扣较大者；范围前开播即使跨入范围也不能计入，趋势按开播归桶。
    @Test @DisplayName("记录覆盖与名单分类使用各自范围内开播的场次")
    void recordingCoverage() {
        var starts = List.of(from.minusSeconds(60), from, from.plusSeconds(86340),
                from.plusSeconds(86400), from.plusSeconds(3 * 86400));
        for (int i = -1; i < 4; i++) {
            Instant start = starts.get(i + 1);
            archive.append(new LiveSession("bilibili", 1L, "sample", 2L,
                    start.toEpochMilli(), start.plusSeconds(3600).toEpochMilli(), 3600,
                    Map.of("follow_count", 2d), Map.of(), LiveEndReason.NORMAL, List.of(),
                    i <= 0 ? 30 : 0, i == 3 ? Map.of() : Map.of("enter_users", List.of()),
                    i <= 0 ? 20 : 0, Map.of(), i == 2 ? null : i == 1 ? Map.of("enter_users", 1L) : Map.of()));
        }
        var data = insights("7", "bilibili");
        assertEquals(JSONObject.of("effectiveSeconds", 14370, "gapSeconds", 30, "gapSessions", 1), data.getJSONObject("capture"), "区间缺口不得相加或混入范围前开播场次");
        assertEquals(JSONObject.of("complete", 1, "anonymous", 1, "unknown", 1, "missing", 1), data.getJSONObject("coverage"));
        var slots = JSON.parseObject(JSON.toJSONString(controller.startSlots("bilibili", 1L, "90").getBody())).getJSONObject("startSlots");
        assertEquals(5, slots.getIntValue("sessions"));
        assertEquals(18000, slots.getLongValue("durationSeconds"));
        assertEquals(17940, slots.getJSONObject("capture").getLongValue("effectiveSeconds"));
        assertEquals(2, slots.getJSONObject("capture").getIntValue("gapSessions"));
        assertEquals(2, slots.getJSONObject("coverage").getIntValue("complete"));
        for (String period : List.of("day", "week", "month")) {
            var buckets = JSON.parseObject(JSON.toJSONString(controller.rangeTrend("bilibili", 1L, period, "7").getBody())).getJSONObject("trend").getJSONArray("buckets");
            for (int i = 0; i < buckets.size(); i++) {
                var bucket = buckets.getJSONObject(i);
                var expectedStarts = starts.stream().map(Instant::toEpochMilli)
                        .filter(at -> at >= bucket.getLongValue("start") && at < bucket.getLongValue("end")).toList();
                int count = expectedStarts.size();
                assertEquals(count, bucket.getIntValue("sessions"), "跨进本桶但在前桶开播的场次不得混入");
                assertEquals(expectedStarts, bucket.getJSONArray("starts").toJavaList(Long.class));
                assertTrue(bucket.containsKey("effectiveSeconds"), "趋势必须输出有效时长");
                var coverage = bucket.getJSONObject("coverage");
                assertNotNull(coverage, "趋势必须输出名单分类");
                assertEquals(count, coverage.values().stream().mapToInt(v -> ((Number) v).intValue()).sum());
                String[] qualities = {"complete", "anonymous", "unknown", "missing"};
                for (int q = 0; q < qualities.length; q++)
                    assertEquals(expectedStarts.contains(starts.get(q + 1).toEpochMilli()) ? 1 : 0,
                            coverage.getIntValue(qualities[q]), "名单只算本桶开播场次");
                assertEquals(count * 3600L - (bucket.getJSONArray("starts").contains(from.toEpochMilli()) ? 30 : 0), bucket.getLongValue("effectiveSeconds"), "只扣本桶开播场次的缺口");
            }
            if (period.equals("day")) System.out.println("RECORDING_TREND_SAMPLE: " + buckets.getJSONObject(0));
        }
        var items = controller.streamer("bilibili", 1L, "week", 1, 20).getBody().getJSONObject("sessions").getJSONArray("items");
        var qualities = new HashSet<String>();
        for (int i = 0; i < items.size(); i++) {
            var item = items.getJSONObject(i);
            qualities.add(item.getString("listQuality"));
            assertEquals(item.getLongValue("maintenanceGapSeconds") > 0 ? 3570 : 3600, item.getLongValue("effectiveSeconds"));
            if (item.getLongValue("startTime") == from.toEpochMilli()) System.out.println("RECORDING_SESSION_SAMPLE: " + item);
        }
        assertEquals(Set.of("complete", "anonymous", "unknown", "missing"), qualities);
        System.out.println("RECORDING_INSIGHTS_SAMPLE: " + data);
        System.out.println("RECORDING_SLOTS_SAMPLE: " + JSONObject.of("sessions", slots.get("sessions"), "durationSeconds", slots.get("durationSeconds"), "capture", slots.get("capture"), "coverage", slots.get("coverage")));
    }

    // 单场有效时长与四种名单词均从接口读取，避免页面自行猜测完整性。
    @Test void sessionRecordingFields() {
        String[] qualities = {"complete", "anonymous", "unknown", "missing"};
        for (int i = 0; i < qualities.length; i++) {
            var item = LiveSessionJson.of(new LiveSession("bilibili", 1L, "sample", 2L,
                    from.toEpochMilli(), from.plusSeconds(3600).toEpochMilli(), 3600, Map.of(), Map.of(),
                    LiveEndReason.NORMAL, List.of(), 30, i == 3 ? Map.of() : Map.of("enter_users", List.of()),
                    20, Map.of(), i == 2 ? null : i == 1 ? Map.of("enter_users", 1L) : Map.of()));
            assertEquals(3570L, item.getLong("effectiveSeconds"), "有效时长必须扣较大缺口");
            assertEquals(qualities[i], item.getString("listQuality"), "四种名单不能混淆");
        }
    }
    // 跨进次日的整场只归开播桶，不能按重叠重复计数。
    @Test void trendRecordingFields() {
        append(from);
        var buckets = JSON.parseObject(JSON.toJSONString(controller.rangeTrend("bilibili", 1L, "day", "7").getBody())).getJSONObject("trend").getJSONArray("buckets");
        assertEquals(600L, buckets.getJSONObject(0).getLong("effectiveSeconds"), "趋势必须带出有效时长");
        assertEquals(1, buckets.getJSONObject(0).getJSONObject("coverage").getIntValue("complete"));
        assertEquals(0L, buckets.getJSONObject(1).getLong("effectiveSeconds"));
        assertEquals(0, buckets.getJSONObject(1).getJSONObject("coverage").getIntValue("complete"));
    }

}
