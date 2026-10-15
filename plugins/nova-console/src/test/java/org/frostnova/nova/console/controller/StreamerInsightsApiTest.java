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
    final Instant now = Instant.parse("2026-10-20T23:00:00Z");
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

}
