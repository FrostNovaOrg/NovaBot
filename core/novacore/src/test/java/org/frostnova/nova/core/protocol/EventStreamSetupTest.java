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
import java.util.HashMap;
import java.util.Map;

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

    @ParameterizedTest
    @ValueSource(strings = {"*", "null", "https://*.example", "https://panel.example/path",
            "https://panel.example?view=1", "https://user@panel.example", "file:///panel", "",
            "https://panel.example/", "https://panel.example#view", "https://panel.example:70000"})
    void configurationValues(String value) {
        NovaEventStreamConfiguration configuration = new NovaEventStreamConfiguration();
        try {
            assertNull(handler(configuration, null, Map.of("allowed-origins[0]", value)));
            assertNull(org.springframework.test.util.ReflectionTestUtils.getField(configuration, "endpoint"));
        } finally {
            configuration.destroy();
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
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/nova/events");
            request.setRemoteAddr("127.0.0.1");
            for (String origin : origins) {
                request.addHeader("Origin", origin);
            }
            MockHttpServletResponse response = new MockHttpServletResponse();
            boolean accepted = true;
            for (var interceptor : handler.getHandshakeInterceptors()) {
                if (!interceptor.beforeHandshake(new ServletServerHttpRequest(request),
                        new ServletServerHttpResponse(response), handler.getWebSocketHandler(), new HashMap<>())) {
                    accepted = false;
                    break;
                }
            }
            assertEquals(expected, accepted);
            if (!expected) {
                assertEquals(403, response.getStatus());
            }
        } finally {
            configuration.destroy();
        }
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
