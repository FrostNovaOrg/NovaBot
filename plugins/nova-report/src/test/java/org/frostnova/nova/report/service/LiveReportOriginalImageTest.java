package org.frostnova.nova.report.service;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.command.BilibiliStreamerChoice;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.event.live.BilibiliLiveOffEvent;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.analytics.LiveDetail;
import org.frostnova.nova.core.command.CommandContext;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.event.live.common.LiveOnEvent;
import org.frostnova.nova.core.handler.NovaEventHandlerPushMessageInitializer;
import org.frostnova.nova.core.listener.NovaDefaultLiveOffEventListener;
import org.frostnova.nova.core.listener.NovaDefaultLiveOnEventListener;
import org.frostnova.nova.core.model.*;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.service.*;
import org.frostnova.nova.report.command.BilibiliLiveReportCommand;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.handler.BilibiliLiveReportPushHandler;
import org.frostnova.nova.report.painter.*;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LiveReportOriginalImageTest {
    @TempDir Path directory;
    private final List<AnnotationConfigApplicationContext> contexts = new ArrayList<>();
    private BilibiliLiveReportPushHandler handler;
    private BilibiliLiveReportReplayPainter replay;
    private NovaMessageSender sender;
    private PushTarget channel;
    private PushMessage message;
    private LiveDataService liveData;
    private byte[] original;
    private RevenueVisibilityService revenue;

    @BeforeEach void setup() throws Exception {
        var buffer = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", buffer);
        original = buffer.toByteArray();
        channel = new PushTarget();
        channel.setPlatform("qq-onebot"); channel.setType(PushTargetType.GROUP); channel.setNum(1001L);
        message = new PushMessage();
        message.setHandler(BilibiliLiveReportPushHandler.class.getName()); message.setTarget(channel);
        message.setParamsJsonObject(JSONObject.of("cover", false)); channel.setMessages(List.of(message));
        var painter = mock(BilibiliLiveReportPainter.class);
        when(painter.paint(anyString(), any(), any(), any()))
                .thenReturn(Optional.of(Base64.getEncoder().encodeToString(original)));
        sender = mock(NovaMessageSender.class);
        revenue = mock(RevenueVisibilityService.class);
        handler = new BilibiliLiveReportPushHandler(mock(BilibiliApiUtil.class), sender, painter, revenue);
        liveData = mock(LiveDataService.class);
        when(liveData.getLiveStartTime("bilibili", 1L)).thenReturn(Optional.of(100L));
        ReflectionTestUtils.setField(handler, "liveData", liveData);
        ReflectionTestUtils.setField(handler, "rendered", newCache());
        replay = mock(BilibiliLiveReportReplayPainter.class);
        when(replay.render(any(), any())).thenReturn(Optional.of(new byte[]{65, 66, 67}));
    }

    @AfterEach void close() { contexts.forEach(AnnotationConfigApplicationContext::close); }

    // 抓住隔天或重启后补发变成占位头像的故障。
    @Test void restartResendsExactlyThePushedBytes() {
        push();
        var sent = ArgumentCaptor.forClass(Message.class);
        verify(sender).send(sent.capture());
        String content = redrawer(newCache()).channelContent(detail(100), channel).orElseThrow();
        assertEquals(sent.getValue().getContent(), content);
        assertArrayEquals(original, Base64.getDecoder().decode(content.substring(14, content.length() - 1)));
        verifyNoInteractions(replay);
    }

    @Test void groupCommandUsesTheOriginalPushAfterRestart() {
        var properties = new NovaCoreProperties();
        properties.getDatasource().setJsonPath(directory.resolve("datasource.json").toString());
        properties.getLive().setLiveDataPath(directory.resolve("data.json").toString());
        var templates = new PushTemplateDefaults(properties);
        assertTrue(templates.save(handler, JSONObject.of("danmu_ranking", 10)).isEmpty());
        channel.setReportPalette("night");
        message.setParams("{\"cover\":true,\"fans_change\":true,\"card_follow\":false}");
        var handlers = mock(NovaEventHandlerService.class);
        when(handlers.getHandler(message.getHandler())).thenReturn(Optional.of(handler));
        assertTrue(new NovaEventHandlerPushMessageInitializer(handlers, templates).initialize(message));
        push();

        var user = new PushUser();
        user.setUid(1L);
        user.setPlatform("bilibili");
        user.setUname("主播示例");
        user.setTargets(List.of(channel));
        var source = mock(AbstractDataSource.class);
        when(source.getUsers("bilibili")).thenReturn(List.of(user));
        var details = new LiveDetailArchive(properties);
        details.store(detail(100));
        var reports = new LiveReportDeliveryService(details, source, null, null);
        var redrawer = redrawer(newCache());
        ReflectionTestUtils.setField(redrawer, "templates", new PushTemplateDefaults(properties));
        var sessions = new LiveSessionArchive(properties);
        var archiver = new LiveSessionDetailArchiver(liveData, mock(LiveRoomInfoHistory.class), details);
        var command = new BilibiliLiveReportCommand(source, new BilibiliStreamerChoice(liveData, sessions), liveData,
                mock(BilibiliLiveReportPainter.class), reports, archiver, redrawer);
        var context = new CommandContext("qq-onebot", PushTargetType.GROUP, 1001L, 1002L,
                "直播报告", List.of(), "直播报告");

        var sent = ArgumentCaptor.forClass(Message.class);
        verify(sender).send(sent.capture());
        assertEquals(sent.getValue().getContent(), command.execute(context).content());
        verifyNoInteractions(replay);
    }

    @Test void groupCommandAndConsoleResendUseTheFullReportAfterReconnect() {
        var properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(directory.resolve("data.json").toString());
        properties.getLive().setSaveLiveData(false);
        properties.getLive().setReconnectInterval(300);
        var data = new DefaultLiveDataService(properties);
        var details = new LiveDetailArchive(properties);
        var sessions = new LiveSessionArchive(properties);
        var roomHistory = new LiveRoomInfoHistory(new NovaStateStore(properties));
        var archiver = new LiveSessionDetailArchiver(data, roomHistory, details);
        var on = new NovaDefaultLiveOnEventListener(properties, data,
                new LiveSessionRecovery(data, sessions, roomHistory, archiver));
        var off = new NovaDefaultLiveOffEventListener(data, sessions, new LiveInterventionTracker(), roomHistory, archiver);

        channel.setMessages(List.of());
        var user = new PushUser();
        user.setUid(1L);
        user.setPlatform("bilibili");
        user.setUname("主播示例");
        user.setTargets(List.of(channel));
        var source = mock(AbstractDataSource.class);
        when(source.getUsers("bilibili")).thenReturn(List.of(user));
        when(source.getAllUsers()).thenReturn(List.of(user));
        var redrawer = spy(redrawer(newCache()));
        var beans = new StaticListableBeanFactory(Map.of("reportRedrawer", redrawer));
        var reports = new LiveReportDeliveryService(details, source, beans.getBeanProvider(LiveReportRedrawer.class), sender);
        var command = new BilibiliLiveReportCommand(source, new BilibiliStreamerChoice(data, sessions), data,
                mock(BilibiliLiveReportPainter.class), reports, archiver, redrawer);
        var context = new CommandContext("qq-onebot", PushTargetType.GROUP, 1001L, 1002L,
                "直播报告", List.of(), "直播报告");

        // 用存档的时长区分画手返回的图片。
        doAnswer(call -> {
            LiveDetail archived = call.getArgument(0);
            byte[] duration = Long.toString(archived.durationSeconds()).getBytes(StandardCharsets.US_ASCII);
            var painter = mock(BilibiliLiveReportReplayPainter.class);
            when(painter.render(any(), any())).thenReturn(Optional.of(duration));
            return painter;
        }).when(redrawer).replay(any());

        var streamer = new LiveStreamerInfo(1L, "主播示例", 9L);
        var start = Instant.parse("2025-01-01T12:00:00Z");
        on.onLiveOnEventSetLiveData(new LiveOnEvent("bilibili", streamer, start));
        var disconnected = start.plusSeconds(3600);
        off.onLiveOffEvent(new BilibiliLiveOffEvent(streamer, disconnected));
        String halfReport = command.execute(context).content();
        assertEquals("{image_base64=MzYwMA==}", halfReport, "第一次下播的报告应画出 3600 秒");
        assertEquals(halfReport, command.execute(context).content());
        verify(redrawer).replay(any());

        var resumed = new LiveOnEvent("bilibili", streamer, disconnected.plusSeconds(60));
        on.onLiveOnEventCheckReconnect(resumed);
        assertTrue(resumed.isReconnect());
        on.onLiveOnEventSetLiveData(resumed);
        off.onLiveOffEvent(new BilibiliLiveOffEvent(streamer, start.plusSeconds(7200)));
        LiveDetail finished = reports.latest("bilibili", 1L).orElseThrow();
        assertEquals(start.toEpochMilli(), finished.startTime());
        assertEquals(7200, finished.durationSeconds());

        String fullReport = command.execute(context).content();
        assertEquals("{image_base64=NzIwMA==}", fullReport, "续播结束后应回复 7200 秒的整场报告");
        reports.sendLatest("bilibili", 1L, channel, start.toEpochMilli());
        var sent = ArgumentCaptor.forClass(Message.class);
        verify(sender).sendNow(sent.capture());
        assertEquals(fullReport, sent.getValue().getContent());
    }

    @Test void aReplayFinishingAfterThePushKeepsTheOriginalAvailable() {
        var cache = newCache();
        ReflectionTestUtils.setField(handler, "rendered", cache);
        var redrawer = redrawer(cache);
        when(replay.render(any(), any())).thenAnswer(call -> {
            push();
            return Optional.of(new byte[]{65, 66, 67});
        });

        redrawer.channelContent(detail(100), channel).orElseThrow();

        var sent = ArgumentCaptor.forClass(Message.class);
        verify(sender).send(sent.capture());
        assertEquals(sent.getValue().getContent(), redrawer.channelContent(detail(100), channel).orElseThrow());
    }

    // 抓住每次开播都留一份旧图、越攒越多占满磁盘的故障。
    @Test void firstImageOfNewSessionDeletesOldSessionFiles() throws Exception {
        push();
        List<Path> old = storedFiles();
        assertEquals(1, old.size(), "推送时应留下成品图");
        message.getParamsJsonObject().put("cover", true);
        push();
        assertEquals(2, storedFiles().size(), "同一场可以留多套外观");
        when(liveData.getLiveStartTime("bilibili", 1L)).thenReturn(Optional.of(200L));
        push();
        assertFalse(Files.exists(old.get(0)), "新一场应删掉旧场次");
        assertEquals(1, storedFiles().size());
    }

    // 抓住改了通道版式，补发仍发出旧样子的故障；先确认旧外观确实已落盘。
    @Test void changedChannelLayoutReplaysInsteadOfUsingStoredOriginal() throws Exception {
        push();
        assertEquals(1, storedFiles().size(), "旧外观的原图应在磁盘上");
        message.getParamsJsonObject().put("cover", true);
        assertEquals("{image_base64=QUJD}", redrawer(newCache()).channelContent(detail(100), channel).orElseThrow());
        verify(replay).render(argThat(options -> options.isCover()), any());
        assertEquals(1, storedFiles().size(), "重画的图不落盘");
    }

    private void push() {
        handler.handle(new BilibiliLiveOffEvent(new LiveStreamerInfo(1L, "主播示例", 9L), Instant.ofEpochMilli(300)), message);
    }

    private List<Path> storedFiles() throws Exception {
        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile).toList();
        }
    }

    private LiveReportRenderCache newCache() {
        var properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(directory.resolve("data.json").toString());
        var context = new AnnotationConfigApplicationContext();
        contexts.add(context);
        context.registerBean(NovaCoreProperties.class, () -> properties);
        context.registerBean(LiveReportRenderCache.class);
        context.refresh();
        return context.getBean(LiveReportRenderCache.class);
    }

    private BilibiliLiveReportRedrawer redrawer(LiveReportRenderCache cache) {
        var redrawer = new BilibiliLiveReportRedrawer(mock(NovaCommonPainterFactory.class), mock(BilibiliApiUtil.class),
                mock(FontUtil.class), new NovaBilibiliProperties(), mock(LiveRoomInfoHistory.class), mock(ReportImageDiskCache.class)) {
            @Override protected BilibiliLiveReportReplayPainter replay(LiveDetail detail) { return replay; }
        };
        var templates = mock(PushTemplateDefaults.class);
        when(templates.paramsOf(any())).thenAnswer(call -> new JSONObject());
        ReflectionTestUtils.setField(redrawer, "templates", templates);
        ReflectionTestUtils.setField(redrawer, "handler", handler);
        ReflectionTestUtils.setField(redrawer, "revenue", revenue);
        ReflectionTestUtils.setField(redrawer, "rendered", cache);
        return redrawer;
    }

    private LiveDetail detail(long start) {
        return new LiveDetail(1, "bilibili", 1L, "主播示例", 9L, start, 300, 1,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of(), List.of(), List.of(), Map.of(), List.of());
    }
}
