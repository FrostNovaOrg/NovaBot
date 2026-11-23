package org.frostnova.nova.report.command;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.event.live.BilibiliLiveOffEvent;
import org.frostnova.nova.bilibili.command.BilibiliStreamerChoice;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.report.handler.BilibiliLiveReportPushHandler;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.report.painter.BilibiliLiveReportPainter;
import org.frostnova.nova.report.painter.BilibiliLiveReportReplayPainter;
import org.frostnova.nova.report.painter.ReportPalette;
import org.frostnova.nova.report.service.BilibiliLiveReportRedrawer;
import org.frostnova.nova.core.command.CommandContext;
import org.frostnova.nova.core.command.CommandReply;
import org.frostnova.nova.core.analytics.LiveDetail;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.datasource.DataSourceService;
import org.frostnova.nova.core.datasource.DataSourceServiceRegistry;
import org.frostnova.nova.core.enums.LiveEndReason;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.listener.NovaDefaultLiveOffEventListener;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.PushMessage;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.LiveDetailArchive;
import org.frostnova.nova.core.service.LiveInterventionTracker;
import org.frostnova.nova.core.service.LiveReportDeliveryService;
import org.frostnova.nova.core.service.LiveRoomInfoHistory;
import org.frostnova.nova.core.service.LiveSessionArchive;
import org.frostnova.nova.core.service.LiveSessionDetailArchiver;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.frostnova.nova.core.service.RevenueVisibilityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「直播报告」命令的主播选择、直播中与下播后的答复及版式。
 */
@DisplayName("直播报告命令")
class BilibiliLiveReportCommandTest {
    private static final String PLATFORM = "qq-onebot";

    private static final Long FRIEND = 20001L;

    private static final Long SENDER = 40001L;

    private static final List<String> NAMES = List.of("主播甲", "主播乙");

    @TempDir
    Path dataDir;

    @Test
    @DisplayName("私聊没有报告推送、主播在播：出图，画手拿到默认版式")
    void paintsDefaultLayoutWhenSessionHasNoReportPush() {
        Fixture fixture = new Fixture(List.of(streamer(1, null)), Set.of(1));

        CommandReply reply = fixture.command.execute(context());

        assertEquals("{image_base64=QUJD}", reply.content());
        BilibiliLiveReportOptions options = fixture.capturedOptions();
        assertTrue(options.isCover());
        assertTrue(options.isCards());
        assertTrue(options.isDanmuCloud());
        assertEquals(5, options.getDanmuRanking());
        assertEquals(0, options.getBoxRanking());
        assertTrue(options.isShowRevenue(), "私聊默认展示金额");
    }

    @Test
    @DisplayName("没有已结束的直播时说明没有报告")
    void explainsWhenThereIsNoArchivedReport() {
        Fixture fixture = new Fixture(List.of(streamer(1, null)), Set.of());

        CommandReply reply = fixture.command.execute(context());

        assertTrue(reply.content().contains("主播甲 还没有已结束的直播"), reply.content());
        assertTrue(reply.content().contains("暂时没有直播报告"), reply.content());
        verify(fixture.painter, never()).paint(anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("下播后回复最新的整场报告")
    void repliesWithLatestFinishedReport() {
        PushUser user = streamer(1, null);
        PushTarget target = user.getTargets().get(0);
        target.setType(PushTargetType.GROUP);
        target.setReportPalette("night");
        target.setRevenueVisible(false);
        Fixture fixture = new Fixture(List.of(user), Set.of());
        fixture.details.store(detail(100, 200));
        fixture.details.store(detail(300, 600));

        CommandReply reply = fixture.command.execute(new CommandContext(PLATFORM, PushTargetType.GROUP, FRIEND, SENDER,
                "直播报告", List.of(), "直播报告"));

        assertEquals("{image_base64=REVG}", reply.content());
        assertEquals(300, fixture.replayedDetail.startTime());
        verify(fixture.replay).render(argThat(options -> !options.isShowRevenue()), same(ReportPalette.NIGHT));
        verify(fixture.painter, never()).paint(anyString(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("直播中与下播后都使用默认模板和已保存的版式")
    void usesDefaultTemplateAndDisabledReportLayout(boolean savedLayout) {
        PushUser streamer = streamer(1, savedLayout ? JSONObject.of("cover", false) : null);
        if (savedLayout) {
            PushMessage report = streamer.getTargets().get(0).getMessages().get(0);
            report.setHandler("org.frostnova.nova.bilibili.handler.BilibiliLiveReportPushHandler");
            report.setParams(report.getParamsJsonObject().toJSONString());
            report.setParamsJsonObject(null);
            report.setEnabled(false);
        }
        Fixture fixture = new Fixture(List.of(streamer), Set.of(1));
        assertTrue(fixture.templates.save(fixture.handler, JSONObject.of("danmu_ranking", 10)).isEmpty());

        assertEquals("{image_base64=QUJD}", fixture.command.execute(context()).content());
        BilibiliLiveReportOptions live = fixture.capturedOptions();
        assertEquals(10, live.getDanmuRanking());
        assertEquals(!savedLayout, live.isCover());

        when(fixture.liveData.getLiveStatus("bilibili", 10001L)).thenReturn(Optional.of(false));
        fixture.details.store(detail(300, 600));
        assertEquals("{image_base64=REVG}", fixture.command.execute(context()).content());
        var archivedOptions = ArgumentCaptor.forClass(BilibiliLiveReportOptions.class);
        verify(fixture.replay).render(archivedOptions.capture(), any());
        assertThat(archivedOptions.getValue()).usingRecursiveComparison().isEqualTo(live);
    }

    @Test
    @DisplayName("整场报告画不出来时发文字版")
    void repliesWithFinishedTextReportWhenImageFails() {
        Fixture fixture = new Fixture(List.of(streamer(1, null)), Set.of());
        fixture.details.store(detail(300, 600));
        when(fixture.replay.render(any(), any())).thenReturn(Optional.empty());
        when(fixture.replay.textReport(eq("bilibili"), any(), any())).thenReturn("主播甲的直播报告\n时长 1 小时");

        String reply = fixture.command.execute(context()).content();

        assertTrue(reply.contains("主播甲的直播报告"), reply);
        assertTrue(reply.contains("时长 1 小时"), reply);
    }

    @Test
    @DisplayName("刚下播而明细还没写好时，提醒稍后再查")
    void doesNotSendAnOlderReportWhileClosingTheCurrentSession() {
        Fixture fixture = new Fixture(List.of(streamer(1, null)), Set.of());
        fixture.details.store(detail(100, 200));
        when(fixture.liveData.getLiveStartTime("bilibili", 10001L)).thenReturn(Optional.of(300L));
        var intervention = mock(LiveInterventionTracker.class);
        when(intervention.endReason(anyString(), anyLong(), any())).thenReturn(LiveEndReason.NORMAL);
        List<String> duringArchive = new ArrayList<>();
        doAnswer(call -> {
            duringArchive.add(fixture.command.execute(context()).content());
            return call.callRealMethod();
        }).when(fixture.archiver).allSeries("bilibili", 10001L);
        var listener = new NovaDefaultLiveOffEventListener(fixture.liveData, fixture.sessions,
                intervention, fixture.roomInfo, fixture.archiver);

        listener.onLiveOffEvent(new BilibiliLiveOffEvent(new LiveStreamerInfo(10001L, "主播甲", 9L),
                Instant.ofEpochMilli(600)));

        assertEquals(1, duringArchive.size());
        assertTrue(duringArchive.get(0).contains("刚下播，直播报告还在生成"), duringArchive.get(0));
        assertEquals("{image_base64=REVG}", fixture.command.execute(context()).content());
        assertEquals(300, fixture.replayedDetail.startTime());
    }

    private static LiveDetail detail(long start, long end) {
        return new LiveDetail(LiveDetail.VERSION, "bilibili", 10001L, "主播甲", 9L, start, end, (end - start) / 1000,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of(), List.of(), List.of(), Map.of(), List.of());
    }

    @Test
    @DisplayName("多位只一位在播、不带名：content 以「本次用的是：」起、以图收")
    void picksTheOnlyLivingStreamerWithoutPrefixingTheImage() {
        Fixture fixture = new Fixture(List.of(streamer(1, null), streamer(2, null)), Set.of(2));

        CommandReply reply = fixture.command.execute(context());

        assertTrue(reply.content().startsWith("本次用的是："), reply.content());
        assertTrue(reply.content().endsWith("{image_base64=QUJD}"), reply.content());
        assertTrue(reply.content().contains("\n"), reply.content());
        assertEquals(10002L, fixture.capturedSource().getUid());
    }

    @Test
    @DisplayName("带主播名时应出点名的那位")
    void honorsNamedStreamer() {
        Fixture fixture = new Fixture(List.of(streamer(1, null), streamer(2, null)), Set.of(1, 2));

        CommandReply reply = fixture.command.execute(context("主播乙"));

        assertEquals("{image_base64=QUJD}", reply.content());
        assertEquals(10002L, fixture.capturedSource().getUid());
    }

    @Test
    @DisplayName("该主播有推到本会话的报告推送：画手拿到那条推送的版式")
    void usesPushLayoutWhenThisSessionHasAReportHandler() {
        JSONObject params = new JSONObject();
        params.put("cover", false);
        params.put("danmu_ranking", 10);
        Fixture fixture = new Fixture(List.of(streamer(1, params)), Set.of(1));

        fixture.command.execute(context());

        BilibiliLiveReportOptions options = fixture.capturedOptions();
        assertFalse(options.isCover());
        assertEquals(10, options.getDanmuRanking());
        assertTrue(options.isCards(), "未写的项仍是默认");
    }

    @Test
    @DisplayName("推送里写的是处理器旧类名: 版式照样用那一条, 不悄悄改回默认")
    void usesPushLayoutWhenHandlerIsWrittenUnderItsLegacyName() {
        JSONObject params = new JSONObject();
        params.put("cover", false);
        params.put("danmu_ranking", 10);
        Fixture fixture = new Fixture(List.of(legacyStreamer(1, params)), Set.of(1));

        fixture.command.execute(context());

        BilibiliLiveReportOptions options = fixture.capturedOptions();
        assertFalse(options.isCover(), "老配置里的版式没被认出来, 报告悄悄换回默认版式");
        assertEquals(10, options.getDanmuRanking());
    }

    private static CommandContext context(String... args) {
        return new CommandContext(PLATFORM, PushTargetType.FRIEND, FRIEND, SENDER,
                "直播报告", List.of(args), args.length == 0 ? "直播报告" : "直播报告 " + String.join(" ", args));
    }

    /**
     * 搬包之前那份配置：{@code handler} 里写的是旧全类名，实例由核心按别名回落解出来。
     * 老使用者机器上的 datasource.json 正是这个样子
     */
    private static PushUser legacyStreamer(int index, JSONObject reportParams) {
        PushUser user = streamer(index, reportParams);
        PushMessage message = user.getTargets().get(0).getMessages().get(0);
        message.setHandler("org.frostnova.nova.bilibili.handler.BilibiliLiveReportPushHandler");
        message.setHandlerInstance(new BilibiliLiveReportPushHandler(mock(BilibiliApiUtil.class),
                mock(NovaMessageSender.class), mock(BilibiliLiveReportPainter.class),
                mock(RevenueVisibilityService.class)));
        return user;
    }

    private static PushUser streamer(int index, JSONObject reportParams) {
        PushUser user = new PushUser();
        user.setUid(10000L + index);
        user.setUname(NAMES.get(index - 1));
        user.setPlatform("bilibili");
        user.setEnabled(true);

        PushTarget target = new PushTarget();
        target.setPlatform(PLATFORM);
        target.setType(PushTargetType.FRIEND);
        target.setNum(FRIEND);
        target.setEnabled(true);
        if (reportParams != null) {
            PushMessage message = new PushMessage();
            message.setHandler(BilibiliLiveReportPushHandler.class.getName());
            message.setEnabled(true);
            message.setParamsJsonObject(reportParams);
            target.setMessages(new ArrayList<>(List.of(message)));
        } else {
            target.setMessages(new ArrayList<>());
        }
        user.setTargets(new ArrayList<>(List.of(target)));
        target.setUser(user);
        return user;
    }

    /**
     * 一次用例用的零件：主播名单与在播状态按用例不同，共用一份会把上一例的盘面带到下一例。
     */
    private class Fixture {
        private final BilibiliLiveReportPainter painter;

        private final BilibiliLiveReportCommand command;

        private final LiveDataService liveData = mock(LiveDataService.class);
        private final LiveRoomInfoHistory roomInfo = mock(LiveRoomInfoHistory.class);
        private final LiveSessionArchive sessions;
        private final LiveDetailArchive details;
        private final LiveSessionDetailArchiver archiver;
        private final PushTemplateDefaults templates;
        private final BilibiliLiveReportPushHandler handler;
        private final BilibiliLiveReportReplayPainter replay = mock(BilibiliLiveReportReplayPainter.class);
        private LiveDetail replayedDetail;

        private final ArgumentCaptor<LiveStreamerInfo> source =
                ArgumentCaptor.forClass(LiveStreamerInfo.class);

        private final ArgumentCaptor<BilibiliLiveReportOptions> options =
                ArgumentCaptor.forClass(BilibiliLiveReportOptions.class);

        private boolean captured;

        Fixture(List<PushUser> configuredUsers, Set<Integer> living) {
            var registry = mock(DataSourceServiceRegistry.class);
            when(registry.getDataSourceService("bilibili")).thenReturn(Optional.of(mock(DataSourceService.class)));
            var dataSource = new AbstractDataSource(event -> { }, registry, null) {
                @Override public void load() { add(configuredUsers); }
            };
            dataSource.load();
            when(liveData.getLiveStatus(anyString(), anyLong())).thenAnswer(invocation ->
                    Optional.of(living.contains((int) ((Long) invocation.getArgument(1) - 10000L))));

            painter = mock(BilibiliLiveReportPainter.class);
            when(painter.paint(anyString(), any(), any(), any())).thenReturn(Optional.of("QUJD"));

            NovaCoreProperties properties = new NovaCoreProperties();
            properties.getLive().setLiveDataPath(dataDir.resolve("data.json").toString());
            properties.getDatasource().setJsonPath(dataDir.resolve("datasource.json").toString());
            var revenue = new RevenueVisibilityService(new NovaStateStore(properties));
            templates = new PushTemplateDefaults(properties);
            handler = new BilibiliLiveReportPushHandler(mock(BilibiliApiUtil.class), mock(NovaMessageSender.class), painter, revenue);
            sessions = new LiveSessionArchive(properties);
            details = new LiveDetailArchive(properties);
            archiver = spy(new LiveSessionDetailArchiver(liveData, roomInfo, details));
            var redrawer = new BilibiliLiveReportRedrawer(null, null, null, new NovaBilibiliProperties(), roomInfo, null) {
                @Override protected BilibiliLiveReportReplayPainter replay(LiveDetail detail) {
                    replayedDetail = detail;
                    return replay;
                }
            };
            ReflectionTestUtils.setField(redrawer, "templates", templates);
            ReflectionTestUtils.setField(redrawer, "handler", handler);
            ReflectionTestUtils.setField(redrawer, "revenue", revenue);
            when(replay.render(any(), any())).thenReturn(Optional.of(new byte[]{68, 69, 70}));
            command = new BilibiliLiveReportCommand(dataSource, new BilibiliStreamerChoice(liveData, sessions),
                    liveData, painter, new LiveReportDeliveryService(details, dataSource, null, null), archiver, redrawer);
        }

        private void capture() {
            if (!captured) {
                verify(painter).paint(anyString(), source.capture(), options.capture(), any());
                captured = true;
            }
        }

        LiveStreamerInfo capturedSource() {
            capture();
            return source.getValue();
        }

        BilibiliLiveReportOptions capturedOptions() {
            capture();
            return options.getValue();
        }
    }
}
