package com.starlwr.bot.core.protocol;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.URI;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 握手里不许带凭据
 * <p>
 * 口令改走连接后的首帧之后，这道拦截守的不再是「有没有口令」，
 * 而是<b>有没有人还在把口令往握手里塞</b>。
 * <p>
 * ⚠️ <b>为什么是拒收而不是忽略。</b> 忽略等于留了一条看起来还能用的旧通道：
 * 客户端照旧把口令塞进子协议、口令照旧落进代理日志，而它自己因为随后发了认证帧
 * 所以一切正常，<b>没有任何人会发现</b>。
 */
@DisplayName("握手里不许带凭据")
class NoCredentialsInHandshakeTest {
    private NoCredentialsInHandshake interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new NoCredentialsInHandshake();
    }

    @Test
    @DisplayName("尺子先过阳性对照：干净的握手必须放行")
    void allowsCleanHandshake() {
        assertTrue(handshake(new HttpHeaders()),
                "阳性对照: 若这里也拒, 下面几条「拒住了」就都不成立");
    }

    @Test
    @DisplayName("🔴 子协议里带口令一律拒 —— 不留旧通道")
    void rejectsLegacySubprotocol() {
        MockHttpServletResponse raw = new MockHttpServletResponse();

        assertFalse(handshake(subprotocol("nova.token.随便什么"), raw));
        assertEquals(400, raw.getStatus(), "要给出明确状态码, 让还在用旧通道的客户端知道该改了");
    }

    @Test
    @DisplayName("🔴 逗号分隔里混着旧前缀也要认出来")
    void rejectsLegacySubprotocolAmongOthers() {
        assertFalse(handshake(subprotocol("nova.v2, nova.token.口令, 其他")));
    }

    @Test
    @DisplayName("🔴 Authorization 头同样拒")
    void rejectsAuthorizationHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer 随便什么");

        assertFalse(handshake(headers),
                "多数代理会遮蔽这个头, 但「多数」不是「全部」; 而且规矩要能一句话讲清: 握手里不许有凭据");
    }

    @Test
    @DisplayName("不带旧前缀的普通子协议照常放行")
    void allowsUnrelatedSubprotocol() {
        assertTrue(handshake(subprotocol("nova.v2, 其他")),
                "拦的是携带凭据这个动作, 不是拦子协议本身");
    }

    private HttpHeaders subprotocol(String value) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Sec-WebSocket-Protocol", value);
        return headers;
    }

    private boolean handshake(HttpHeaders headers) {
        return handshake(headers, new MockHttpServletResponse());
    }

    private boolean handshake(HttpHeaders headers, MockHttpServletResponse raw) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getHeaders()).thenReturn(headers);
        when(request.getURI()).thenReturn(URI.create("http://127.0.0.1:7827/nova/events"));

        ServerHttpResponse response = new ServletServerHttpResponse(raw);
        return interceptor.beforeHandshake(request, response, null, new HashMap<>());
    }
}
