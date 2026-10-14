package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSON;
import org.frostnova.nova.core.analytics.LiveMetricCatalog;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.LiveEndReason;
import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.service.*;
import org.frostnova.nova.report.analytics.BilibiliLiveMetricCatalog;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("运营周报只读预览接口")
class WeeklyReportApiTest {
    @TempDir Path dir;
    StreamerController controller;
    LiveSessionArchive archive;
    WeeklyReportRenderer renderer;
    MockMvc mvc;
    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        var props = new NovaCoreProperties();
        props.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        props.getDatasource().setJsonPath(dir.resolve("datasource.json").toString());
        archive = new LiveSessionArchive(props);
        var source = mock(AbstractDataSource.class); when(source.getAllUsers()).thenReturn(List.of());
        ObjectProvider<LiveMetricCatalog> catalogs = mock(ObjectProvider.class);
        when(catalogs.orderedStream()).thenAnswer(i -> Stream.of(new BilibiliLiveMetricCatalog()));
        controller = new StreamerController(source, new StreamerDirectory(props), mock(LiveDataService.class), archive,
                new StreamerSnapshotArchive(props), new LiveDetailArchive(props), catalogs, mock(ObjectProvider.class),
                Clock.fixed(Instant.parse("2026-10-12T02:00:00Z"), ZoneOffset.UTC));
        renderer = mock(WeeklyReportRenderer.class); when(renderer.platform()).thenReturn("bilibili");
        when(renderer.render(any(), any())).thenReturn(new byte[]{(byte)137, 80, 78, 71});
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }
    @SuppressWarnings("unchecked") void injectRenderer() {
        ObjectProvider<WeeklyReportRenderer> renderers = mock(ObjectProvider.class);
        when(renderers.orderedStream()).thenAnswer(i -> Stream.of(renderer));
        ReflectionTestUtils.setField(controller, "weeklyRenderers", renderers);
    }
    void archive(Instant start) {
        archive.append(new LiveSession("bilibili", 1L, "主播示例", 2L, start.toEpochMilli(), start.plusSeconds(600).toEpochMilli(),
                600, Map.of("gift_value", 1234.5, "danmu_count", 8901d), Map.of(), LiveEndReason.NORMAL,
                List.of(), 0, Map.of("enter_users", List.of(9200000001L)), 0, Map.of(), Map.of()));
    }
    @Test @DisplayName("未知主播预览与详情返回同一404提示") void missing() throws Exception {
        String expected = controller.streamer("bilibili", 1L, "week", 1, 0).getBody().getString("message");
        String raw = mvc.perform(get("/config/api/streamers/bilibili/1/weekly-report"))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertEquals(expected, JSON.parseObject(raw).getString("message"));
    }
    @Test @DisplayName("预览返回PNG，摘要和隐藏金额参数生效") void imageAndSummary() throws Exception {
        injectRenderer(); archive(Instant.parse("2026-10-05T00:00:00Z"));
        mvc.perform(get("/config/api/streamers/bilibili/1/weekly-report").param("showRevenue", "true"))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(new byte[]{(byte)137,80,78,71}));
        String raw = mvc.perform(get("/config/api/streamers/bilibili/1/weekly-report").param("format", "json"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(JSON.parseObject(raw).getString("summary").contains("弹幕 8,901 条"));
        for (String token : List.of("流水", "元", "revenue", "9200000001")) assertFalse(raw.contains(token), token);
        String visible = mvc.perform(get("/config/api/streamers/bilibili/1/weekly-report").param("format", "json").param("showRevenue", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(JSON.parseObject(visible).getString("summary").contains("流水 1,234.50 元"));
    }
    @Test @DisplayName("无上一周归档及绘图失败时返回文字内容") void textFallback() throws Exception {
        injectRenderer(); archive(Instant.parse("2026-10-01T00:00:00Z"));
        String raw = mvc.perform(get("/config/api/streamers/bilibili/1/weekly-report"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(JSON.parseObject(raw).getString("summary").contains("没有记录到开播"));
        verify(renderer, never()).render(any(), any());
        archive(Instant.parse("2026-10-05T00:00:00Z"));
        when(renderer.render(any(), any())).thenThrow(new IllegalStateException("image unavailable"));
        raw = mvc.perform(get("/config/api/streamers/bilibili/1/weekly-report"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var text = JSON.parseObject(raw).getString("text");
        for (String token : List.of("直播时长：", "场次：", "弹幕：", "观众：", "互动观众：", "粉丝净增：")) assertTrue(text.contains(token));
        mvc.perform(get("/config/api/streamers/bilibili/1/weekly-report").param("format", "jpeg"))
                .andExpect(status().isBadRequest());
    }
}
