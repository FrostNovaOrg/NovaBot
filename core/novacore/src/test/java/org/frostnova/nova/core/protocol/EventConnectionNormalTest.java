package org.frostnova.nova.core.protocol;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.properties.LiveProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class EventConnectionNormalTest {
    @Test
    void localPanels(@TempDir Path directory) throws Exception {
        NovaEventStream stream = new NovaEventStream(2000);
        EventStreamTokenService tokens = tokens(directory);
        String token = tokens.issue("panel");
        NovaEventEndpoint endpoint = new NovaEventEndpoint(stream, tokens);
        AtomicInteger[] delivered = new AtomicInteger[8];
        for (int i = 0; i < delivered.length; i++) delivered[i] = new AtomicInteger();
        try {
            for (int i = 0; i < 8; i++) {
                AtomicInteger received = delivered[i];
                var session = new NovaEventEndpointTest.FakeSession("panel-" + i) {
                    @Override public void sendMessage(WebSocketMessage<?> frame) {
                        if (((TextMessage) frame).getPayload().contains("\"seq\":")) received.incrementAndGet();
                    }
                };
                endpoint.afterConnectionEstablished(session);
                endpoint.handleTextMessage(session, new TextMessage("{\"kind\":\"auth\",\"token\":\"" + token + "\"}"));
                endpoint.handleTextMessage(session, new TextMessage("{\"kind\":\"resume\",\"data\":{\"fromSeq\":0}}"));
            }
            long[] samples = new long[7];
            for (int round = -2; round < samples.length; round++) {
                long elapsed = 0;
                for (int batch = 0; batch < 100; batch++) {
                    long start = System.nanoTime();
                    for (int i = 0; i < 100; i++) {
                        JSONObject frame = new JSONObject();
                        frame.put("kind", "danmaku");
                        frame.put("data", "panel");
                        stream.publish(frame);
                    }
                    elapsed += System.nanoTime() - start;
                    int expected = (round + 2) * 10000 + (batch + 1) * 100;
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    for (AtomicInteger received : delivered) {
                        while (received.get() < expected && System.nanoTime() < until) Thread.sleep(1);
                        assertEquals(expected, received.get());
                    }
                }
                if (round >= 0) samples[round] = elapsed / 10000;
            }
            java.util.Arrays.sort(samples);
            System.out.printf("PANEL_PUBLISH_NS median=%d min=%d max=%d clients=8 events=10000 rounds=7%n",
                    samples[3], samples[0], samples[6]);
            assertEquals(8, endpoint.getClientCount());
        } finally {
            endpoint.shutdown();
        }
    }

    @Test
    void reconnectAndResume(@TempDir Path directory) throws Exception {
        EventStreamTokenService tokens = tokens(directory);
        String token = tokens.issue("panel");
        var endpoint = new NovaEventEndpoint(new NovaEventStream(64), tokens);
        long[] samples = new long[20];
        try {
            for (int i = 0; i < 20; i++) {
                var session = new NovaEventEndpointTest.FakeSession("panel-" + i);
                long start = System.nanoTime();
                endpoint.afterConnectionEstablished(session);
                endpoint.handleTextMessage(session, new TextMessage("{\"kind\":\"auth\",\"token\":\"" + token + "\"}"));
                assertEquals("hello", session.next().getString("kind"));
                endpoint.handleTextMessage(session, new TextMessage("{\"kind\":\"resume\",\"data\":{\"fromSeq\":0}}"));
                endpoint.handleTextMessage(session, new TextMessage("{\"kind\":\"pong\"}"));
                endpoint.afterConnectionClosed(session, CloseStatus.NORMAL);
                assertEquals(0, endpoint.getClientCount());
                samples[i] = System.nanoTime() - start;
            }
            java.util.Arrays.sort(samples);
            System.out.printf("PANEL_RECONNECT_NS median=%d min=%d max=%d rounds=20%n",
                    samples[10], samples[0], samples[19]);
        } finally {
            endpoint.shutdown();
        }
    }

    private EventStreamTokenService tokens(Path directory) {
        LiveProperties properties = new LiveProperties();
        properties.setLiveDataPath(directory.resolve("data.json").toString());
        return new EventStreamTokenService(properties);
    }
    @Test
    void completedPanelWritesUseDebug() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(NovaEventEndpoint.class);
        var messages = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        messages.start();
        logger.addAppender(messages);
        var endpoint = new NovaEventEndpoint(new NovaEventStream(10));
        try {
            var session = new NovaEventEndpointTest.FakeSession("panel") {
                @Override public void sendMessage(WebSocketMessage<?> frame) {
                    throw new IllegalStateException("panel closed");
                }
                @Override public void close(CloseStatus status) {
                    endpoint.afterConnectionClosed(this, status);
                    throw new IllegalStateException("panel closed");
                }
            };
            endpoint.afterConnectionEstablished(session);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            var senders = (java.util.concurrent.ThreadPoolExecutor)
                    org.springframework.test.util.ReflectionTestUtils.getField(endpoint, "senders");
            while (senders.getCompletedTaskCount() == 0 && System.nanoTime() < until) Thread.sleep(5);
            assertTrue(senders.getCompletedTaskCount() > 0);
            assertEquals(0, endpoint.getClientCount());
            assertEquals(0, messages.list.stream().filter(message -> message.getFormattedMessage()
                    .contains("io-timeout-ms")).count());
        } finally {
            endpoint.shutdown();
            logger.detachAppender(messages);
            messages.stop();
        }
    }

}
