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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.frostnova.nova.core.model.*;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.handler.WeeklyReportPushHandler;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.sender.PushGate;
import org.mockito.ArgumentCaptor;
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
    AbstractDataSource source;
    NovaCoreProperties props;
    ObjectProvider<LiveMetricCatalog> catalogs;
    StreamerSnapshotArchive snapshots;
    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        props = new NovaCoreProperties();
        props.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        props.getDatasource().setJsonPath(dir.resolve("datasource.json").toString());
        archive = new LiveSessionArchive(props);
        source = mock(AbstractDataSource.class); when(source.getAllUsers()).thenReturn(List.of());
        catalogs = mock(ObjectProvider.class);
        when(catalogs.orderedStream()).thenAnswer(i -> Stream.of(new BilibiliLiveMetricCatalog()));
        snapshots = new StreamerSnapshotArchive(props);
        controller = new StreamerController(source, new StreamerDirectory(props), mock(LiveDataService.class), archive,
                snapshots, new LiveDetailArchive(props), catalogs, mock(ObjectProvider.class),
                Clock.fixed(Instant.parse("2026-10-12T02:00:00Z"), ZoneOffset.UTC));
        renderer = mock(WeeklyReportRenderer.class); when(renderer.platform()).thenReturn("bilibili");
        when(renderer.render(any(), any(), any())).thenReturn(new byte[]{(byte)137, 80, 78, 71});
        injectRenderer();
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }
    @SuppressWarnings("unchecked") void injectRenderer() {
        ObjectProvider<WeeklyReportRenderer> renderers = mock(ObjectProvider.class);
        when(renderers.orderedStream()).thenAnswer(i -> Stream.of(renderer));
        ReflectionTestUtils.setField(controller, "weeklyReports", new WeeklyReportContentService(source,
                new StreamerDirectory(props), archive, snapshots, mock(LiveDataService.class), catalogs, renderers));
    }
    void archive(Instant start) {
        archive.append(new LiveSession("bilibili", 1L, "主播示例", 2L, start.toEpochMilli(), start.plusSeconds(600).toEpochMilli(),
                600, Map.of("gift_value", 1234.5, "danmu_count", 8901d), Map.of(), LiveEndReason.NORMAL,
                List.of(), 0, Map.of("enter_users", List.of(9200000001L)), 0, Map.of(), Map.of()));
    }

    @Test @DisplayName("周报取数保留前周场次与边界快照，比较与概况一致")
    void previewLoadsBothWeeks() {
        var now = Instant.parse("2026-10-12T02:00:00Z");
        var range = org.frostnova.nova.core.analytics.WeeklyReport.previousWeek(now);
        var previousFrom = range.from().minusSeconds(7 * 86400);
        for (var at : List.of(previousFrom.minusSeconds(86400), previousFrom, range.from().minusSeconds(1),
                range.from(), range.to().minusSeconds(1), range.to())) archive(at);
        snapshots.append(new StreamerSnapshot("bilibili", 1L, "示例", previousFrom.minusSeconds(1).toEpochMilli(), Map.of("fans", 100d)));
        snapshots.append(new StreamerSnapshot("bilibili", 1L, "示例", range.from().toEpochMilli(), Map.of("fans", 110d)));
        snapshots.append(new StreamerSnapshot("bilibili", 1L, "示例", range.to().minusSeconds(1).toEpochMilli(), Map.of("fans", 120d)));
        var content = (WeeklyReportContentService) ReflectionTestUtils.getField(controller, "weeklyReports");
        var data = content.preview("bilibili", 1L, now, true).orElseThrow().data();
        var direct = new org.frostnova.nova.core.analytics.StreamerInsights().comparePeriods(
                archive.find(0, Long.MAX_VALUE), snapshots.find(0, range.to().toEpochMilli()), range.from(), range.to(),
                previousFrom, range.from(), new BilibiliLiveMetricCatalog().insights());
        assertEquals(direct.comparisons(), data.comparisons());
        assertEquals(direct.current().fans(), data.fans());
        assertEquals(direct.current().composition(), data.composition());
        assertEquals(2, data.metrics().get("sessions"));
        for (String key : List.of("durationSeconds", "revenue", "messages", "audience"))
            assertEquals(0d, data.comparisons().get(key).changeRatio());
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
        verify(renderer, never()).render(any(), any(), any());
        archive(Instant.parse("2026-10-05T00:00:00Z"));
        when(renderer.render(any(), any(), any())).thenThrow(new IllegalStateException("image unavailable"));
        raw = mvc.perform(get("/config/api/streamers/bilibili/1/weekly-report"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var text = JSON.parseObject(raw).getString("text");
        for (String token : List.of("直播时长：", "场次：", "弹幕：", "观众：", "互动观众：", "粉丝净增：")) assertTrue(text.contains(token));
        mvc.perform(get("/config/api/streamers/bilibili/1/weekly-report").param("format", "jpeg"))
                .andExpect(status().isBadRequest());
    }
    @ParameterizedTest(name = "金额可见={0}：定时发送的摘要与预览 JSON 相同")
    @ValueSource(booleans = {true, false})
    @DisplayName("同一会话同一统计周的定时摘要与预览逐字相同")
    void scheduledMatchesPreview(boolean visible) throws Exception {
        archive(Instant.parse("2026-10-05T00:00:00Z"));
        var user = new PushUser(); user.setUid(1L); user.setPlatform("bilibili"); user.setUname("主播示例");
        var target = new PushTarget(); target.setPlatform("qq-onebot"); target.setType(PushTargetType.GROUP); target.setNum(1001L);
        var subscription = new PushMessage(); subscription.setHandler(WeeklyReportPushHandler.class.getName());
        target.setMessages(List.of(subscription)); user.setTargets(List.of(target));
        when(source.getAllUsers()).thenReturn(List.of(user));
        var revenue = new RevenueVisibilityService(new NovaStateStore(props));
        target.setRevenueVisible(visible);
        var sender = mock(NovaMessageSender.class);
        var gate = mock(PushGate.class);
        when(gate.allowedFor(any(PushTarget.class))).thenReturn(true);
        when(gate.blockedBy()).thenReturn(PushGate.Block.QUIET_HOURS);
        var shared = (WeeklyReportContentService) ReflectionTestUtils.getField(controller, "weeklyReports");
        var delivery = new WeeklyReportDeliveryService(source, shared, revenue, sender, gate,
                new WeeklyReportDeliveryStore(dir.resolve("weekly.json")),
                Clock.fixed(Instant.parse("2026-10-12T02:00:00Z"), ZoneOffset.UTC));
        delivery.check(false);
        var outgoing = ArgumentCaptor.forClass(Message.class);
        verify(sender).sendWeeklyReport(outgoing.capture());
        mvc = MockMvcBuilders.standaloneSetup(controller, new WeeklyReportPushController(delivery)).build();
        var json = mvc.perform(get("/config/api/streamers/bilibili/1/weekly-report")
                .param("format", "json").param("showRevenue", Boolean.toString(visible)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(JSON.parseObject(json).getString("summary").startsWith("主播示例 上周（"), json);
        assertEquals(JSON.parseObject(json).getString("summary"), outgoing.getValue().getContent().split("\n", 2)[1]);
        delivery.trial("bilibili", 1L, target);
        verify(sender).sendNow(outgoing.capture());
        assertEquals(JSON.parseObject(json).getString("summary"), outgoing.getValue().getContent().split("\n", 2)[1]);
        var targetJson = mvc.perform(get("/config/api/weekly-report/bilibili/1/preview")
                .param("pushPlatform", "qq-onebot").param("type", "1").param("num", "1001").param("format", "json"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(JSON.parseObject(json).getString("summary"), JSON.parseObject(targetJson).getString("summary"));
    }

    WeeklyReportDeliveryService targetReports() {
        return new WeeklyReportDeliveryService(source,
                (WeeklyReportContentService) ReflectionTestUtils.getField(controller, "weeklyReports"),
                new RevenueVisibilityService(new NovaStateStore(props)), mock(NovaMessageSender.class), mock(PushGate.class),
                new WeeklyReportDeliveryStore(dir.resolve("weekly.json")),
                Clock.fixed(Instant.parse("2026-10-12T02:00:00Z"), ZoneOffset.UTC));
    }

    @ParameterizedTest(name = "有图={0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("主播名中的全体占位符与CQ码发送到OneBot后仍是文字")
    void externalNameStaysText(boolean image) {
        archive(Instant.parse("2026-10-05T00:00:00Z"));
        var user = new PushUser(); user.setUid(1L); user.setPlatform("bilibili");
        user.setUname("{at=all}测试[CQ:at,qq=all]");
        when(source.getAllUsers()).thenReturn(List.of(user));
        if (!image) when(renderer.render(any(), any(), any())).thenReturn(null);
        var sender = mock(NovaMessageSender.class);
        var delivery = new WeeklyReportDeliveryService(source,
                (WeeklyReportContentService) ReflectionTestUtils.getField(controller, "weeklyReports"),
                new RevenueVisibilityService(new NovaStateStore(props)), sender, mock(PushGate.class),
                new WeeklyReportDeliveryStore(dir.resolve("weekly.json")),
                Clock.fixed(Instant.parse("2026-10-12T02:00:00Z"), ZoneOffset.UTC));
        var target = new PushTarget(); target.setPlatform("qq-onebot"); target.setType(PushTargetType.GROUP); target.setNum(1001L);
        delivery.trial("bilibili", 1L, target);
        var outgoing = ArgumentCaptor.forClass(Message.class);
        verify(sender).sendNow(outgoing.capture());
        var segments = new org.frostnova.nova.adapter.onebot.converter.OneBotMessageConverter()
                .convert(outgoing.getValue().getContent());
        var text = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            var segment = segments.getJSONObject(i);
            assertTrue(Set.of("text", "image").contains(segment.getString("type")), segment.toJSONString());
            if ("text".equals(segment.getString("type"))) text.append(segment.getJSONObject("data").getString("text"));
        }
        assertTrue(text.toString().contains("｛at=all｝测试[CQ:at,qq=all] "), text.toString());
    }
    @ParameterizedTest(name = "文字预览：{0}")
    @ValueSource(strings = {"empty", "renderer-failed", "renderer-missing"})
    @DisplayName("推送页无图预览展示摘要与文字，不展示原始JSON")
    void targetTextPreview(String mode) throws Exception {
        var user = new PushUser(); user.setUid(1L); user.setPlatform("bilibili"); user.setUname("主播示例");
        when(source.getAllUsers()).thenReturn(List.of(user));
        if (!mode.equals("empty")) archive(Instant.parse("2026-10-05T00:00:00Z"));
        if (mode.equals("renderer-failed")) when(renderer.render(any(), any(), any())).thenThrow(new IllegalStateException("sample render failure"));
        if (mode.equals("renderer-missing")) when(renderer.platform()).thenReturn("sample");
        mvc = MockMvcBuilders.standaloneSetup(new WeeklyReportPushController(targetReports())).build();
        String url = "/config/api/weekly-report/bilibili/1/preview";
        var page = mvc.perform(get(url).param("pushPlatform", "qq-onebot").param("type", "1").param("num", "1001"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/html"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var raw = mvc.perform(get(url).param("pushPlatform", "qq-onebot").param("type", "1").param("num", "1001").param("format", "json"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var json = JSON.parseObject(raw);
        assertTrue(page.contains(json.getString("summary")));
        assertTrue(org.springframework.web.util.HtmlUtils.htmlUnescape(page).contains(json.getString("text")));
        assertTrue(page.contains("<pre")); assertFalse(page.contains("\"data\"")); assertFalse(json.containsKey("data"));
    }
    @Test @DisplayName("推送页有图预览仍显示PNG")
    void targetImagePreview() throws Exception {
        archive(Instant.parse("2026-10-05T00:00:00Z"));
        mvc = MockMvcBuilders.standaloneSetup(new WeeklyReportPushController(targetReports())).build();
        mvc.perform(get("/config/api/weekly-report/bilibili/1/preview").param("pushPlatform", "qq-onebot").param("type", "1").param("num", "1001"))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"));
    }
    @Test @DisplayName("推送页不存在的主播预览返回共用404提示")
    void targetMissingPreview() throws Exception {
        var expected = controller.streamer("bilibili", 1L, "week", 1, 0).getBody().getString("message");
        mvc = MockMvcBuilders.standaloneSetup(new WeeklyReportPushController(targetReports())).build();
        var raw = mvc.perform(get("/config/api/weekly-report/bilibili/1/preview").param("pushPlatform", "qq-onebot").param("type", "1").param("num", "1001"))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertEquals(expected, JSON.parseObject(raw).getString("message"));
    }

}
