package com.starlwr.bot.bilibili.protocol;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 协议服务端的握手、回补与心跳测试
 * <p>
 * 这里盯的是<b>顺序</b>：协议规定「服务端补发缺口后转入实时推送」，
 * 一旦顺序反了，回补的旧消息会排在新消息后面，seq 就不再递增，
 * 而下游的去重与断线回补全靠 seq 递增这一条。
 */
@DisplayName("协议服务端握手与回补")
class NovaEventEndpointTest {
    /**
     * 收集发送内容的假会话
     */
    static class FakeSession implements WebSocketSession {
        private final String id;

        private final LinkedBlockingQueue<String> sent = new LinkedBlockingQueue<>();

        volatile CloseStatus closedWith;

        FakeSession(String id) {
            this.id = id;
        }

        /**
         * 取下一条消息
         * @return 消息，超时未收到时为 null
         */
        JSONObject next() throws InterruptedException {
            String json = sent.poll(3, TimeUnit.SECONDS);
            return json == null ? null : JSON.parseObject(json);
        }

        /**
         * 已经下行过多少帧
         * <p>
         * 给「认证之前一个字节都不下行」那一格用：它要断言的是**零**，
         * 而 {@link #next()} 为了等消息会先空等三秒——断言零不该靠空等。
         */
        int sentCount() {
            return sent.size();
        }

        /**
         * 取接下来的若干条消息
         */
        List<JSONObject> next(int count) throws InterruptedException {
            List<JSONObject> messages = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                JSONObject message = next();
                assertNotNull(message, "只收到 " + messages.size() + " 条, 期望 " + count + ": " + messages);
                messages.add(message);
            }
            return messages;
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) {
            sent.add(((TextMessage) message).getPayload());
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public void close() {
            close(CloseStatus.NORMAL);
        }

        @Override
        public void close(CloseStatus status) {
            closedWith = status;
        }

        @Override
        public boolean isOpen() {
            return closedWith == null;
        }

        @Override
        public URI getUri() {
            return URI.create("ws://127.0.0.1/nova/events");
        }

        @Override
        public org.springframework.http.HttpHeaders getHandshakeHeaders() {
            return new org.springframework.http.HttpHeaders();
        }

        @Override
        public Map<String, Object> getAttributes() {
            return new HashMap<>();
        }

        @Override
        public Principal getPrincipal() {
            return null;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 7827);
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 54321);
        }

        @Override
        public String getAcceptedProtocol() {
            return null;
        }

        @Override
        public void setTextMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getTextMessageSizeLimit() {
            return 0;
        }

        @Override
        public void setBinaryMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getBinaryMessageSizeLimit() {
            return 0;
        }

        @Override
        public List<WebSocketExtension> getExtensions() {
            return List.of();
        }
    }

    private NovaEventEndpoint endpoint;

    @AfterEach
    void tearDown() {
        if (endpoint != null) {
            endpoint.shutdown();
        }
    }

    private static JSONObject envelope() {
        JSONObject j = new JSONObject();
        j.put("v", NovaEventMapper.PROTOCOL_VERSION);
        j.put("kind", "danmaku");
        j.put("ts", 1786111565000L);
        j.put("room", 10000);
        return j;
    }

    private static TextMessage resume(long fromSeq) {
        JSONObject data = new JSONObject();
        data.put("fromSeq", fromSeq);

        JSONObject request = new JSONObject();
        request.put("v", NovaEventMapper.PROTOCOL_VERSION);
        request.put("kind", "resume");
        request.put("data", data);
        return new TextMessage(request.toJSONString());
    }

    @Test
    @DisplayName("握手先发 hello，内容符合协议")
    void helloIsSentFirst() throws Exception {
        NovaEventStream stream = new NovaEventStream(100);
        stream.publish(envelope());
        stream.publish(envelope());
        endpoint = new NovaEventEndpoint(stream);

        FakeSession session = new FakeSession("c1");
        endpoint.afterConnectionEstablished(session);

        JSONObject hello = session.next();
        assertNotNull(hello);
        assertEquals("hello", hello.getString("kind"));
        assertEquals(List.of(), NovaProtocolSchema.violations(hello));

        JSONObject data = hello.getJSONObject("data");
        assertEquals(stream.getSessionId(), data.getString("sessionId"));
        assertEquals("novabot", data.getString("source"));
        assertEquals(2L, data.getLongValue("lastSeq"));
        assertEquals(1L, data.getLongValue("bufferedFrom"));
    }

    @Test
    @DisplayName("⚠️ 握手后到 resume 之前不推实时流，否则 seq 就不再递增了")
    void liveStreamWaitsForResumeWindow() throws Exception {
        NovaEventStream stream = new NovaEventStream(100);
        stream.publish(envelope());
        endpoint = new NovaEventEndpoint(stream);

        FakeSession session = new FakeSession("c1");
        endpoint.afterConnectionEstablished(session);
        assertEquals("hello", session.next().getString("kind"));

        // 窗口期内产生的消息此刻不该发出去
        stream.publish(envelope());
        assertNull(session.sent.poll(300, TimeUnit.MILLISECONDS), "窗口期内不该有实时消息");

        // 客户端从 1 开始回补，缺口是 2
        endpoint.handleTextMessage(session, resume(1));

        JSONObject first = session.next();
        assertNotNull(first);
        assertEquals(2L, first.getLongValue("seq"), "回补应从缺口的第一条开始");
    }

    @Test
    @DisplayName("回补之后无缝转入实时流，seq 连续")
    void resumeThenLive() throws Exception {
        NovaEventStream stream = new NovaEventStream(100);
        for (int i = 0; i < 4; i++) {
            stream.publish(envelope());
        }
        endpoint = new NovaEventEndpoint(stream);

        FakeSession session = new FakeSession("c1");
        endpoint.afterConnectionEstablished(session);
        assertEquals("hello", session.next().getString("kind"));

        endpoint.handleTextMessage(session, resume(1));
        stream.publish(envelope());

        List<Long> seqs = session.next(4).stream().map(m -> m.getLongValue("seq")).toList();
        assertEquals(List.of(2L, 3L, 4L, 5L), seqs);
    }

    @Test
    @DisplayName("窗口到期后自动转入实时流，窗口期内的消息不会丢")
    void graceWindowExpiryGoesLive() throws Exception {
        NovaEventStream stream = new NovaEventStream(100);
        endpoint = new NovaEventEndpoint(stream);

        FakeSession session = new FakeSession("c1");
        endpoint.afterConnectionEstablished(session);
        assertEquals("hello", session.next().getString("kind"));

        // 这条产生在窗口期内。转入实时流时是从握手那一刻的序号接着补的，所以它必须补上
        stream.publish(envelope());

        Thread.sleep(NovaEventEndpoint.RESUME_GRACE_MS + 500);
        stream.publish(envelope());

        List<Long> seqs = session.next(2).stream().map(m -> m.getLongValue("seq")).toList();
        assertEquals(List.of(1L, 2L), seqs, "窗口期内产生的第 1 条不能丢");
    }

    @Test
    @DisplayName("⚠️ 缺口超出缓冲窗口时重发 hello，绝不静默地少发一段")
    void outOfWindowResumeResendsHello() throws Exception {
        NovaEventStream stream = new NovaEventStream(3);
        for (int i = 0; i < 8; i++) {
            stream.publish(envelope());
        }
        endpoint = new NovaEventEndpoint(stream);

        FakeSession session = new FakeSession("c1");
        endpoint.afterConnectionEstablished(session);
        assertEquals("hello", session.next().getString("kind"));

        // 缓冲只剩 6、7、8，客户端却想从 2 之后开始补
        endpoint.handleTextMessage(session, resume(2));

        JSONObject again = session.next();
        assertNotNull(again);
        assertEquals("hello", again.getString("kind"), "补不上就要让客户端重来, 而不是发一段残缺的");
        assertEquals(6L, again.getJSONObject("data").getLongValue("bufferedFrom"));

        stream.publish(envelope());
        JSONObject live = session.next();
        assertNotNull(live);
        assertEquals(9L, live.getLongValue("seq"), "重发 hello 后应从当下继续推");
    }

    @Test
    @DisplayName("转入实时流之后才发 resume 的，重发一份 hello 让它自己判断")
    void lateResumeGetsHello() throws Exception {
        NovaEventStream stream = new NovaEventStream(100);
        endpoint = new NovaEventEndpoint(stream);

        FakeSession session = new FakeSession("c1");
        endpoint.afterConnectionEstablished(session);
        assertEquals("hello", session.next().getString("kind"));

        Thread.sleep(NovaEventEndpoint.RESUME_GRACE_MS + 500);
        endpoint.handleTextMessage(session, resume(0));

        JSONObject again = session.next();
        assertNotNull(again);
        assertEquals("hello", again.getString("kind"));
    }

    @Test
    @DisplayName("不认识的消息忽略而不断连，协议如此规定")
    void unknownMessageIsIgnored() throws Exception {
        NovaEventStream stream = new NovaEventStream(100);
        endpoint = new NovaEventEndpoint(stream);

        FakeSession session = new FakeSession("c1");
        endpoint.afterConnectionEstablished(session);
        assertEquals("hello", session.next().getString("kind"));

        endpoint.handleTextMessage(session, new TextMessage("{\"kind\":\"没听说过\"}"));
        endpoint.handleTextMessage(session, new TextMessage("这根本不是 JSON"));
        endpoint.handleTextMessage(session, new TextMessage("{\"kind\":\"pong\"}"));

        assertTrue(session.isOpen());
        assertEquals(1, endpoint.getClientCount());
    }

    @Test
    @DisplayName("断开后不再收到消息")
    void closedClientStopsReceiving() throws Exception {
        NovaEventStream stream = new NovaEventStream(100);
        endpoint = new NovaEventEndpoint(stream);

        FakeSession session = new FakeSession("c1");
        endpoint.afterConnectionEstablished(session);
        assertEquals("hello", session.next().getString("kind"));

        Thread.sleep(NovaEventEndpoint.RESUME_GRACE_MS + 500);
        endpoint.afterConnectionClosed(session, CloseStatus.NORMAL);

        stream.publish(envelope());
        assertNull(session.sent.poll(300, TimeUnit.MILLISECONDS));
        assertEquals(0, endpoint.getClientCount());
        assertEquals(0, stream.getSubscriberCount());
    }

    @Test
    @DisplayName("两个客户端各自握手，收到同一批 seq")
    void twoClientsSeeTheSameStream() throws Exception {
        NovaEventStream stream = new NovaEventStream(100);
        endpoint = new NovaEventEndpoint(stream);

        FakeSession one = new FakeSession("c1");
        FakeSession another = new FakeSession("c2");
        endpoint.afterConnectionEstablished(one);
        endpoint.afterConnectionEstablished(another);
        assertEquals("hello", one.next().getString("kind"));
        assertEquals("hello", another.next().getString("kind"));

        Thread.sleep(NovaEventEndpoint.RESUME_GRACE_MS + 500);
        stream.publish(envelope());
        stream.publish(envelope());

        assertEquals(List.of(1L, 2L), one.next(2).stream().map(m -> m.getLongValue("seq")).toList());
        assertEquals(List.of(1L, 2L), another.next(2).stream().map(m -> m.getLongValue("seq")).toList());
    }

    @Test
    @DisplayName("hello 的 capabilities 与实际会推的 kind 一致")
    void capabilitiesMatchWhatWeActuallySend() throws Exception {
        NovaEventStream stream = new NovaEventStream(100);
        endpoint = new NovaEventEndpoint(stream);

        FakeSession session = new FakeSession("c1");
        endpoint.afterConnectionEstablished(session);

        List<String> capabilities = session.next().getJSONObject("data")
                .getJSONArray("capabilities").toList(String.class);

        // 缺项的语义是「展示层隐藏对应 UI」。列了却不推，下游会一直等一个永远不来的东西
        assertEquals(List.of("danmaku", "superchat", "gift", "guard",
                "enter", "follow", "share", "like", "room_stat"), capabilities);
    }

    /**
     * 端点是 {@link WebSocketHandler} 的实现，这条只是把类型关系钉住，
     * 免得日后有人把它改成别的接口而装配处默默地不再生效
     */
    @Test
    @DisplayName("端点是标准的 WebSocketHandler")
    void isWebSocketHandler() {
        endpoint = new NovaEventEndpoint(new NovaEventStream(10));
        assertInstanceOf(WebSocketHandler.class, endpoint);
    }
}
