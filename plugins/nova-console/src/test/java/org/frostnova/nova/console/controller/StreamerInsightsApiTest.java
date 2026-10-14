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
}
