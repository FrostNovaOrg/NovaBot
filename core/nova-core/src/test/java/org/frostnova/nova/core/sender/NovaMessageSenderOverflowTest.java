package org.frostnova.nova.core.sender;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.health.PushActivityRecorder;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.Sender;
import org.frostnova.nova.core.service.AtAllQuotaService;
import org.frostnova.nova.core.service.NovaSenderService;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.frostnova.nova.core.util.HttpUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NovaMessageSenderOverflowTest {
    @Test
    @DisplayName("掉线积压满队列后，周报与下播小结持锁发送仍能结束，无需重启恢复")
    void overflowDoesNotDeadlockDeliveryServices() throws Exception {
        try (BlockedPlatform platform = new BlockedPlatform()) {
            ReentrantLock weeklyLock = new ReentrantLock();
            ReentrantLock recapLock = new ReentrantLock();
            CountDownLatch recapLocked = new CountDownLatch(1);
            CountDownLatch firstCallbackStarted = new CountDownLatch(1);
            CountDownLatch callbacksDone = new CountDownLatch(2);
            Message recap = message();
            recap.addOnFailureCallback(() -> {
                firstCallbackStarted.countDown();
                takeLock(recapLock);
                callbacksDone.countDown();
            });
            Message weekly = message();
            weekly.addOnFailureCallback(() -> {
                takeLock(weeklyLock);
                callbacksDone.countDown();
            });
            platform.fill(recap, weekly);

            CountDownLatch callersDone = new CountDownLatch(2);
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread weeklyThread = caller(weeklyLock, () -> {
                await(recapLocked);
                platform.sender.send(message());
            }, callersDone, error);
            Thread recapThread = caller(recapLock, () -> {
                recapLocked.countDown();
                await(firstCallbackStarted);
                // 并发积压补上 poll 留出的空位，让随后这次发送也必须挤出旧消息。
                platform.sender.send(message());
                platform.sender.send(message());
            }, callersDone, error);
            try {
                weeklyThread.start();
                recapThread.start();
                assertTrue(callersDone.await(5, TimeUnit.SECONDS),
                        "周报与下播小结持锁发送互等，两个定时任务不能恢复");
                assertNull(error.get());
                assertTrue(callbacksDone.await(5, TimeUnit.SECONDS), "两个被挤掉消息的失败回调应完成");
            } finally {
                weeklyThread.interrupt();
                recapThread.interrupt();
                weeklyThread.join(10000);
                recapThread.join(10000);
                assertFalse(weeklyThread.isAlive());
                assertFalse(recapThread.isAlive());
            }
        }
    }

    @Test
    @DisplayName("积压时仅丢最旧消息，失败回调在其他线程恰好执行一次，丢弃数和原因正确")
    void oldestMessageFailsExactlyOnceOffCallerThread() throws Exception {
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger newerFailures = new AtomicInteger();
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        CountDownLatch failed = new CountDownLatch(1);
        Message oldest = message();
        oldest.addOnFailureCallback(() -> {
            failures.incrementAndGet();
            callbackThread.set(Thread.currentThread());
            failed.countDown();
        });
        Message newer = message();
        newer.addOnFailureCallback(newerFailures::incrementAndGet);
        try (BlockedPlatform platform = new BlockedPlatform()) {
            platform.fill(oldest, newer);
            platform.sender.send(message());
            assertTrue(failed.await(5, TimeUnit.SECONDS), "被挤掉消息必须通知失败，避免一直处于待发");
            assertNotSame(Thread.currentThread(), callbackThread.get(), "回调不能借用发送调用方的锁");
            assertEquals("队列已满, 被挤掉", oldest.getFailureReason());
            assertEquals(1, platform.sender.getDroppedCount());
            assertEquals(500, platform.sender.getPendingCount());
        }
        assertEquals(1, failures.get(), "关机收尾也不能重复通知被挤掉的消息");
        assertEquals(0, newerFailures.get(), "较新的消息应保留并送达");
    }

    @Test
    @DisplayName("关机等待被挤掉消息的回调完成，避免丢失投递结果")
    void shutdownDrainsOverflowCallbacks() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        CountDownLatch deliveriesDrained = new CountDownLatch(1);
        try (BlockedPlatform platform = new BlockedPlatform()) {
            Message oldest = message();
            oldest.addOnFailureCallback(() -> {
                started.countDown();
                await(release);
            });
            Message next = message();
            next.addOnFailureCallback(completed::countDown);
            platform.fill(oldest, next);
            Thread caller = new Thread(() -> platform.sender.send(message()));
            Thread shutdown = new Thread(platform.sender::shutdown);
            caller.start();
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                caller.join(5000);
                assertFalse(caller.isAlive(), "慢回调不能阻塞新的发送");
                Message tail = message();
                tail.addOnCompleteCallback(deliveriesDrained::countDown);
                platform.sender.send(tail);
                platform.release.countDown();
                assertTrue(deliveriesDrained.await(5, TimeUnit.SECONDS));
                shutdown.start();
                awaitWaitingOrFinished(shutdown);
                assertTrue(shutdown.isAlive(), "关机应等待仍未完成的挤出回调");
                release.countDown();
                shutdown.join(15000);
                assertFalse(shutdown.isAlive(), "关机应在有限等待后结束");
                assertEquals(0, completed.getCount(), "关机返回前应完成已排队的失败回调");
            } finally {
                release.countDown();
                caller.join(10000);
                shutdown.join(15000);
            }
        }
    }

    @Test
    @DisplayName("停机时挤出回调卡住也有等待上限，未开始的回调丢弃并记日志")
    void shutdownAbandonsCallbacksAfterTimeout() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(NovaMessageSender.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.list = new CopyOnWriteArrayList<>();
        logs.start();
        logger.addAppender(logs);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch callbackFinished = new CountDownLatch(1);
        CountDownLatch deliveriesDrained = new CountDownLatch(1);
        AtomicInteger abandoned = new AtomicInteger();
        try (BlockedPlatform platform = new BlockedPlatform()) {
            Message oldest = message();
            oldest.addOnFailureCallback(() -> {
                started.countDown();
                try {
                    await(release);
                } finally {
                    callbackFinished.countDown();
                }
            });
            Message next = message();
            next.addOnFailureCallback(abandoned::incrementAndGet);
            platform.fill(oldest, next);
            platform.sender.send(message());
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Message tail = message();
            tail.addOnCompleteCallback(deliveriesDrained::countDown);
            platform.sender.send(tail);
            platform.release.countDown();
            assertTrue(deliveriesDrained.await(5, TimeUnit.SECONDS));
            Thread shutdown = new Thread(platform.sender::shutdown);
            try {
                shutdown.start();
                shutdown.join(15000);
                assertFalse(shutdown.isAlive(), "停机不能无限等待卡住的回调");
                assertTrue(callbackFinished.await(5, TimeUnit.SECONDS), "超时后应中断正在执行的回调");
                assertEquals(0, abandoned.get(), "超时后不能再执行尚未开始的回调");
                assertTrue(logs.list.stream().anyMatch(event ->
                        event.getFormattedMessage().contains("丢弃 1 个待执行回调")));
            } finally {
                release.countDown();
                shutdown.join(15000);
            }
        } finally {
            release.countDown();
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    @DisplayName("平台配置缺失时，调用方自己消息的失败通知仍在调用线程立即执行")
    void missingPlatformStillFailsSynchronously() throws Exception {
        try (BlockedPlatform platform = new BlockedPlatform()) {
            Message missing = message();
            missing.setPlatform("missing");
            AtomicReference<Thread> callbackThread = new AtomicReference<>();
            missing.addOnFailureCallback(() -> callbackThread.set(Thread.currentThread()));
            platform.sender.send(missing);
            assertSame(Thread.currentThread(), callbackThread.get());
            assertEquals(0, platform.sender.getDroppedCount());
        }
    }

    private static Thread caller(ReentrantLock lock, Runnable action, CountDownLatch done,
                                 AtomicReference<Throwable> error) {
        return new Thread(() -> {
            lock.lock();
            try {
                action.run();
            } catch (Throwable e) {
                error.compareAndSet(null, e);
            } finally {
                lock.unlock();
                done.countDown();
            }
        });
    }

    private static void takeLock(ReentrantLock lock) {
        try {
            lock.lockInterruptibly();
            lock.unlock();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待测试状态超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void awaitWaitingOrFinished(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.isAlive() && thread.getState() != Thread.State.TIMED_WAITING
                && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        assertTrue(!thread.isAlive() || thread.getState() == Thread.State.TIMED_WAITING,
                "关机线程应进入等待或已结束");
    }

    private static Message message() {
        return Message.create("sample", PushTargetType.GROUP, 1001L, "推送示例").get(0);
    }

    private static class BlockedPlatform implements AutoCloseable {
        final CountDownLatch release = new CountDownLatch(1);
        final NovaMessageSender sender;

        @SuppressWarnings("unchecked")
        BlockedPlatform() throws Exception {
            Sender target = new Sender();
            target.setName("sample");
            target.setUrl("https://bot.example.test/send");
            target.setDelay(0);
            NovaSenderService platforms = mock(NovaSenderService.class);
            when(platforms.getSender("sample")).thenReturn(Optional.of(target));
            PushGate gate = mock(PushGate.class);
            when(gate.allowedFor(any(Message.class))).thenReturn(true);
            when(gate.alertsAllowed()).thenReturn(true);
            HttpUtil http = mock(HttpUtil.class);
            CountDownLatch sending = new CountDownLatch(1);
            when(http.postJson(anyString(), anyMap(), anyMap())).thenAnswer(invocation -> {
                sending.countDown();
                await(release);
                return new JSONObject().fluentPut("code", 0).fluentPut("id", "sample-id");
            });
            sender = new NovaMessageSender(http, platforms, mock(PushActivityRecorder.class), gate,
                    TimelineWriter.NONE, mock(AtAllQuotaService.class), mock(ObjectProvider.class),
                    mock(FirstPushTipService.class));
            sender.send(message());
            assertTrue(sending.await(5, TimeUnit.SECONDS), "平台发送线程必须已经卡在投递中");
        }

        void fill(Message first, Message second) {
            sender.send(first);
            sender.send(second);
            for (int i = 2; i < 500; i++) {
                sender.send(message());
            }
            assertEquals(500, sender.getPendingCount());
            assertEquals(0, sender.getDroppedCount());
        }

        @Override
        public void close() {
            release.countDown();
            sender.shutdown();
        }
    }
}
