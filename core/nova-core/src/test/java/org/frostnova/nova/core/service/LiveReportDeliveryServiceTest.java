package org.frostnova.nova.core.service;

import org.frostnova.nova.core.analytics.LiveDetail;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.*;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LiveReportDeliveryServiceTest {
    @TempDir Path dir;
    private LiveDetailArchive archive;
    private LiveReportDeliveryService service;
    private NovaMessageSender sender;
    private LiveReportRedrawer redrawer;
    private PushTarget channel;

    @BeforeEach void setup() {
        var properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        archive = new LiveDetailArchive(properties);
        var source = mock(AbstractDataSource.class);
        sender = mock(NovaMessageSender.class);
        redrawer = mock(LiveReportRedrawer.class);
        when(redrawer.platform()).thenReturn("bilibili");
        when(redrawer.channelContent(any(), any())).thenReturn(Optional.of("{image_base64=QUJD}"));
        @SuppressWarnings("unchecked") var redrawers = (ObjectProvider<LiveReportRedrawer>) mock(ObjectProvider.class);
        when(redrawers.orderedStream()).thenAnswer(call -> Stream.of(redrawer));
        channel = target(1001L);
        channel.setReportPalette("night");
        var other = target(2002L);
        var user = new PushUser(); user.setPlatform("bilibili"); user.setUid(1L);
        user.setTargets(List.of(channel, other));
        when(source.getAllUsers()).thenReturn(List.of(user));
        service = new LiveReportDeliveryService(archive, source, redrawers, sender);
    }

    static PushTarget target(long num) {
        var target = new PushTarget(); target.setPlatform("qq-onebot"); target.setType(PushTargetType.GROUP); target.setNum(num);
        return target;
    }
    static LiveDetail detail(String platform, long uid, long start, long end) {
        return new LiveDetail(1, platform, uid, "主播示例", 9L, start, end, (end-start)/1000,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of(), List.of(), List.of(), Map.of(), List.of());
    }

    @Test void latestCompletedSessionOnlyGoesToSelectedSavedChannel() {
        archive.store(detail("bilibili", 1, 100, 200));
        archive.store(detail("bilibili", 1, 300, 400));
        archive.store(detail("bilibili", 1, 500, 0));
        archive.store(detail("bilibili", 2, 700, 800));
        archive.store(detail("other", 1, 900, 1000));
        assertEquals(300, service.latest("bilibili", 1L).orElseThrow().startTime());
        service.sendLatest("bilibili", 1L, target(1001), 300);
        verify(redrawer).channelContent(argThat(d -> d.startTime() == 300), same(channel));
        var sent = ArgumentCaptor.forClass(Message.class);
        verify(sender).sendNow(sent.capture());
        assertEquals(1001L, sent.getValue().getNum());
        assertEquals("{image_base64=QUJD}", sent.getValue().getContent());
        assertSame(channel, sent.getValue().getChannel());
        verifyNoMoreInteractions(sender);
        assertEquals(300, service.latest("bilibili", 1L).orElseThrow().startTime());
    }

    @Test void missingSessionNeverRendersOrSends() {
        assertTrue(service.latest("bilibili", 1L).isEmpty());
        var error = assertThrows(IllegalArgumentException.class, () -> service.sendLatest("bilibili", 1L, target(1001), 0));
        assertEquals(LiveReportDeliveryService.NO_REPORT, error.getMessage());
        verifyNoInteractions(redrawer, sender);
    }

    @Test void confirmationCannotSilentlySwitchToNewerSession() {
        archive.store(detail("bilibili", 1, 300, 400));
        assertThrows(IllegalArgumentException.class, () -> service.sendLatest("bilibili", 1L, target(1001), 100));
        verifyNoInteractions(redrawer, sender);
    }

    @Test void missingChannelNeverSendsElsewhere() {
        archive.store(detail("bilibili", 1, 300, 400));
        assertThrows(IllegalArgumentException.class, () -> service.sendLatest("bilibili", 1L, target(9999), 300));
        verifyNoInteractions(redrawer, sender);
    }

    @Test void manualDeliveryDoesNotConsumeTheNextSession() {
        archive.store(detail("bilibili", 1, 100, 200));
        service.sendLatest("bilibili", 1L, target(1001), 100);
        archive.store(detail("bilibili", 1, 300, 400));
        service.sendLatest("bilibili", 1L, target(1001), 300);
        verify(redrawer).channelContent(argThat(d -> d.startTime() == 100), same(channel));
        verify(redrawer).channelContent(argThat(d -> d.startTime() == 300), same(channel));
        verify(sender, times(2)).sendNow(any());
        verifyNoMoreInteractions(sender);
    }
    @Test void manualSendBypassesQuietAndLeavesFirstPushTipForNormalDelivery() throws Exception {
        archive.store(detail("bilibili", 1, 100, 200));
        var http = mock(org.frostnova.nova.core.util.HttpUtil.class);
        var sent = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var normalAndTip = new java.util.concurrent.CountDownLatch(2);
        when(http.postJson(anyString(), anyMap(), anyMap())).thenAnswer(call -> {
            Map<String, Object> body = call.getArgument(2);
            sent.add(String.valueOf(body.get("content")));
            if (!"{image_base64=QUJD}".equals(body.get("content"))) normalAndTip.countDown();
            return com.alibaba.fastjson2.JSONObject.of("code", 0, "id", "sample");
        });
        var targets = mock(NovaSenderService.class);
        var target = new Sender(); target.setName("qq-onebot"); target.setUrl("https://example.invalid/send"); target.setDelay(0);
        when(targets.getSender("qq-onebot")).thenReturn(Optional.of(target));
        var gate = mock(org.frostnova.nova.core.sender.PushGate.class);
        when(gate.allowedFor(any(Message.class))).thenReturn(false);
        var properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("sender.json").toString());
        var tip = spy(new org.frostnova.nova.core.sender.FirstPushTipService(new NovaStateStore(properties)));
        doReturn("首推提示").when(tip).text(any());
        @SuppressWarnings("unchecked") var resolvers = (ObjectProvider<org.frostnova.nova.core.sender.AtAllPermissionResolver>) mock(ObjectProvider.class);
        var actual = new NovaMessageSender(http, targets,
                mock(org.frostnova.nova.core.health.PushActivityRecorder.class), gate,
                org.frostnova.nova.core.timeline.TimelineWriter.NONE,
                mock(AtAllQuotaService.class), resolvers, tip);
        try {
            var source = (AbstractDataSource) org.springframework.test.util.ReflectionTestUtils.getField(service, "source");
            @SuppressWarnings("unchecked") var redrawers = (ObjectProvider<LiveReportRedrawer>) org.springframework.test.util.ReflectionTestUtils.getField(service, "redrawers");
            new LiveReportDeliveryService(archive, source, redrawers, actual).sendLatest("bilibili", 1L, target(1001), 100);
            assertEquals(List.of("{image_base64=QUJD}"), sent);
            assertEquals(0, actual.getPendingCount());
            verifyNoInteractions(gate, tip);
            when(gate.allowedFor(any(Message.class))).thenReturn(true);
            when(gate.alertsAllowed()).thenReturn(true);
            actual.send(Message.create("qq-onebot", PushTargetType.GROUP, 1001L, "下一场下播报告").get(0));
            assertTrue(normalAndTip.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(List.of("{image_base64=QUJD}", "下一场下播报告", "首推提示"), sent);
            verify(tip).claim("qq-onebot", PushTargetType.GROUP, 1001L);
        } finally { actual.shutdown(); }
    }

}
