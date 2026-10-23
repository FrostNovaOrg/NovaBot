package org.frostnova.nova.core.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(40)
class EventConnectionServerTest {
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(NovaEventStreamConfiguration.class)
    static class ServerConfiguration {
        @Bean
        EventStreamTokenService tokens() {
            EventStreamTokenService service = mock(EventStreamTokenService.class);
            doAnswer(call -> {
                ((Consumer<String>) call.getArgument(1)).accept("panel");
                return EventStreamTokenService.Verdict.OK;
            }).when(service).authenticate(any(), any());
            return service;
        }
    }

    static final class Panel implements WebSocket.Listener {
        final LinkedBlockingQueue<String> frames = new LinkedBlockingQueue<>();
        final StringBuilder text = new StringBuilder();
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            text.append(data);
            if (last) { frames.add(text.toString()); text.setLength(0); }
            socket.request(1);
            return null;
        }
    }

    @Test
    void localPanelConnection() throws Exception {
        LoopbackPort.assumeAllowed();
        try (var context = new SpringApplicationBuilder(ServerConfiguration.class).properties(
                "server.address=127.0.0.1", "server.port=0", "spring.main.banner-mode=off",
                "novabot.core.event-stream.enabled=true", "novabot.core.event-stream.max-connections=1").run()) {
            int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
            URI uri = URI.create("ws://127.0.0.1:" + port + "/nova/events");
            var endpoint = (NovaEventEndpoint) org.springframework.test.util.ReflectionTestUtils.getField(
                    context.getBean(NovaEventStreamConfiguration.class), "endpoint");
            HttpClient client = HttpClient.newHttpClient();
            Panel panel = new Panel();
            WebSocket first = client.newWebSocketBuilder().buildAsync(uri, panel).get(10, TimeUnit.SECONDS);
            try {
                first.sendText("{\"kind\":\"auth\",\"token\":\"panel\"}", true).get(10, TimeUnit.SECONDS);
                String hello = panel.frames.poll(10, TimeUnit.SECONDS);
                assertNotNull(hello);
                assertTrue(hello.contains("\"hello\""));
                var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> client.newWebSocketBuilder().buildAsync(uri, new Panel()).get(10, TimeUnit.SECONDS));
                var response = ((WebSocketHandshakeException) failure.getCause()).getResponse();
                assertEquals(503, response.statusCode());
                assertEquals("5", response.headers().firstValue("Retry-After").orElseThrow());
                first.sendClose(WebSocket.NORMAL_CLOSURE, "panel closed").get(10, TimeUnit.SECONDS);
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (endpoint.getClientCount() != 0 && System.nanoTime() < until) Thread.sleep(5);
                assertEquals(0, endpoint.getClientCount());
                WebSocket second = client.newWebSocketBuilder().buildAsync(uri, new Panel()).get(10, TimeUnit.SECONDS);
                second.sendClose(WebSocket.NORMAL_CLOSURE, "panel closed").get(10, TimeUnit.SECONDS);
            } finally {
                first.abort();
            }
        }
    }
}
