package org.frostnova.nova.core.protocol;

import org.frostnova.nova.core.properties.EventStreamProperties;
import org.frostnova.nova.core.properties.LiveProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import org.frostnova.nova.core.model.EventStreamToken;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import java.util.List;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventStreamSetupTest {
    @TempDir
    Path directory;

    @Test
    void defaults() {
        assertTrue(new EventStreamProperties().isRequireToken());
    }

    @Test
    void startupMessage() {
        Logger logger = (Logger) LoggerFactory.getLogger(NovaEventStreamConfiguration.class);
        ListAppender<ILoggingEvent> messages = new ListAppender<>();
        messages.start();
        logger.addAppender(messages);
        NovaEventStreamConfiguration configuration = new NovaEventStreamConfiguration();
        try {
            handler(configuration, null, Map.of());
            String text = messages.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(text.contains("外部面板"));
            assertTrue(text.contains("首帧"));
            assertTrue(text.contains("allowed-origins"));
            assertTrue(text.contains("重启"));
            assertTrue(messages.list.stream().anyMatch(message -> message.getLevel() == Level.WARN
                    && message.getFormattedMessage().contains("外部面板")));
        } finally {
            configuration.destroy();
            logger.detachAppender(messages);
            messages.stop();
        }
    }

    @Test
    void completedSetupHasNoReminder() {
        EventStreamTokenService tokens = mock(EventStreamTokenService.class);
        when(tokens.list()).thenReturn(List.of(new EventStreamToken("record", "panel", 1, 0)));
        Logger logger = (Logger) LoggerFactory.getLogger(NovaEventStreamConfiguration.class);
        ListAppender<ILoggingEvent> messages = new ListAppender<>();
        messages.start();
        logger.addAppender(messages);
        NovaEventStreamConfiguration configuration = new NovaEventStreamConfiguration();
        try {
            assertNotNull(handler(configuration, tokens, Map.of()));
            assertTrue(messages.list.stream().noneMatch(message -> message.getLevel() == Level.WARN));
        } finally {
            configuration.destroy();
            logger.detachAppender(messages);
            messages.stop();
        }
    }

    @Test
    void inactiveSetupHasReminder() {
        EventStreamTokenService tokens = mock(EventStreamTokenService.class);
        when(tokens.list()).thenReturn(List.of(new EventStreamToken("record", "panel", 1, 2)));
        Logger logger = (Logger) LoggerFactory.getLogger(NovaEventStreamConfiguration.class);
        ListAppender<ILoggingEvent> messages = new ListAppender<>();
        messages.start();
        logger.addAppender(messages);
        NovaEventStreamConfiguration configuration = new NovaEventStreamConfiguration();
        try {
            handler(configuration, tokens, Map.of());
            assertTrue(messages.list.stream().anyMatch(message -> message.getLevel() == Level.WARN
                    && message.getFormattedMessage().contains("外部面板")));
        } finally {
            configuration.destroy();
            logger.detachAppender(messages);
            messages.stop();
        }
    }

    @Test
    void remainingApplicationStarts() {
        Logger logger = (Logger) LoggerFactory.getLogger(NovaEventStreamConfiguration.class);
        ListAppender<ILoggingEvent> messages = new ListAppender<>();
        messages.start();
        logger.addAppender(messages);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("settings", Map.of(
                    EventStreamProperties.PREFIX + ".enabled", true,
                    EventStreamProperties.PREFIX + ".allowed-origins[0]", "https://panel.example/")));
            context.registerBean(EventStreamTokenService.class, () -> mock(EventStreamTokenService.class));
            context.registerBean("console", Object.class, Object::new);
            context.register(NovaEventStreamConfiguration.class);
            assertDoesNotThrow(context::refresh);
            assertNotNull(context.getBean("console"));
            assertTrue(((SimpleUrlHandlerMapping) context.getBean("novaEventStreamHandlerMapping")).getUrlMap().isEmpty());
            assertTrue(context.getBean(NovaEventStreamConfiguration.class).isRunning());
            assertEquals(1, messages.list.stream().filter(message -> message.getLevel() == Level.ERROR
                    && message.getFormattedMessage().contains("allowed-origins")).count());
        } finally {
            logger.detachAppender(messages);
            messages.stop();
        }
    }

    @Test
    void configuredClientFlow() throws Exception {
        clientFlow(true);
    }

    @Test
    void previousConfiguration() throws Exception {
        clientFlow(false);
    }

    private void clientFlow(boolean configured) throws Exception {
        LiveProperties live = new LiveProperties();
        live.setLiveDataPath(directory.resolve("data.json").toString());
        EventStreamTokenService tokens = spy(new EventStreamTokenService(live));
        NovaEventStreamConfiguration configuration = new NovaEventStreamConfiguration();
        try {
            WebSocketHttpRequestHandler handler = handler(configuration, tokens, Map.of(
                    "require-token", configured));
            NovaEventEndpoint endpoint = (NovaEventEndpoint) org.springframework.web.socket.handler.WebSocketHandlerDecorator.unwrap(handler.getWebSocketHandler());
            NovaEventEndpointTest.FakeSession session = new NovaEventEndpointTest.FakeSession("client");
            endpoint.afterConnectionEstablished(session);
            endpoint.handleTextMessage(session, new TextMessage("{\"kind\":\"auth\",\"token\":\""
                    + tokens.issue("panel") + "\"}"));
            verify(tokens).authenticate(anyString(), any());
            assertEquals("hello", session.next().getString("kind"));
        } finally {
            configuration.destroy();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:7830", "https://panel.example", "null", ""})
    void defaultRequestValues(String value) throws Exception {
        check(Map.of(), new String[]{value}, false);
    }

    @Test
    void configuredRequestValues() throws Exception {
        Map<String, Object> values = Map.of("allowed-origins[0]", "https://panel.example",
                "allowed-origins[1]", "http://localhost:7830");
        check(values, new String[]{"https://panel.example"}, true);
        check(values, new String[]{"HTTPS://PANEL.EXAMPLE"}, true);
        check(values, new String[]{"http://localhost:7830"}, true);
        check(values, new String[]{"https://other.example"}, false);
        check(values, new String[]{"https://panel.example", "https://other.example"}, false);
        check(values, new String[]{"https://panel.example/path"}, false);
    }

    @Test
    void defaultPortConfiguration() throws Exception {
        Map<String, Object> values = Map.of("allowed-origins[0]", "https://panel.example:443",
                "allowed-origins[1]", "http://localhost:80", "allowed-origins[2]", "http://[::1]:80");
        check(values, new String[]{"https://panel.example"}, true);
        check(values, new String[]{"http://localhost"}, true);
        check(values, new String[]{"http://[::1]"}, true);
        check(values, new String[]{"http://panel.example"}, false);
        check(values, new String[]{"https://localhost"}, false);
        check(Map.of("allowed-origins[0]", "https://panel.example:80"), new String[]{"https://panel.example"}, false);
        check(Map.of("allowed-origins[0]", "http://panel.example:443"), new String[]{"http://panel.example"}, false);
    }

    @Test
    void defaultPortRequestValues() throws Exception {
        for (String configured : List.of("https://panel.example", "https://panel.example:443")) {
            Map<String, Object> values = Map.of("allowed-origins[0]", configured);
            check(values, new String[]{"https://panel.example"}, true);
            check(values, new String[]{"https://panel.example:443"}, true);
            check(values, new String[]{"HTTPS://Panel.Example:443"}, true);
            check(values, new String[]{"https://panel.example:80"}, false);
            check(values, new String[]{"https://panel.example:8443"}, false);
        }
        for (String configured : List.of("http://localhost", "http://localhost:80")) {
            Map<String, Object> values = Map.of("allowed-origins[0]", configured);
            check(values, new String[]{"http://localhost"}, true);
            check(values, new String[]{"http://localhost:80"}, true);
            check(values, new String[]{"http://localhost:443"}, false);
        }
        Map<String, Object> otherPort = Map.of("allowed-origins[0]", "https://panel.example:8443");
        check(otherPort, new String[]{"https://panel.example:8443"}, true);
        check(otherPort, new String[]{"https://panel.example"}, false);
        check(otherPort, new String[]{"https://panel.example:443"}, false);
        check(Map.of("allowed-origins[0]", "https://panel.example:80"), new String[]{"https://panel.example:80"}, true);
        check(Map.of("allowed-origins[0]", "http://panel.example:443"), new String[]{"http://panel.example:443"}, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "null", "https://*.example", "https://panel.example/path",
            "https://panel.example?view=1", "https://user@panel.example", "file:///panel", "",
            "https://panel.example/", "https://panel.example#view", "https://panel.example:70000"})
    void configurationValues(String value) {
        NovaEventStreamConfiguration configuration = new NovaEventStreamConfiguration();
        try {
            assertNull(handler(configuration, null, Map.of("allowed-origins[0]", value)));
            assertNull(org.springframework.test.util.ReflectionTestUtils.getField(configuration, "endpoint"));
            assertEquals(EventStreamStatus.State.PAUSED, configuration.eventStreamStatus().state());
        } finally {
            configuration.destroy();
        }
    }

    @Test
    void runningState() {
        NovaEventStreamConfiguration configuration = new NovaEventStreamConfiguration();
        try {
            assertNotNull(handler(configuration, null, Map.of("allowed-origins[0]", "https://panel.example")));
            assertEquals(EventStreamStatus.State.RUNNING, configuration.eventStreamStatus().state());
        } finally {
            configuration.destroy();
        }
    }

    @Test
    void offState() {
        NovaEventStreamConfiguration configuration = new NovaEventStreamConfiguration();
        EventStreamProperties properties = configuration.eventStreamProperties(new StandardEnvironment());
        assertTrue(((SimpleUrlHandlerMapping) configuration.novaEventStreamHandlerMapping(properties,
                new NovaEventStream(10), mock(EventStreamTokenService.class))).getUrlMap().isEmpty());
        assertEquals(EventStreamStatus.State.OFF, configuration.eventStreamStatus().state());
    }

    @Test
    void unlistedOriginNote() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(EventHandshakeOrigins.class);
        ListAppender<ILoggingEvent> messages = new ListAppender<>();
        messages.start();
        logger.addAppender(messages);
        NovaEventStreamConfiguration configuration = new NovaEventStreamConfiguration();
        try {
            WebSocketHttpRequestHandler handler = handler(configuration, null,
                    Map.of("allowed-origins[0]", "https://panel.example"));
            assertFalse(accepted(handler, "https://other.example"));
            assertFalse(accepted(handler, "https://other.example"));
            assertEquals("https://other.example", configuration.eventStreamStatus().unlistedOrigin());
            assertFalse(accepted(handler, "http://localhost:7830"));
            assertEquals("http://localhost:7830", configuration.eventStreamStatus().unlistedOrigin());

            List<String> notes = messages.list.stream().filter(message -> message.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage).toList();
            assertEquals(2, notes.size(), String.join("\n", notes));
            assertTrue(notes.get(0).contains("https://other.example"));
            assertTrue(notes.get(1).contains("http://localhost:7830"));
            for (String note : notes) {
                assertTrue(note.contains("未登记"));
                assertTrue(note.contains("事件输出 · 页面来源"));
                assertTrue(note.contains("重启"));
            }
        } finally {
            configuration.destroy();
            logger.detachAppender(messages);
            messages.stop();
        }
    }

    @Test
    void noUnlistedOriginNote() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(EventHandshakeOrigins.class);
        ListAppender<ILoggingEvent> messages = new ListAppender<>();
        messages.start();
        logger.addAppender(messages);
        NovaEventStreamConfiguration configuration = new NovaEventStreamConfiguration();
        try {
            WebSocketHttpRequestHandler handler = handler(configuration, null,
                    Map.of("allowed-origins[0]", "https://panel.example"));
            assertTrue(accepted(handler, "https://panel.example"));
            assertTrue(accepted(handler));
            assertFalse(accepted(handler, "null"));
            assertNull(configuration.eventStreamStatus().unlistedOrigin());
            assertTrue(messages.list.isEmpty(), messages.list.toString());
        } finally {
            configuration.destroy();
            logger.detachAppender(messages);
            messages.stop();
        }
    }

    @Test
    void unlistedOriginNoteLimit() {
        EventStreamStatus status = new EventStreamStatus();
        for (int i = 0; i < EventStreamStatus.ORIGIN_LIMIT; i++) {
            assertTrue(status.unlisted("https://panel" + i + ".example"));
        }
        assertFalse(status.unlisted("https://more.example"));
        assertEquals("https://more.example", status.unlistedOrigin());
    }

    @Test
    void unlistedOriginNoteLimitUnderConcurrentRequests() throws Exception {
        int requests = 64;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        try {
            for (int trial = 0; trial < 300; trial++) {
                EventStreamStatus status = new EventStreamStatus();
                for (int i = 0; i < EventStreamStatus.ORIGIN_LIMIT - 1; i++) {
                    status.unlisted("https://panel" + i + ".example");
                }
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Boolean>> notes = new ArrayList<>();
                for (int i = 0; i < requests; i++) {
                    String origin = "https://trial" + trial + "-" + i + ".example";
                    notes.add(pool.submit(() -> {
                        start.await();
                        return status.unlisted(origin);
                    }));
                }
                start.countDown();
                int logged = 0;
                for (Future<Boolean> note : notes) {
                    if (note.get(30, TimeUnit.SECONDS)) {
                        logged++;
                    }
                }
                Set<?> remembered = (Set<?>) org.springframework.test.util.ReflectionTestUtils
                        .getField(status, "loggedOrigins");
                assertEquals(1, logged, "trial " + trial);
                assertEquals(EventStreamStatus.ORIGIN_LIMIT, remembered.size(), "trial " + trial);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void longOriginNote() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(EventHandshakeOrigins.class);
        ListAppender<ILoggingEvent> messages = new ListAppender<>();
        messages.start();
        logger.addAppender(messages);
        NovaEventStreamConfiguration configuration = new NovaEventStreamConfiguration();
        try {
            WebSocketHttpRequestHandler handler = handler(configuration, null,
                    Map.of("allowed-origins[0]", "https://panel.example"));
            String longOrigin = "https://" + "a".repeat(4096) + ".example";
            for (int i = 0; i < 3; i++) {
                assertFalse(accepted(handler, longOrigin + i));
            }
            assertFalse(accepted(handler, "https://other.example:" + "0".repeat(4096) + "8443"));
            assertFalse(accepted(handler, "https://panel.example:" + "0".repeat(4096) + "443"));
            assertTrue(messages.list.isEmpty(), messages.list.size() + " messages");
            assertNull(configuration.eventStreamStatus().unlistedOrigin());

            String host = (("a".repeat(62) + ".").repeat(3) + "b".repeat(55) + ".example");
            String origin = "https://" + host;
            assertEquals(260, origin.length());
            assertFalse(accepted(handler, origin));
            assertEquals(1, messages.list.size());
            assertTrue(messages.list.get(0).getFormattedMessage().contains(origin));
            assertEquals(origin, configuration.eventStreamStatus().unlistedOrigin());
        } finally {
            configuration.destroy();
            logger.detachAppender(messages);
            messages.stop();
        }
        NovaEventStreamConfiguration paused = new NovaEventStreamConfiguration();
        try {
            assertNull(handler(paused, null, Map.of("allowed-origins[0]", "https://" + "a".repeat(254))));
            assertEquals(EventStreamStatus.State.PAUSED, paused.eventStreamStatus().state());
        } finally {
            paused.destroy();
        }
    }

    @Test
    void nativeRequest() throws Exception {
        check(Map.of(), new String[0], true);
    }

    private void check(Map<String, Object> values, String[] origins, boolean expected) throws Exception {
        NovaEventStreamConfiguration configuration = new NovaEventStreamConfiguration();
        try {
            WebSocketHttpRequestHandler handler = handler(configuration, null, values);
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertEquals(expected, accepted(handler, response, origins));
            if (!expected) {
                assertEquals(403, response.getStatus());
            }
        } finally {
            configuration.destroy();
        }
    }

    private static boolean accepted(WebSocketHttpRequestHandler handler, String... origins) throws Exception {
        return accepted(handler, new MockHttpServletResponse(), origins);
    }

    private static boolean accepted(WebSocketHttpRequestHandler handler, MockHttpServletResponse response,
                                    String... origins) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/nova/events");
        request.setRemoteAddr("127.0.0.1");
        for (String origin : origins) {
            request.addHeader("Origin", origin);
        }
        for (var interceptor : handler.getHandshakeInterceptors()) {
            if (!interceptor.beforeHandshake(new ServletServerHttpRequest(request),
                    new ServletServerHttpResponse(response), handler.getWebSocketHandler(), new HashMap<>())) {
                return false;
            }
        }
        return true;
    }

    static WebSocketHttpRequestHandler handler(NovaEventStreamConfiguration configuration,
                                               EventStreamTokenService tokens, Map<String, Object> values) {
        Map<String, Object> source = new HashMap<>();
        source.put(EventStreamProperties.PREFIX + ".enabled", true);
        values.forEach((key, value) -> source.put(EventStreamProperties.PREFIX + "." + key, value));
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("settings", source));
        EventStreamProperties properties = configuration.eventStreamProperties(environment);
        SimpleUrlHandlerMapping mapping = (SimpleUrlHandlerMapping) configuration.novaEventStreamHandlerMapping(
                properties, new NovaEventStream(10), tokens == null ? mock(EventStreamTokenService.class) : tokens);
        return (WebSocketHttpRequestHandler) mapping.getUrlMap().get(properties.getPath());
    }
}
