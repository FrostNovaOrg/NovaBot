package org.frostnova.nova.core.protocol;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.websocket.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.adapter.standard.StandardWebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(30)
class EventConnectionBudgetTest {
    private final NovaEventStreamConfiguration configuration = new NovaEventStreamConfiguration();
    private WebSocketHttpRequestHandler handler;
    private NovaEventEndpoint endpoint;
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void finish() {
        release.countDown();
        configuration.destroy();
    }

    private void setup(Map<String, Object> values) {
        EventStreamTokenService tokens = mock(EventStreamTokenService.class);
        when(tokens.authenticate(any(), any())).thenReturn(EventStreamTokenService.Verdict.BAD_TOKEN);
        handler = EventStreamSetupTest.handler(configuration, tokens, values);
        endpoint = (NovaEventEndpoint) WebSocketHandlerDecorator.unwrap(handler.getWebSocketHandler());
    }

    private final class Attempt {
        final MockHttpServletResponse output = new MockHttpServletResponse();
        final ServletServerHttpResponse response = new ServletServerHttpResponse(output);
        final ServletServerHttpRequest request;
        final Map<String, Object> attributes = new HashMap<>();
        boolean accepted = true;

        Attempt(String source) throws Exception {
            MockHttpServletRequest input = new MockHttpServletRequest("GET", "/nova/events");
            input.setRemoteAddr(source);
            request = new ServletServerHttpRequest(input);
            for (var interceptor : handler.getHandshakeInterceptors()) {
                if (!interceptor.beforeHandshake(request, response, handler.getWebSocketHandler(), attributes)) {
                    accepted = false;
                    break;
                }
            }
        }

        void complete(boolean success) {
            output.setStatus(success ? 101 : 400);
            for (var interceptor : handler.getHandshakeInterceptors()) {
                interceptor.afterHandshake(request, response, handler.getWebSocketHandler(), null);
            }
        }

        NovaEventEndpointTest.FakeSession connect(String id) {
            var session = new NovaEventEndpointTest.FakeSession(id) {
                @Override public Map<String, Object> getAttributes() { return attributes; }
            };
            endpoint.afterConnectionEstablished(session);
            complete(true);
            return session;
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < until) {
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean());
    }

    private ThreadPoolExecutor pool(String name) throws Exception {
        Field field = NovaEventEndpoint.class.getDeclaredField(name);
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(endpoint);
    }

    @Test
    void pendingAllowance() throws Exception {
        setup(Map.of("max-pending-connections", 2));
        new Attempt("127.0.0.1").connect("one");
        new Attempt("127.0.0.1").connect("two");
        Attempt next = new Attempt("127.0.0.1");
        assertFalse(next.accepted);
        assertEquals(503, next.output.getStatus());
        assertEquals("5", next.output.getHeader("Retry-After"));
    }

    @Test
    void totalAllowance() throws Exception {
        setup(Map.of("max-connections", 2));
        new Attempt("127.0.0.1").connect("one");
        new Attempt("127.0.0.2").connect("two");
        assertFalse(new Attempt("127.0.0.3").accepted);
    }

    @Test
    void sourceAllowance() throws Exception {
        setup(Map.of("max-connections-per-source", 1));
        new Attempt("127.0.0.1").connect("one");
        assertFalse(new Attempt("127.0.0.1").accepted);
        assertTrue(new Attempt("127.0.0.2").accepted);
    }

    @Test
    void handshakeAllowance() throws Exception {
        setup(Map.of("max-concurrent-handshakes", 1));
        Attempt first = new Attempt("127.0.0.1");
        assertTrue(first.accepted);
        assertFalse(new Attempt("127.0.0.2").accepted);
        first.complete(false);
        assertTrue(new Attempt("127.0.0.2").accepted);
    }

    @Test
    void completedHandshakeKeepsConnectionAllowance() throws Exception {
        setup(Map.of("max-connections", 1));
        Attempt first = new Attempt("127.0.0.1");
        first.complete(true);
        assertFalse(new Attempt("127.0.0.2").accepted);
        var session = first.connect("one");
        endpoint.afterConnectionClosed(session, CloseStatus.NORMAL);
        endpoint.afterConnectionClosed(session, CloseStatus.NORMAL);
        assertTrue(new Attempt("127.0.0.2").accepted);
        assertFalse(new Attempt("127.0.0.3").accepted);
    }

    @Test
    void handshakeWindow() throws Exception {
        setup(Map.of("handshake-timeout-ms", 30, "max-connections", 1));
        Attempt first = new Attempt("127.0.0.1");
        first.complete(true);
        await(() -> poolUnchecked().getQueue().isEmpty());
        Attempt next = null;
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until) {
            next = new Attempt("127.0.0.2");
            if (next.accepted) break;
            Thread.sleep(5);
        }
        assertNotNull(next);
        assertTrue(next.accepted);
        assertNotNull(first.connect("late").closedWith);
    }

    private ThreadPoolExecutor poolUnchecked() {
        try { return pool("heartbeats"); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Test
    void parallelHandshakes() throws Exception {
        setup(Map.of("max-concurrent-handshakes", 3));
        var workers = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<Attempt>> results = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                results.add(workers.submit(() -> { start.await(); return new Attempt("127.0.0.1"); }));
            }
            start.countDown();
            int accepted = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS).accepted) accepted++;
            assertEquals(3, accepted);
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void directConnectionsUseAllowance() throws Exception {
        setup(Map.of("max-pending-connections", 1));
        endpoint.afterConnectionEstablished(new NovaEventEndpointTest.FakeSession("one"));
        var next = new NovaEventEndpointTest.FakeSession("two");
        endpoint.afterConnectionEstablished(next);
        assertEquals(1, endpoint.getClientCount());
        assertNotNull(next.closedWith);
    }

    @Test
    void completedConnectionsLeaveTimerQueue() throws Exception {
        setup(Map.of());
        for (int i = 0; i < 30; i++) {
            var session = new Attempt("127.0.0.1").connect("client-" + i);
            endpoint.afterConnectionClosed(session, CloseStatus.NORMAL);
        }
        assertEquals(0, pool("heartbeats").getQueue().size());
    }

    @Test
    void executorAllowances() throws Exception {
        setup(Map.of("max-connections", 3));
        assertEquals(6, pool("senders").getMaximumPoolSize());
        assertEquals(3, pool("closers").getQueue().remainingCapacity());
    }

    @Test
    void closeCompletionRetainsAllowance() throws Exception {
        setup(Map.of("max-connections", 1));
        CountDownLatch entered = new CountDownLatch(1);
        var session = new NovaEventEndpointTest.FakeSession("held") {
            @Override public void close(CloseStatus status) {
                closedWith = status;
                entered.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        };
        endpoint.afterConnectionEstablished(session);
        String json = "{\"kind\":\"auth\",\"token\":\"panel\"}";
        var worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(() -> { try { endpoint.handleTextMessage(session, new TextMessage(json)); }
                catch (Exception e) { throw new IllegalStateException(e); } });
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            assertFalse(new Attempt("127.0.0.2").accepted);
            release.countDown();
            await(() -> endpoint.getClientCount() == 0);
            assertTrue(new Attempt("127.0.0.2").accepted);
        } finally {
            release.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void sessionWriteAndCloseWindow() throws Exception {
        setup(Map.of("io-timeout-ms", 1234));
        StandardWebSocketSession session = mock(StandardWebSocketSession.class);
        Session nativeSession = mock(Session.class);
        Map<String, Object> settings = new HashMap<>();
        when(session.getId()).thenReturn("standard");
        when(session.getAttributes()).thenReturn(new HashMap<>());
        when(session.getRemoteAddress()).thenReturn(new java.net.InetSocketAddress("127.0.0.1", 42));
        when(session.getNativeSession()).thenReturn(nativeSession);
        when(nativeSession.getUserProperties()).thenReturn(settings);
        endpoint.afterConnectionEstablished(session);
        assertEquals(1234L, settings.get("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT"));
        assertEquals(1234L, settings.get("org.apache.tomcat.websocket.SESSION_CLOSE_TIMEOUT"));
        assertEquals(1234L, settings.get("org.apache.tomcat.websocket.ABNORMAL_SESSION_CLOSE_SEND_TIMEOUT"));
    }

    @Test
    void adjustmentMessage() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("org.frostnova.nova.core.protocol.EventConnectionLimits");
        ListAppender<ILoggingEvent> messages = new ListAppender<>();
        messages.start();
        logger.addAppender(messages);
        try {
            setup(Map.of("max-connections", 1));
            new Attempt("127.0.0.1").connect("one");
            new Attempt("127.0.0.2");
            assertTrue(messages.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(text -> text.contains("max-connections") && text.contains("重启")));
        } finally {
            logger.detachAppender(messages);
            messages.stop();
        }
    }
    @Test
    void defaultValues() throws Exception {
        var values = new org.frostnova.nova.core.properties.EventStreamProperties();
        Map<String, Integer> expected = Map.of("maxConnections", 256, "maxPendingConnections", 128,
                "maxConnectionsPerSource", 128, "maxConcurrentHandshakes", 64,
                "handshakeTimeoutMs", 10000, "ioTimeoutMs", 10000);
        for (var entry : expected.entrySet()) {
            Field field = values.getClass().getDeclaredField(entry.getKey());
            field.setAccessible(true);
            assertEquals(entry.getValue(), field.get(values));
        }
    }

    @Test
    void previousValuesUseDefaults() throws Exception {
        setup(Map.of("max-connections", 0, "max-pending-connections", -1,
                "max-connections-per-source", 0, "max-concurrent-handshakes", -2,
                "handshake-timeout-ms", 0, "io-timeout-ms", -1));
        var limits = org.springframework.test.util.ReflectionTestUtils.getField(endpoint, "limits");
        for (var entry : Map.of("maxConnections", 256, "maxPending", 128, "maxPerSource", 128,
                "maxHandshakes", 64, "handshakeTimeout", 10000, "ioTimeout", 10000).entrySet()) {
            Field field = limits.getClass().getDeclaredField(entry.getKey());
            field.setAccessible(true);
            assertEquals(entry.getValue(), field.get(limits));
        }
    }

    @Test
    void firstFrameCompletesPendingStage() throws Exception {
        EventStreamTokenService tokens = mock(EventStreamTokenService.class);
        doAnswer(call -> {
            ((java.util.function.Consumer<String>) call.getArgument(1)).accept("panel");
            return EventStreamTokenService.Verdict.OK;
        }).when(tokens).authenticate(any(), any());
        handler = EventStreamSetupTest.handler(configuration, tokens,
                Map.of("max-pending-connections", 1, "max-connections-per-source", 2));
        endpoint = (NovaEventEndpoint) WebSocketHandlerDecorator.unwrap(handler.getWebSocketHandler());
        var first = new Attempt("127.0.0.1").connect("one");
        endpoint.handleTextMessage(first, new TextMessage("{\"kind\":\"auth\",\"token\":\"panel\"}"));
        assertEquals("hello", first.next().getString("kind"));
        assertTrue(new Attempt("127.0.0.1").accepted);
        assertFalse(new Attempt("127.0.0.1").accepted);
    }

    @Test
    void heldHandshakeRetainsAllowance() throws Exception {
        setup(Map.of("max-concurrent-handshakes", 2, "max-connections", 1, "handshake-timeout-ms", 30));
        Attempt first = new Attempt("127.0.0.1");
        await(() -> poolUnchecked().getCompletedTaskCount() >= 1);
        assertFalse(new Attempt("127.0.0.2").accepted);
        first.complete(false);
        assertTrue(new Attempt("127.0.0.2").accepted);
    }

    @Test
    void repeatedRequestsShareNotice() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("org.frostnova.nova.core.protocol.EventConnectionLimits");
        ListAppender<ILoggingEvent> messages = new ListAppender<>();
        messages.start();
        logger.addAppender(messages);
        try {
            setup(Map.of("max-connections", 1));
            new Attempt("127.0.0.1").connect("one");
            for (int i = 0; i < 100; i++) assertFalse(new Attempt("127.0.0.2").accepted);
            assertEquals(1, messages.list.stream().filter(event ->
                    event.getFormattedMessage().contains("max-connections")).count());
        } finally {
            logger.detachAppender(messages);
            messages.stop();
        }
    }

    @Test
    void handshakeWindowNoticeOncePerFiveSeconds() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("org.frostnova.nova.core.protocol.EventConnectionLimits");
        ListAppender<ILoggingEvent> messages = new ListAppender<>();
        messages.start();
        logger.addAppender(messages);
        try {
            setup(Map.of("handshake-timeout-ms", 20));
            java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong();
            org.springframework.test.util.ReflectionTestUtils.setField(endpoint.connectionLimits(), "clock",
                    (java.util.function.LongSupplier) now::get);
            java.util.function.LongSupplier notices = () -> messages.list.stream()
                    .filter(event -> event.getFormattedMessage().contains("handshake-timeout-ms")).count();

            for (int i = 0; i < 20; i++) assertTrue(new Attempt("127.0.0.1").accepted);
            await(() -> poolUnchecked().getCompletedTaskCount() >= 20);
            assertEquals(1, notices.getAsLong());

            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(4900));
            assertTrue(new Attempt("127.0.0.1").accepted);
            await(() -> poolUnchecked().getCompletedTaskCount() >= 21);
            assertEquals(1, notices.getAsLong());

            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(200));
            assertTrue(new Attempt("127.0.0.1").accepted);
            await(() -> poolUnchecked().getCompletedTaskCount() >= 22);
            assertEquals(2, notices.getAsLong());
        } finally {
            logger.detachAppender(messages);
            messages.stop();
        }
    }

    @Test
    void closingPoolsHaveSeparateAllowances() throws Exception {
        setup(Map.of("max-connections", 3));
        assertEquals(2, pool("closers").getMaximumPoolSize());
        assertEquals(3, pool("activeClosers").getMaximumPoolSize());
        assertEquals(0, pool("activeClosers").getQueue().remainingCapacity());
    }

    @Test
    void connectionSeries() throws Exception {
        setup(Map.of("max-connections", 8, "max-pending-connections", 8,
                "max-connections-per-source", 8));
        for (int round = 0; round < 40; round++) {
            List<NovaEventEndpointTest.FakeSession> sessions = new ArrayList<>();
            for (int i = 0; i < 8; i++) sessions.add(new Attempt("127.0.0.1").connect(round + "-" + i));
            for (int i = 0; i < 8; i++) assertFalse(new Attempt("127.0.0.1").accepted);
            assertEquals(8, endpoint.getClientCount());
            assertEquals(0, pool("senders").getLargestPoolSize());
            for (var session : sessions) endpoint.afterConnectionClosed(session, CloseStatus.NORMAL);
            assertEquals(0, endpoint.getClientCount());
            assertEquals(0, poolUnchecked().getQueue().size());
        }
    }

    @Test
    void connectedTimerCount() throws Exception {
        setup(Map.of());
        new Attempt("127.0.0.1").connect("one");
        assertEquals(1, poolUnchecked().getQueue().size());
    }

    @Test
    void closeWorkUsesOwnPool() throws Exception {
        EventStreamTokenService tokens = mock(EventStreamTokenService.class);
        doAnswer(call -> {
            ((java.util.function.Consumer<String>) call.getArgument(1)).accept("panel");
            return EventStreamTokenService.Verdict.OK;
        }).when(tokens).authenticate(any(), any());
        handler = EventStreamSetupTest.handler(configuration, tokens, Map.of());
        endpoint = (NovaEventEndpoint) WebSocketHandlerDecorator.unwrap(handler.getWebSocketHandler());
        var session = new Attempt("127.0.0.1").connect("one");
        endpoint.handleTextMessage(session, new TextMessage("{\"kind\":\"auth\",\"token\":\"panel\"}"));
        assertNotNull(session.next());
        var clients = (Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(endpoint, "clients");
        org.springframework.test.util.ReflectionTestUtils.setField(clients.get("one"), "lastSeenAt", 0L);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(endpoint, "heartbeat");
        await(() -> session.closedWith != null);
        assertEquals(1, pool("activeClosers").getTaskCount());
    }

    @Test
    void shutdownCompletesWithinWindow() throws Exception {
        setup(Map.of());
        CountDownLatch entered = new CountDownLatch(1);
        var session = new NovaEventEndpointTest.FakeSession("held") {
            @Override public void close(CloseStatus status) {
                entered.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        };
        endpoint.afterConnectionEstablished(session);
        var worker = Executors.newSingleThreadExecutor();
        try {
            assertDoesNotThrow(() -> worker.submit(endpoint::shutdown).get(3, TimeUnit.SECONDS));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            await(() -> poolUnchecked().isTerminated());
            assertTrue(pool("activeClosers").isTerminated());
        } finally {
            release.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void writeCompletionMessage() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(NovaEventEndpoint.class);
        ListAppender<ILoggingEvent> messages = new ListAppender<>();
        messages.start();
        logger.addAppender(messages);
        var local = new NovaEventEndpoint(new NovaEventStream(10));
        try {
            var session = new NovaEventEndpointTest.FakeSession("panel") {
                @Override public void sendMessage(org.springframework.web.socket.WebSocketMessage<?> frame) {
                    throw new IllegalStateException(new java.net.SocketTimeoutException("write complete"));
                }
                @Override public void close(CloseStatus status) { throw new IllegalStateException(new java.net.SocketTimeoutException("close complete")); }
            };
            local.afterConnectionEstablished(session);
            await(() -> local.getClientCount() == 0);
            assertEquals(2, messages.list.stream().filter(message -> message.getFormattedMessage()
                    .contains("io-timeout-ms") && message.getFormattedMessage().contains("重启")).count());
        } finally {
            local.shutdown();
            logger.detachAppender(messages);
            messages.stop();
        }
    }

    @Test
    void closingCallbackRetainsAllowance() throws Exception {
        setup(Map.of("max-connections", 1));
        CountDownLatch entered = new CountDownLatch(1);
        var session = new NovaEventEndpointTest.FakeSession("held") {
            @Override public void close(CloseStatus status) {
                endpoint.afterConnectionClosed(this, status);
                entered.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        };
        endpoint.afterConnectionEstablished(session);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var result = worker.submit(() -> endpoint.handleTextMessage(session,
                    new TextMessage("{\"kind\":\"auth\",\"token\":\"panel\"}")));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            assertFalse(new Attempt("127.0.0.2").accepted);
            release.countDown();
            result.get(10, TimeUnit.SECONDS);
            assertTrue(new Attempt("127.0.0.2").accepted);
        } finally {
            release.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void stoppedEndpointHasNoNewHandshake() throws Exception {
        setup(Map.of());
        endpoint.shutdown();
        Attempt next = assertDoesNotThrow(() -> new Attempt("127.0.0.1"));
        assertFalse(next.accepted);
    }

    @Test
    void incompleteHandshakeReturnsAllowance() throws Exception {
        setup(Map.of("max-connections", 1, "max-pending-connections", 1,
                "max-connections-per-source", 1, "max-concurrent-handshakes", 1));
        Attempt first = new Attempt("127.0.0.1");
        first.complete(false);
        assertTrue(new Attempt("127.0.0.1").accepted);
    }

    @Test
    void openPanelsUseTotalAllowance() throws Exception {
        var properties = configuration.eventStreamProperties(new org.springframework.mock.env.MockEnvironment()
                .withProperty("novabot.core.event-stream.max-pending-connections", "1"));
        var constructor = NovaEventEndpoint.class.getDeclaredConstructor(NovaEventStream.class,
                EventStreamTokenService.class, NovaEventEndpoint.Timings.class,
                org.frostnova.nova.core.properties.EventStreamProperties.class);
        constructor.setAccessible(true);
        var local = constructor.newInstance(new NovaEventStream(10), null,
                NovaEventEndpoint.Timings.DEFAULT, properties);
        try {
            var first = new NovaEventEndpointTest.FakeSession("one");
            var second = new NovaEventEndpointTest.FakeSession("two");
            local.afterConnectionEstablished(first);
            local.afterConnectionEstablished(second);
            assertEquals(2, local.getClientCount());
            assertNotNull(first.next());
            assertNotNull(second.next());
        } finally {
            local.shutdown();
        }
    }

    @Test
    void firstFrameTimerCompletion() throws Exception {
        timerCompletion(0);
    }

    @Test
    void liveTimerCompletion() throws Exception {
        timerCompletion(1);
    }

    @Test
    void panelTimerCompletion() throws Exception {
        timerCompletion(2);
    }

    private void timerCompletion(int stage) throws Exception {
        var tokens = stage == 1 ? null : mock(EventStreamTokenService.class);
        if (stage == 2) {
            doAnswer(call -> {
                ((java.util.function.Consumer<String>) call.getArgument(1)).accept("panel");
                return EventStreamTokenService.Verdict.OK;
            }).when(tokens).authenticate(any(), any());
        }
        var local = new NovaEventEndpoint(new NovaEventStream(10), tokens);
        var session = new NovaEventEndpointTest.FakeSession("panel");
        var timer = new java.util.concurrent.ScheduledThreadPoolExecutor(1) {
            @Override public java.util.concurrent.ScheduledFuture<?> schedule(Runnable command,
                    long delay, TimeUnit unit) {
                var task = super.schedule(command, 60, TimeUnit.SECONDS);
                if (stage != 2 || unit.toMillis(delay) == NovaEventEndpoint.RESUME_GRACE_MS) {
                    local.afterConnectionClosed(session, CloseStatus.NORMAL);
                }
                return task;
            }
        };
        timer.setRemoveOnCancelPolicy(true);
        var original = (java.util.concurrent.ScheduledExecutorService)
                org.springframework.test.util.ReflectionTestUtils.getField(local, "heartbeats");
        original.shutdownNow();
        org.springframework.test.util.ReflectionTestUtils.setField(local, "heartbeats", timer);
        try {
            local.afterConnectionEstablished(session);
            if (stage == 2) {
                local.handleTextMessage(session, new TextMessage("{\"kind\":\"auth\",\"token\":\"panel\"}"));
            }
            assertEquals(0, local.getClientCount());
            assertEquals(0, timer.getQueue().size());
        } finally {
            local.shutdown();
            assertTrue(timer.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void activeCloseSeries() throws Exception {
        var tokens = mock(EventStreamTokenService.class);
        doAnswer(call -> {
            ((java.util.function.Consumer<String>) call.getArgument(1)).accept("panel");
            return EventStreamTokenService.Verdict.OK;
        }).when(tokens).authenticate(any(), any());
        handler = EventStreamSetupTest.handler(configuration, tokens, Map.of("max-connections", 3));
        endpoint = (NovaEventEndpoint) WebSocketHandlerDecorator.unwrap(handler.getWebSocketHandler());
        CountDownLatch entered = new CountDownLatch(3);
        for (int i = 0; i < 3; i++) {
            var session = new NovaEventEndpointTest.FakeSession("panel-" + i) {
                @Override public void close(CloseStatus status) {
                    entered.countDown();
                    try { release.await(20, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
            };
            endpoint.afterConnectionEstablished(session);
            endpoint.handleTextMessage(session, new TextMessage("{\"kind\":\"auth\",\"token\":\"panel\"}"));
            assertNotNull(session.next());
            var clients = (Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(endpoint, "clients");
            org.springframework.test.util.ReflectionTestUtils.setField(clients.get(session.getId()), "lastSeenAt", 0L);
        }
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(endpoint, "heartbeat");
        assertTrue(entered.await(10, TimeUnit.SECONDS));
    }

    @Test
    void senderAllowanceMessage() throws Exception {
        setup(Map.of());
        var tokens = (EventStreamTokenService) org.springframework.test.util.ReflectionTestUtils.getField(endpoint, "tokens");
        doAnswer(call -> {
            ((java.util.function.Consumer<String>) call.getArgument(1)).accept("panel");
            return EventStreamTokenService.Verdict.OK;
        }).when(tokens).authenticate(any(), any());
        var original = pool("senders");
        original.shutdownNow();
        var sender = mock(ThreadPoolExecutor.class);
        doThrow(new java.util.concurrent.RejectedExecutionException()).when(sender).execute(any(Runnable.class));
        org.springframework.test.util.ReflectionTestUtils.setField(endpoint, "senders", sender);
        var logger = (Logger) LoggerFactory.getLogger(NovaEventEndpoint.class);
        ListAppender<ILoggingEvent> messages = new ListAppender<>();
        messages.start();
        logger.addAppender(messages);
        try {
            var session = new NovaEventEndpointTest.FakeSession("panel");
            endpoint.afterConnectionEstablished(session);
            endpoint.handleTextMessage(session, new TextMessage("{\"kind\":\"auth\",\"token\":\"panel\"}"));
            assertNotNull(session.closedWith);
            assertTrue(messages.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(text -> text.contains("max-connections") && text.contains("重启")));
        } finally {
            logger.detachAppender(messages);
            messages.stop();
            assertTrue(original.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void handshakeWindowMessage() throws Exception {
        var logger = (Logger) LoggerFactory.getLogger("org.frostnova.nova.core.protocol.EventConnectionLimits");
        ListAppender<ILoggingEvent> messages = new ListAppender<>();
        messages.start();
        logger.addAppender(messages);
        try {
            setup(Map.of("handshake-timeout-ms", 30));
            new Attempt("127.0.0.1").complete(true);
            await(() -> poolUnchecked().getCompletedTaskCount() > 0);
            assertTrue(messages.list.stream().filter(event -> event.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(text -> text.contains("handshake-timeout-ms") && text.contains("重启")));
        } finally {
            logger.detachAppender(messages);
            messages.stop();
        }
    }

    @Test
    void defaultValueMessages() {
        var logger = (Logger) LoggerFactory.getLogger("org.frostnova.nova.core.protocol.EventConnectionLimits");
        ListAppender<ILoggingEvent> messages = new ListAppender<>();
        messages.start();
        logger.addAppender(messages);
        try {
            setup(Map.of("max-connections", 0, "max-pending-connections", 0,
                    "max-connections-per-source", 0, "max-concurrent-handshakes", 0,
                    "handshake-timeout-ms", 0, "io-timeout-ms", 0));
            for (String key : List.of("max-connections", "max-pending-connections",
                    "max-connections-per-source", "max-concurrent-handshakes",
                    "handshake-timeout-ms", "io-timeout-ms")) {
                assertTrue(messages.list.stream().filter(event -> event.getLevel() == Level.WARN)
                        .map(ILoggingEvent::getFormattedMessage)
                        .anyMatch(text -> text.contains("." + key + " ")
                                && text.contains("默认值") && text.contains("重启")));
            }
        } finally {
            logger.detachAppender(messages);
            messages.stop();
        }
    }

    @Test
    void shutdownReturnsReservations() throws Exception {
        setup(Map.of());
        for (int i = 0; i < 4; i++) assertTrue(new Attempt("127.0.0.1").accepted);
        endpoint.shutdown();
        var limits = org.springframework.test.util.ReflectionTestUtils.getField(endpoint, "limits");
        for (String field : List.of("connections", "pending")) {
            assertEquals(0, org.springframework.test.util.ReflectionTestUtils.getField(limits, field));
        }
        for (String field : List.of("sources", "handshakes")) {
            assertTrue(((Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(limits, field)).isEmpty());
        }
    }

}
