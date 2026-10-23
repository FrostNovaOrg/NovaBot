package org.frostnova.nova.report.service;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.event.live.BilibiliLiveOffEvent;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.analytics.LiveDetail;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.*;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.service.*;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.handler.BilibiliLiveReportPushHandler;
import org.frostnova.nova.report.painter.*;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
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

    private void push() { handler.handle(new BilibiliLiveOffEvent(new LiveStreamerInfo(1L, "主播示例", 9L)), message); }

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
