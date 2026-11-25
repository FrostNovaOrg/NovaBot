package org.frostnova.nova.bilibili.service;

import org.frostnova.nova.bilibili.config.BilibiliRuntimeAppliers;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.health.BilibiliDisconnectDigest;
import org.frostnova.nova.bilibili.health.BilibiliRiskMetrics;
import org.frostnova.nova.bilibili.protocol.BilibiliPacketCodec;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplier;
import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplierContributor;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.client.WebSocketClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/**
 * 设置页保存数据包解压的两项上限时，正在收的那个包拿到的是同一组上限
 */
@DisplayName("数据包解压上限保存时不一新一旧")
class BilibiliDecodeLimitsSaveTest {
    private static final String PREFIX = "novabot.bilibili.live.";

    @Test
    @DisplayName("两项一起保存的那一刻收到的包，要么两项都按旧值、要么两项都按新值")
    void packetArrivingMidSaveSeesOneConsistentPair() throws Exception {
        // 抓的故障：连接器分两次、不拿锁地读这两项，保存刚写完字节上限、还没写层数时到的包，
        // 拿到的是新字节上限配旧层数
        NovaBilibiliProperties properties = spy(new NovaBilibiliProperties());
        NovaBilibiliProperties.Live live = spy(new NovaBilibiliProperties.Live());
        doReturn(live).when(properties).getLive();

        BilibiliLiveRoomConnector connector = connector(properties);
        assertEquals(new BilibiliPacketCodec.Limits(32 * 1024 * 1024, 3), connector.decodeLimits());

        // 保存写完第一项、还没写第二项时停下，等收包那一方去读
        CountDownLatch firstWritten = new CountDownLatch(1);
        AtomicReference<Thread> reader = new AtomicReference<>();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            firstWritten.countDown();
            waitUntilBlockedOrDone(reader);
            return null;
        }).when(live).setMaxDecompressedBytes(anyInt());

        RuntimeConfigurationApplier applier = applier(properties);
        Map<String, String> batch = new LinkedHashMap<>();
        batch.put(PREFIX + "max-decompressed-bytes", "1048576");
        batch.put(PREFIX + "max-decode-nesting-depth", "2");
        assertEquals(List.of(), applier.validateValues(batch));

        AtomicReference<List<String>> restart = new AtomicReference<>();
        Thread saver = new Thread(() -> restart.set(applier.applyAndTrack(batch)), "save");
        AtomicReference<BilibiliPacketCodec.Limits> seen = new AtomicReference<>();
        reader.set(new Thread(() -> seen.set(connector.decodeLimits()), "packet"));

        saver.start();
        assertTrue(firstWritten.await(30, TimeUnit.SECONDS), "保存应写到第一项");
        reader.get().start();
        reader.get().join(30_000);
        saver.join(30_000);

        assertEquals(List.of(), restart.get(), "两项都该当场生效");
        assertEquals(new BilibiliPacketCodec.Limits(1048576, 2), seen.get(),
                "保存中途到的包拿到了一新一旧的一组上限");
        assertEquals(new BilibiliPacketCodec.Limits(1048576, 2), connector.decodeLimits(), "保存之后的包按新值");
    }

    /**
     * 等收包那一方要么卡在锁上（读要等保存写完），要么已经读完走了（读没等保存）
     */
    private static void waitUntilBlockedOrDone(AtomicReference<Thread> reader) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            Thread thread = reader.get();
            Thread.State state = thread == null ? Thread.State.NEW : thread.getState();
            if (state == Thread.State.BLOCKED || state == Thread.State.TERMINATED) {
                return;
            }
            Thread.sleep(1);
        }
    }

    private static RuntimeConfigurationApplier applier(NovaBilibiliProperties properties) {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean(BilibiliRuntimeAppliers.class, () -> new BilibiliRuntimeAppliers(properties));
        context.refresh();
        return new RuntimeConfigurationApplier(new NovaCoreProperties(),
                context.getBeanProvider(RuntimeConfigurationApplierContributor.class), TimelineWriter.NONE);
    }

    private static BilibiliLiveRoomConnector connector(NovaBilibiliProperties properties) {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        return new BilibiliLiveRoomConnector(
                new LiveStreamerInfo(10001L, "测试主播", 10001L),
                mock(BilibiliApiUtil.class),
                mock(BilibiliEventParser.class),
                properties,
                mock(ApplicationEventPublisher.class),
                scheduler,
                mock(WebSocketClient.class),
                mock(BilibiliLiveStateGate.class),
                mock(BilibiliConnectGate.class),
                new BilibiliRiskMetrics(),
                mock(BilibiliDisconnectDigest.class),
                mock(LiveDataService.class),
                new AtomicBoolean(true));
    }
}
