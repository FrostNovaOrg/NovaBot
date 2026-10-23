package org.frostnova.nova.core.protocol;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.properties.LiveProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件流各条连接占用的线程
 * <p>
 * 线程数按端点自己的线程池读，不按线程名数全进程：同一个进程里别的端点、别的测试起的线程
 * 名字一样，数名字量到的是它们。
 */
@DisplayName("事件流连接的线程占用")
class NovaEventSenderLifecycleTest {
    private static final int CONNECTIONS = 16;

    /** 等一件「应当很快发生」的事的上限 */
    private static final long WAIT_SECONDS = 10;

    @TempDir
    Path dir;

    private EventStreamTokenService tokens;

    private NovaEventEndpoint endpoint;

    @BeforeEach
    void setUp() {
        LiveProperties live = new LiveProperties();
        live.setLiveDataPath(dir.resolve("data.json").toString());
        tokens = new EventStreamTokenService(live);
    }

    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void tearDown() {
        release.countDown();
        if (endpoint != null) {
            endpoint.shutdown();
        }
    }

    /**
     * 写与关都停在门闩上的会话，到测试结束才放开
     */
    private final class StuckSession extends NovaEventEndpointTest.FakeSession {
        final CountDownLatch closeEntered = new CountDownLatch(1);

        /** 停在门闩上时被叫停了 */
        final CountDownLatch stopped = new CountDownLatch(1);

        StuckSession(String id) {
            super(id);
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) {
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
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                stopped.countDown();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 写停在门闩上、关照常的会话
     */
    private final class WriteStuckSession extends NovaEventEndpointTest.FakeSession {
        final CountDownLatch writeEntered = new CountDownLatch(1);

        WriteStuckSession(String id) {
            super(id);
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) {
            writeEntered.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private ThreadPoolExecutor pool(String name) throws ReflectiveOperationException {
        Field field = NovaEventEndpoint.class.getDeclaredField(name);
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(endpoint);
    }

    private static int closerThreads() throws ReflectiveOperationException {
        Field field = NovaEventEndpoint.class.getDeclaredField("CLOSER_THREADS");
        field.setAccessible(true);
        return field.getInt(null);
    }

    private void stopPool(String name) throws ReflectiveOperationException {
        Field field = NovaEventEndpoint.class.getDeclaredField(name);
        field.setAccessible(true);
        ((ExecutorService) field.get(endpoint)).shutdown();
    }

    /**
     * 连上一条未认证、关闭会停住的连接，等它到点断开、停在关闭上
     */
    private StuckSession stuckUnauthenticated(String id) throws Exception {
        StuckSession session = new StuckSession(id);
        endpoint.afterConnectionEstablished(session);
        assertTrue(session.closeEntered.await(WAIT_SECONDS, TimeUnit.SECONDS), "锚: 未认证的连接没有到点断开");
        return session;
    }

    private static String auth(String token) {
        return "{\"kind\":\"auth\",\"v\":2,\"token\":\"" + token + "\"}";
    }

    @Test
    @Timeout(20)
    @DisplayName("要求口令时，认证之前不起发送线程，认证之后照常下行")
    void senderStartsOnlyAfterAuthentication() throws Exception {
        endpoint = new NovaEventEndpoint(new NovaEventStream(64), tokens);
        NovaEventEndpointTest.FakeSession[] sessions = new NovaEventEndpointTest.FakeSession[CONNECTIONS];
        for (int i = 0; i < CONNECTIONS; i++) {
            sessions[i] = new NovaEventEndpointTest.FakeSession("pending-" + i);
            endpoint.afterConnectionEstablished(sessions[i]);
        }

        assertEquals(CONNECTIONS, endpoint.getClientCount());
        assertEquals(0, pool("senders").getLargestPoolSize(), "认证之前起了发送线程");

        endpoint.handleTextMessage(sessions[0], new TextMessage(auth(tokens.issue("面板-甲"))));

        JSONObject hello = sessions[0].next();
        assertNotNull(hello, "认证通过后应当照常收到 hello");
        assertEquals("hello", hello.getString("kind"));
        assertEquals(1, pool("senders").getLargestPoolSize(), "认证通过的那一条应当有且只有一条发送线程");
        assertEquals(0, sessions[1].sentCount(), "别的未认证连接仍然没有任何下行");
    }

    @Test
    @Timeout(20)
    @DisplayName("不要求口令时，连上即起发送线程并收到 hello")
    void senderStartsOnConnectWithoutToken() throws Exception {
        endpoint = new NovaEventEndpoint(new NovaEventStream(64));
        NovaEventEndpointTest.FakeSession session = new NovaEventEndpointTest.FakeSession("open");

        endpoint.afterConnectionEstablished(session);

        JSONObject hello = session.next();
        assertNotNull(hello, "连上之后应当收到 hello");
        assertEquals("hello", hello.getString("kind"));
        assertEquals(1, pool("senders").getLargestPoolSize());
    }

    @Test
    @Timeout(30)
    @DisplayName("未认证的连接到点断开时不占发送线程，收尾用的线程有上限")
    void unauthenticatedTimeoutsStayBounded() throws Exception {
        endpoint = new NovaEventEndpoint(new NovaEventStream(64), tokens,
                new NovaEventEndpoint.Timings(60_000, 600_000, 50, 60_000));
        NovaEventEndpointTest.FakeSession[] sessions = new NovaEventEndpointTest.FakeSession[CONNECTIONS];
        for (int i = 0; i < CONNECTIONS; i++) {
            sessions[i] = new NovaEventEndpointTest.FakeSession("idle-" + i);
            endpoint.afterConnectionEstablished(sessions[i]);
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        for (NovaEventEndpointTest.FakeSession session : sessions) {
            while (session.closedWith == null && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertNotNull(session.closedWith, "连接 " + session.getId() + " 到点没有断开");
            assertEquals(CloseStatus.POLICY_VIOLATION.getCode(), session.closedWith.getCode());
            assertEquals(0, session.sentCount(), "未认证的连接不该有任何下行");
        }

        assertEquals(0, pool("senders").getLargestPoolSize(), "未认证连接的收尾占了发送线程");
        int limit = closerThreads();
        int closers = pool("closers").getLargestPoolSize();
        assertTrue(closers >= 1 && closers <= limit, "收尾线程应在 1 到 " + limit + " 条之间, 实为 " + closers);
    }

    @Test
    @Timeout(30)
    @DisplayName("已认证连接的关闭帧写不动时，未认证连接照常到点断开")
    void stuckAuthenticatedClosesDoNotHoldUnauthenticatedOnes() throws Exception {
        endpoint = new NovaEventEndpoint(new NovaEventStream(64), tokens,
                new NovaEventEndpoint.Timings(60_000, 1, 200, 60_000));
        String token = tokens.issue("面板-丙");
        int stuckCount = closerThreads();
        StuckSession[] stuck = new StuckSession[stuckCount];
        for (int i = 0; i < stuckCount; i++) {
            stuck[i] = new StuckSession("stuck-" + i);
            endpoint.afterConnectionEstablished(stuck[i]);
            endpoint.handleTextMessage(stuck[i], new TextMessage(auth(token)));
        }

        // 存活时限取 1 毫秒：过了它，下一轮心跳就把这几条已认证的连接断开
        Thread.sleep(20);
        Method heartbeat = NovaEventEndpoint.class.getDeclaredMethod("heartbeat");
        heartbeat.setAccessible(true);
        heartbeat.invoke(endpoint);
        for (StuckSession session : stuck) {
            assertTrue(session.closeEntered.await(WAIT_SECONDS, TimeUnit.SECONDS), "锚: 已认证的连接没有被断开");
        }

        NovaEventEndpointTest.FakeSession idle = new NovaEventEndpointTest.FakeSession("idle");
        endpoint.afterConnectionEstablished(idle);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (idle.closedWith == null && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertNotNull(idle.closedWith, "未认证的连接没有按时断开: 收尾线程被写不动的关闭帧占住了");
        assertEquals(CloseStatus.POLICY_VIOLATION.getCode(), idle.closedWith.getCode());
    }

    @Test
    @Timeout(20)
    @DisplayName("发送线程池不再收活时，认证通过的连接被关掉而不是抛错")
    void connectionClosesWhenSendersRefuse() throws Exception {
        endpoint = new NovaEventEndpoint(new NovaEventStream(64), tokens);
        NovaEventEndpointTest.FakeSession session = new NovaEventEndpointTest.FakeSession("late");
        endpoint.afterConnectionEstablished(session);
        String token = tokens.issue("面板-乙");

        stopPool("senders");
        stopPool("heartbeats");
        endpoint.handleTextMessage(session, new TextMessage(auth(token)));

        assertNotNull(session.closedWith, "发送线程起不来时应当关掉这条连接");
        assertEquals(CloseStatus.GOING_AWAY.getCode(), session.closedWith.getCode());
        assertEquals(0, session.sentCount(), "关掉的连接不该再有下行");
    }

    @Test
    @Timeout(20)
    @DisplayName("不要求口令时线程池已不收活，连接被关掉而不是抛错")
    void openConnectionClosesWhenPoolsRefuse() throws Exception {
        endpoint = new NovaEventEndpoint(new NovaEventStream(64));
        stopPool("senders");
        stopPool("heartbeats");
        NovaEventEndpointTest.FakeSession session = new NovaEventEndpointTest.FakeSession("late-open");

        endpoint.afterConnectionEstablished(session);

        assertNotNull(session.closedWith, "发送线程起不来时应当关掉这条连接");
        assertEquals(CloseStatus.GOING_AWAY.getCode(), session.closedWith.getCode());
        assertEquals(0, session.sentCount(), "关掉的连接不该再有下行");
    }

    @Test
    @Timeout(30)
    @DisplayName("未认证连接到点断开时，收尾线程数不超过上限")
    void closerThreadsStayAtLimit() throws Exception {
        endpoint = new NovaEventEndpoint(new NovaEventStream(64), tokens,
                new NovaEventEndpoint.Timings(60_000, 600_000, 50, 60_000));
        int limit = closerThreads();
        int count = limit + 4;
        StuckSession[] sessions = new StuckSession[count];
        for (int i = 0; i < count; i++) {
            sessions[i] = new StuckSession("held-" + i);
            endpoint.afterConnectionEstablished(sessions[i]);
        }

        // 等全部到点：每一条的关闭都已交给收尾池（在跑的加排队的）
        ThreadPoolExecutor closers = pool("closers");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (closers.getTaskCount() < count && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(count, closers.getTaskCount(), "锚: 未认证的连接没有全部到点断开");

        assertEquals(limit, closers.getLargestPoolSize(), "收尾线程数应当正好是上限");
        int entered = 0;
        for (StuckSession session : sessions) {
            if (session.closeEntered.getCount() == 0) {
                entered++;
            }
        }
        assertEquals(limit, entered, "同时在关的连接数应当正好是上限");
    }

    @Test
    @Timeout(20)
    @DisplayName("没有停住的收尾时，关停不等满宽限")
    void idleShutdownReturnsBeforeDrainLimit() throws Exception {
        endpoint = new NovaEventEndpoint(new NovaEventStream(64), tokens,
                new NovaEventEndpoint.Timings(60_000, 600_000, 50, 60_000));
        NovaEventEndpointTest.FakeSession session = new NovaEventEndpointTest.FakeSession("idle");
        endpoint.afterConnectionEstablished(session);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (session.closedWith == null && System.nanoTime() < until) {
            Thread.sleep(10);
        }
        assertNotNull(session.closedWith, "锚: 未认证的连接没有到点断开");

        long start = System.nanoTime();
        endpoint.shutdown();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsedMs < NovaEventEndpoint.SHUTDOWN_DRAIN_MS,
                "没有停住的收尾，关停却用了 " + elapsedMs + " 毫秒");
        assertTrue(pool("closers").isTerminated(), "关停返回时收尾池应当已经停了");
    }

    @Test
    @Timeout(30)
    @DisplayName("关停时停住的收尾在宽限之后被叫停")
    void shutdownStopsHeldCloser() throws Exception {
        endpoint = new NovaEventEndpoint(new NovaEventStream(64), tokens,
                new NovaEventEndpoint.Timings(60_000, 600_000, 50, 60_000));
        StuckSession held = stuckUnauthenticated("held");

        endpoint.shutdown();

        assertTrue(held.stopped.await(WAIT_SECONDS, TimeUnit.SECONDS), "关停之后收尾线程仍停在关闭上");
    }

    @Test
    @Timeout(30)
    @DisplayName("关停被打断时，收尾线程一并叫停")
    void interruptedShutdownStopsHeldCloser() throws Exception {
        endpoint = new NovaEventEndpoint(new NovaEventStream(64), tokens,
                new NovaEventEndpoint.Timings(60_000, 600_000, 50, 60_000));
        StuckSession held = stuckUnauthenticated("held");

        Thread.currentThread().interrupt();
        endpoint.shutdown();
        boolean flagKept = Thread.interrupted();

        assertTrue(flagKept, "关停被打断后应当保留打断标记");
        assertTrue(held.stopped.await(WAIT_SECONDS, TimeUnit.SECONDS), "关停被打断后收尾线程仍停在关闭上");
    }

    @Test
    @Timeout(30)
    @DisplayName("两个线程池都停住时，关停总共只等一段宽限")
    void shutdownWaitsOneDrainForBothPools() throws Exception {
        endpoint = new NovaEventEndpoint(new NovaEventStream(64), tokens,
                new NovaEventEndpoint.Timings(60_000, 600_000, 500, 60_000));
        WriteStuckSession writer = new WriteStuckSession("writer");
        endpoint.afterConnectionEstablished(writer);
        endpoint.handleTextMessage(writer, new TextMessage(auth(tokens.issue("面板-丁"))));
        assertTrue(writer.writeEntered.await(WAIT_SECONDS, TimeUnit.SECONDS), "锚: 发送线程没有开始写");
        stuckUnauthenticated("held");

        long start = System.nanoTime();
        endpoint.shutdown();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsedMs < 2 * NovaEventEndpoint.SHUTDOWN_DRAIN_MS,
                "两个池子各等了一段宽限: 关停用了 " + elapsedMs + " 毫秒");
    }
}
