package org.frostnova.nova.adapter.onebot.alert;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.adapter.onebot.config.OneBotAdapterPluginProperties;
import org.frostnova.nova.adapter.onebot.health.OneBotConnectionState;
import org.frostnova.nova.core.alert.AlertChannel;
import org.frostnova.nova.core.alert.AlertService;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.health.PushActivityRecorder;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.Sender;
import org.frostnova.nova.core.sender.AtAllPermissionResolver;
import org.frostnova.nova.core.sender.FirstPushTipService;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.sender.PushGate;
import org.frostnova.nova.core.service.AtAllQuotaService;
import org.frostnova.nova.core.service.NovaSenderService;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.timeline.TimelineEventType;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.frostnova.nova.core.util.HttpUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueuedPushSwitchTest {
    @Test
    @DisplayName("关闭推送总开关后，已排队的两条推送与一条告警都不发出")
    void queuedMessagesAreDroppedWhenSwitchCloses() throws Exception {
        // 抓关掉总开关后，积压的推送和告警仍往群里发，且日志页还记成已报出。
        try (Rig rig = new Rig()) {
            List<Message> queued = rig.enqueueAndCloseSwitch();
            rig.releaseAndAwait(queued);
            assertEquals(0, rig.deliveries.get(), "总开关关闭后已排队消息仍发出");
            assertEquals(2, rig.events.stream().filter(e -> e.type() == TimelineEventType.PUSH_PAUSED
                    && e.text().contains("全局推送开关已关闭") && e.text().contains("丢弃")).count());
            assertEquals(1, rig.events.stream().filter(e -> e.type() == TimelineEventType.ALERT_FAILED
                    && String.valueOf(e.detail().get("reason")).contains("全局推送开关已关闭")).count());
            for (Message message : queued) {
                assertNull(message.getId());
                assertTrue(message.getFailureReason().contains("全局推送开关已关闭"));
                AtomicInteger lateFailure = new AtomicInteger();
                message.addOnFailureCallback(lateFailure::incrementAndGet);
                assertEquals(1, lateFailure.get(), "丢弃应定为没发出，后登记的回调也要知道");
            }
        }
    }

    @Test
    @DisplayName("重新打开推送总开关并经过重发间隔，被丢弃的告警不会补发")
    void blockedQueuedAlertIsNotRetriedAfterReopening() throws Exception {
        // 抓出队被开关拦下的告警被当作普通失败，开关打开后又补发进群。
        try (Rig rig = new Rig()) {
            List<Message> queued = rig.enqueueAndCloseSwitch();
            rig.releaseAndAwait(queued);
            rig.core.getPush().setEnabled(true);
            rig.alerts.onApplicationReadyEvent();
            ScheduledExecutorService scheduler = (ScheduledExecutorService) field(rig.alerts, "scheduler");
            CountDownLatch retryIntervalPassed = new CountDownLatch(1);
            scheduler.schedule(retryIntervalPassed::countDown, 2, TimeUnit.SECONDS);
            assertTrue(retryIntervalPassed.await(10, TimeUnit.SECONDS), "等待重发轮询经过一个间隔");
            assertEquals(0, rig.deliveries.get(), "重新打开总开关后，被拦告警仍被发出");
            assertEquals(0, rig.alerts.pendingCount(), "被总开关拦下的告警不得留待重发");
        }
    }

    @Test
    @DisplayName("入队后进入静音时段，原先排队的推送仍照常发出")
    void queuedPushIsNotRecheckedForQuietHours() throws Exception {
        // 抓出队误查静音，导致静音开始前已排队的消息被额外丢弃。
        try (Rig rig = new Rig()) {
            rig.messages.send(Message.create("qq-onebot", PushTargetType.GROUP, 12345L, "开播通知").get(0));
            List<Message> queued = List.copyOf(rig.queue);
            java.time.LocalTime now = java.time.LocalTime.now();
            rig.core.getPush().setQuietStart(now.minusHours(1).toString());
            rig.core.getPush().setQuietEnd(now.plusHours(1).toString());
            assertFalse(new PushGate(rig.core).allowed());
            rig.releaseAndAwait(queued);
            assertEquals(1, rig.deliveries.get());
            assertTrue(rig.events.isEmpty());
        }
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static final class Rig implements AutoCloseable {
        final NovaCoreProperties core = new NovaCoreProperties();
        final AtomicInteger deliveries = new AtomicInteger();
        final List<TimelineEvent> events = new CopyOnWriteArrayList<>();
        final CountDownLatch release = new CountDownLatch(1);
        final BlockingQueue<Message> queue = new LinkedBlockingQueue<>(500) {
            @Override
            public Message take() throws InterruptedException {
                release.await();
                return super.take();
            }
        };
        final NovaMessageSender messages;
        final AlertService alerts;

        @SuppressWarnings("unchecked")
        Rig() throws Exception {
            core.getAlert().setRetryInterval(1);
            Sender target = new Sender();
            target.setName("qq-onebot");
            target.setDelay(0);
            target.setLocalDelivery((headers, params) -> {
                deliveries.incrementAndGet();
                return new JSONObject().fluentPut("code", 0).fluentPut("id", "m1");
            });
            NovaSenderService senders = mock(NovaSenderService.class);
            when(senders.getSender("qq-onebot")).thenReturn(Optional.of(target));
            ObjectProvider<AtAllPermissionResolver> resolvers = mock(ObjectProvider.class);
            TimelineWriter timeline = events::add;
            messages = new NovaMessageSender(mock(HttpUtil.class), senders,
                    new PushActivityRecorder(TimelineWriter.NONE), new PushGate(core), timeline,
                    new AtAllQuotaService(core), resolvers, mock(FirstPushTipService.class));
            ((Map<String, BlockingQueue<Message>>) field(messages, "queueMap")).put("qq-onebot", queue);
            var start = NovaMessageSender.class.getDeclaredMethod("startPlatformThread", Sender.class, BlockingQueue.class);
            start.setAccessible(true);
            start.invoke(messages, target, queue);
            OneBotAdapterPluginProperties adapter = new OneBotAdapterPluginProperties();
            adapter.getAlert().setPlatform("qq-onebot");
            adapter.getAlert().setType(PushTargetType.GROUP.getCode());
            adapter.getAlert().setNum(12345L);
            OneBotConnectionState connections = new OneBotConnectionState();
            connections.httpOk("qq-onebot", "服务正常");
            connections.accountOnline("qq-onebot", "在线");
            AlertChannel channel = new QqAlertChannel(adapter, messages, senders, connections);
            ObjectProvider<AlertChannel> channels = mock(ObjectProvider.class);
            when(channels.orderedStream()).thenAnswer(invocation -> List.of(channel).stream());
            alerts = new AlertService(core, channels, timeline);
        }

        List<Message> enqueueAndCloseSwitch() {
            messages.send(Message.create("qq-onebot", PushTargetType.GROUP, 12345L, "开播通知").get(0));
            messages.send(Message.create("qq-onebot", PushTargetType.GROUP, 12345L, "动态通知").get(0));
            alerts.alert("connection.lost", "连接中断", "请检查连接");
            assertEquals(3, messages.getPendingCount(), "先停住发送线程，再让三条消息经正常入口入队");
            List<Message> queued = List.copyOf(queue);
            core.getPush().setEnabled(false);
            return queued;
        }

        void releaseAndAwait(List<Message> queued) throws InterruptedException {
            CountDownLatch processed = new CountDownLatch(queued.size());
            for (Message message : queued) {
                message.addOnSuccessCallback(processed::countDown);
                message.addOnFailureCallback(processed::countDown);
            }
            release.countDown();
            assertTrue(processed.await(10, TimeUnit.SECONDS), "等发送线程处理完这三条消息再断言");
        }

        @Override
        public void close() {
            release.countDown();
            messages.shutdown();
            alerts.onContextClosedEvent();
        }
    }
}
