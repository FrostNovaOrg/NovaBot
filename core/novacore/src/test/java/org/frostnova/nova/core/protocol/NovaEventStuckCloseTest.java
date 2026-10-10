package org.frostnova.nova.core.protocol;

import org.frostnova.nova.core.properties.LiveProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketMessage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 关闭帧写不动的客户端不拖住心跳线程
 * <p>
 * 使用者故障：外接的某个事件流程序卡死、不再读数据时，其他正常的事件流客户端一段时间收不到 ping，
 * 被误判超时、断开重连；别的连接的认证时限与回补窗口也跟着停住。
 * <p>
 * 心跳线程是全局共享的单线程，而关一条连接要发关闭帧、那是一次阻塞写。
 * 这里的假会话把关闭帧停在一把门闩上，到测试结束才放开——在心跳线程上就地关它的话，
 * 心跳线程就停在那儿。
 */
@DisplayName("卡住的客户端不拖住心跳")
class NovaEventStuckCloseTest {

    /** 等一件「应当很快发生」的事的上限 */
    private static final long WAIT_SECONDS = 3;

    @TempDir
    Path dir;

    private NovaEventEndpoint endpoint;

    private final CountDownLatch release = new CountDownLatch(1);

    private Thread round;

    @AfterEach
    void tearDown() throws InterruptedException {
        release.countDown();
        if (round != null) {
            round.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        }
        if (endpoint != null) {
            endpoint.shutdown();
        }
    }

    /**
     * 写与关都停在门闩上的会话：对端不读数据时 socket 写就是这样
     */
    private final class StuckSession extends NovaEventEndpointTest.FakeSession {
        final CountDownLatch writeEntered = new CountDownLatch(1);

        final CountDownLatch closeEntered = new CountDownLatch(1);

        StuckSession(String id) {
            super(id);
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) {
            writeEntered.countDown();
            hold();
        }

        @Override
        public void close(CloseStatus status) {
            closedWith = status;
            closeEntered.countDown();
            hold();
        }

        private void hold() {
            try {
                // 有上限：失败时也不把整个构建挂住
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    @Timeout(20)
    @DisplayName("待发队列已满、关闭帧写不动的客户端在场时，一轮心跳照常走完，正常客户端收到 ping")
    void stuckClientDoesNotHoldHeartbeatRound() throws Exception {
        endpoint = new NovaEventEndpoint(new NovaEventStream(64), null,
                new NovaEventEndpoint.Timings(60_000, 600_000, 60_000, 60_000));

        StuckSession stuck = new StuckSession("stuck");
        endpoint.afterConnectionEstablished(stuck);
        assertTrue(stuck.writeEntered.await(WAIT_SECONDS, TimeUnit.SECONDS), "锚: 发送线程没有停在 hello 的写上");

        BlockingQueue<String> outbox = outbox("stuck");
        while (outbox.offer("{}")) {
            // 灌满待发队列，下一条 ping 就会触发「队列满即断开」
        }
        assertEquals(0, outbox.remainingCapacity(), "锚: 待发队列没灌满");

        NovaEventEndpointTest.FakeSession healthy = new NovaEventEndpointTest.FakeSession("healthy");
        endpoint.afterConnectionEstablished(healthy);
        assertEquals("hello", healthy.next().getString("kind"));

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Method heartbeat = NovaEventEndpoint.class.getDeclaredMethod("heartbeat");
        heartbeat.setAccessible(true);
        round = new Thread(() -> {
            try {
                heartbeat.invoke(endpoint);
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "heartbeat-round");
        round.setDaemon(true);
        round.start();
        round.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));

        assertFalse(round.isAlive(), "一轮心跳 " + WAIT_SECONDS + " 秒内没走完: 停在了写不动的那条连接的关闭帧上");
        assertNull(failure.get(), "一轮心跳抛了异常: " + failure.get());
        assertEquals("ping", healthy.next().getString("kind"), "正常客户端这一轮没收到 ping");

        assertTrue(stuck.closeEntered.await(WAIT_SECONDS, TimeUnit.SECONDS), "队列满的客户端没被断开");
        assertEquals(CloseStatus.SERVICE_OVERLOAD.getCode(), stuck.closedWith.getCode());
        assertEquals("客户端消费过慢", stuck.closedWith.getReason());
    }

    @Test
    @Timeout(20)
    @DisplayName("关闭帧写不动的未认证客户端到点被断开后，心跳线程上排在后面的任务照常执行")
    void stuckUnauthenticatedCloseDoesNotHoldHeartbeatThread() throws Exception {
        LiveProperties live = new LiveProperties();
        live.setLiveDataPath(dir.resolve("data.json").toString());
        endpoint = new NovaEventEndpoint(new NovaEventStream(64), new EventStreamTokenService(live),
                new NovaEventEndpoint.Timings(60_000, 600_000, 50, 60_000));

        StuckSession stuck = new StuckSession("unauthenticated");
        endpoint.afterConnectionEstablished(stuck);

        assertTrue(stuck.closeEntered.await(WAIT_SECONDS, TimeUnit.SECONDS), "未认证超时没有断开连接");
        assertEquals(CloseStatus.POLICY_VIOLATION.getCode(), stuck.closedWith.getCode());
        assertEquals("未在时限内认证", stuck.closedWith.getReason());

        CountDownLatch ran = new CountDownLatch(1);
        heartbeats().execute(ran::countDown);
        assertTrue(ran.await(WAIT_SECONDS, TimeUnit.SECONDS),
                "心跳线程 " + WAIT_SECONDS + " 秒内没执行排在后面的任务: 停在了未认证连接的关闭帧上");
    }

    @SuppressWarnings("unchecked")
    private BlockingQueue<String> outbox(String sessionId) throws ReflectiveOperationException {
        Field clientsField = NovaEventEndpoint.class.getDeclaredField("clients");
        clientsField.setAccessible(true);
        Object client = ((Map<String, ?>) clientsField.get(endpoint)).get(sessionId);
        Field outboxField = client.getClass().getDeclaredField("outbox");
        outboxField.setAccessible(true);
        return (BlockingQueue<String>) outboxField.get(client);
    }

    private ScheduledExecutorService heartbeats() throws ReflectiveOperationException {
        Field field = NovaEventEndpoint.class.getDeclaredField("heartbeats");
        field.setAccessible(true);
        return (ScheduledExecutorService) field.get(endpoint);
    }
}
