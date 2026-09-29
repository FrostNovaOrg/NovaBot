package org.frostnova.nova.report.painter;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.command.BilibiliStreamerChoice;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.event.dynamic.BilibiliDynamicUpdateEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliLiveOffEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliOfflineRewardDigestEvent;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.frostnova.nova.bilibili.model.Dynamic;
import org.frostnova.nova.bilibili.model.Room;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.command.CommandContext;
import org.frostnova.nova.core.command.CommandReply;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.PushMessage;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.service.AtSubscriptionService;
import org.frostnova.nova.core.service.DefaultLiveDataService;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.LiveRoomInfoHistory;
import org.frostnova.nova.core.service.LiveSessionArchive;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.service.RevenueVisibilityService;
import org.frostnova.nova.core.service.StreamerNames;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.report.command.BilibiliLiveReportCommand;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.handler.BilibiliDynamicPushHandler;
import org.frostnova.nova.report.handler.BilibiliLiveReportPushHandler;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;

import java.awt.Color;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 起动时没查到主播昵称、推送时也查不到：直播报告、下播报告与动态推送里的主播名
 * <p>
 * 事件与推送配置里的主播名来自内存，起动补全没查到时是空的，推送前向平台再查一次也落空。
 * 此时下播报告的页头与文字版写着「null」或「未知主播」，「直播报告」命令回一串数字；
 * 控制台却显示得出最近一场的昵称。
 */
@DisplayName("直播报告、下播报告与动态推送：补全没查到昵称时退回最近一场")
class StreamerNameFallbackReportTest {
    private static final String PLATFORM = "bilibili";

    private static final long UID = 10001L;

    private static final long ROOM_ID = 20002L;

    private static final long FRIEND = 20001L;

    @TempDir
    Path dir;

    private NovaCoreProperties properties;

    private StreamerNames names;

    private BilibiliApiUtil api;

    private NovaMessageSender sender;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void setUp() throws IOException {
        properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        names = new StreamerNames(new LiveSessionArchive(properties));

        JSONObject line = new JSONObject();
        line.put("platform", PLATFORM);
        line.put("uid", UID);
        line.put("uname", "最近的昵称");
        line.put("startTime", 1_700_000_000_000L);
        line.put("endTime", 1_700_003_600_000L);
        Files.writeString(dir.resolve("sessions.jsonl"), line.toJSONString() + System.lineSeparator(),
                StandardCharsets.UTF_8);

        api = mock(BilibiliApiUtil.class);
        when(api.getUpInfoByUid(anyLong())).thenThrow(new RuntimeException("接口不可用"));
        sender = mock(NovaMessageSender.class);
    }

    @Test
    @DisplayName("「直播报告」命令、主播不在播：回话写最近一场的昵称")
    void reportCommandSaysNotLiveWithArchivedName() {
        CommandReply reply = reportCommand(false, mock(BilibiliLiveReportPainter.class)).execute(context());

        assertEquals("最近的昵称 现在没在直播", reply.content());
    }

    @Test
    @DisplayName("「直播报告」命令、主播在播：交给画手的主播名是最近一场的昵称")
    void reportCommandPaintsWithArchivedName() {
        BilibiliLiveReportPainter painter = mock(BilibiliLiveReportPainter.class);
        when(painter.paint(anyString(), any(), any())).thenReturn(Optional.of("QUJD"));

        reportCommand(true, painter).execute(context());

        ArgumentCaptor<LiveStreamerInfo> source = ArgumentCaptor.forClass(LiveStreamerInfo.class);
        verify(painter).paint(anyString(), source.capture(), any());
        assertEquals("最近的昵称", source.getValue().getUname());
        assertEquals(UID, source.getValue().getUid());
    }

    @Test
    @DisplayName("下播报告文字版：首行写最近一场的昵称")
    void textReportStartsWithArchivedName() {
        String text = reportPainter(api).textReport(PLATFORM, source(null), new BilibiliLiveReportOptions());

        assertTrue(text.startsWith("最近的昵称 本场直播数据"), text);
    }

    @Test
    @DisplayName("下播报告的图：页头写最近一场的昵称")
    void reportImageHeaderShowsArchivedName() {
        BufferedImage placeholder = new BufferedImage(64, 36, BufferedImage.TYPE_INT_ARGB);
        when(api.getBilibiliImage(anyString())).thenReturn(Optional.of(placeholder));
        Room room = new Room();
        room.setCover("https://pic.example/cover.jpg");
        when(api.getLiveInfoByRoomId(anyLong())).thenReturn(room);
        when(api.getGuardList(anyLong(), anyLong())).thenReturn(Optional.of(List.of()));

        List<CommonPainter> made = new ArrayList<>();
        NovaCommonPainterFactory factory = spy(factory());
        doAnswer(invocation -> {
            CommonPainter painter = spy((CommonPainter) invocation.callRealMethod());
            made.add(painter);
            return painter;
        }).when(factory).create(anyInt(), anyInt(), anyBoolean());

        BilibiliLiveReportPainter reportPainter = new BilibiliLiveReportPainter(factory, api,
                new DefaultLiveDataService(new NovaCoreProperties()), fontUtil(), new NovaBilibiliProperties(),
                new LiveRoomInfoHistory(new NovaStateStore(new NovaCoreProperties())), null, names);

        assertTrue(reportPainter.paint(PLATFORM, source(""), new BilibiliLiveReportOptions()).isPresent());
        assertEquals(1, made.size());
        verify(made.get(0), atLeastOnce()).drawSection(eq("最近的昵称"), any(Color.class), any(Point.class));
    }

    @Test
    @DisplayName("下播报告推送：{uname} 写最近一场的昵称")
    void reportPushNamesTheStreamer() {
        BilibiliLiveReportPainter painter = mock(BilibiliLiveReportPainter.class);
        when(painter.paint(anyString(), any(), any())).thenReturn(Optional.of("QUJD"));
        RevenueVisibilityService revenueVisibility = mock(RevenueVisibilityService.class);
        BilibiliLiveReportPushHandler handler =
                new BilibiliLiveReportPushHandler(api, sender, painter, revenueVisibility, names);

        JSONObject params = handler.getDefaultParams();
        params.put("message", "{uname} 的下播报告");
        handler.handle(new BilibiliLiveOffEvent(source("")), pushMessage(params));

        assertEquals("最近的昵称 的下播报告", sentContent());
    }

    @Test
    @DisplayName("打赏播报文字版：写最近一场的昵称")
    void rewardDigestTextNamesTheStreamer() {
        BilibiliOfflineRewardDigestEvent.Contribution person = new BilibiliOfflineRewardDigestEvent.Contribution();
        person.setUid(50002L);
        person.setUname("观众");
        person.setGifts(List.of(new BilibiliOfflineRewardDigestEvent.GiftLine("小花花", 1, 10L)));
        BilibiliOfflineRewardDigestEvent event =
                new BilibiliOfflineRewardDigestEvent(source(""), List.of(person));

        String text = new BilibiliOfflineRewardDigestPainter(factory(), api, new NovaBilibiliProperties(), names)
                .textDigest(event, true);

        assertTrue(text.contains("，最近的昵称 都收到啦"), text);
    }

    @Test
    @DisplayName("动态被屏蔽词挡下：日志页写最近一场的昵称，不写 uid")
    void blockedDynamicTimelineNamesTheStreamer() {
        NovaBilibiliProperties bilibiliProperties = new NovaBilibiliProperties();
        bilibiliProperties.getDynamic().getBlockWords().add("抽奖");
        List<TimelineEvent> recorded = new ArrayList<>();
        BilibiliDynamicPushHandler handler = new BilibiliDynamicPushHandler(api, mock(BilibiliDynamicPainter.class),
                sender, mock(AtSubscriptionService.class), mock(LiveDataService.class), bilibiliProperties,
                recorded::add, names);

        JSONObject desc = new JSONObject();
        desc.put("text", "今晚抽奖");
        JSONObject moduleDynamic = new JSONObject();
        moduleDynamic.put("desc", desc);
        JSONObject modules = new JSONObject();
        modules.put("module_dynamic", moduleDynamic);
        Dynamic dynamic = new Dynamic();
        dynamic.setId("1");
        dynamic.setType("DYNAMIC_TYPE_DRAW");
        dynamic.setModules(modules);

        handler.handle(new BilibiliDynamicUpdateEvent(source(""), dynamic, "发布了动态", "https://t.example/1"),
                pushMessage(handler.getDefaultParams()));

        assertEquals(1, recorded.size(), recorded.toString());
        assertEquals("最近的昵称", recorded.get(0).streamer());
        assertTrue(recorded.get(0).text().contains("最近的昵称的这条动态"), recorded.get(0).text());
    }

    private BilibiliLiveReportCommand reportCommand(boolean living, BilibiliLiveReportPainter painter) {
        PushTarget target = new PushTarget();
        target.setPlatform("qq-onebot");
        target.setType(PushTargetType.FRIEND);
        target.setNum(FRIEND);
        target.setMessages(new ArrayList<>());

        PushUser user = new PushUser();
        user.setUid(UID);
        user.setUname("");
        user.setRoomId(ROOM_ID);
        user.setPlatform(PLATFORM);
        user.setTargets(List.of(target));

        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getUsers(PLATFORM)).thenReturn(List.of(user));

        LiveDataService liveDataService = mock(LiveDataService.class);
        when(liveDataService.getLiveStatus(anyString(), anyLong())).thenReturn(Optional.of(living));
        when(liveDataService.getLiveEndTime(anyString(), anyLong())).thenReturn(Optional.empty());

        RevenueVisibilityService revenueVisibility = mock(RevenueVisibilityService.class);
        when(revenueVisibility.isVisible(anyString(), any(), anyLong())).thenReturn(true);

        return new BilibiliLiveReportCommand(dataSource,
                new BilibiliStreamerChoice(liveDataService, new LiveSessionArchive(properties)),
                liveDataService, painter, revenueVisibility);
    }

    private static CommandContext context() {
        return new CommandContext("qq-onebot", PushTargetType.FRIEND, FRIEND, 40001L,
                "直播报告", List.of(), "直播报告");
    }

    private BilibiliLiveReportPainter reportPainter(BilibiliApiUtil api) {
        return new BilibiliLiveReportPainter(factory(), api, new DefaultLiveDataService(new NovaCoreProperties()),
                fontUtil(), new NovaBilibiliProperties(),
                new LiveRoomInfoHistory(new NovaStateStore(new NovaCoreProperties())), null, names);
    }

    private static FontUtil fontUtil() {
        NovaCoreProperties coreProperties = new NovaCoreProperties();
        coreProperties.getPaint().getFonts().add("内置");
        FontUtil fontUtil = new FontUtil(new DefaultResourceLoader(), coreProperties);
        fontUtil.init();
        return fontUtil;
    }

    private static NovaCommonPainterFactory factory() {
        NovaCoreProperties coreProperties = new NovaCoreProperties();
        coreProperties.getPaint().getFonts().add("内置");
        Properties buildInfo = new Properties();
        buildInfo.setProperty("version", "4.0.0");
        buildInfo.setProperty("artifact", "nova-core");
        buildInfo.setProperty("name", "NovaBot");
        return new NovaCommonPainterFactory(new BuildProperties(buildInfo), coreProperties, fontUtil());
    }

    private static LiveStreamerInfo source(String uname) {
        return new LiveStreamerInfo(UID, uname, ROOM_ID, "https://pic.example/face.jpg");
    }

    private static PushMessage pushMessage(JSONObject params) {
        PushTarget target = new PushTarget();
        target.setPlatform("qq-onebot");
        target.setType(PushTargetType.GROUP);
        target.setNum(30003L);

        PushMessage message = new PushMessage();
        message.setTarget(target);
        message.setParamsJsonObject(params);
        return message;
    }

    private String sentContent() {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(sender).send(captor.capture());
        return captor.getValue().getContent();
    }
}
