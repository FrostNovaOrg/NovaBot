package com.starlwr.bot.bilibili.protocol;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.model.EventStreamToken;
import com.starlwr.bot.core.service.EventStreamTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 事件流只读口令拦截测试
 * <p>
 * 这道拦截是<b>反代上线后唯一真正的门</b>：反代与本程序同机，
 * 转发过来的连接源地址就是回环，「只接受本机连接」届时拦不住任何人。
 */
@DisplayName("事件流只读口令拦截")
class ReadOnlyTokenRequiredTest {
    @TempDir
    Path dir;

    private EventStreamTokenService tokens;

    private ReadOnlyTokenRequired interceptor;

    @BeforeEach
    void setUp() {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        tokens = new EventStreamTokenService(properties);
        interceptor = new ReadOnlyTokenRequired(tokens);
    }

    @Test
    @DisplayName("① 子协议里带有效口令应放行")
    void acceptsTokenInSubprotocol() {
        String token = tokens.issue("面板-甲");

        assertTrue(handshake(subprotocol("nova.token." + token)));
    }

    @Test
    @DisplayName("① 无效口令应拒，并回 401")
    void rejectsInvalidToken() {
        tokens.issue("面板-甲");

        MockHttpServletResponse raw = new MockHttpServletResponse();
        assertFalse(handshake(subprotocol("nova.token.乱填的"), raw));
        assertEquals(401, raw.getStatus(), "要回 401，让面板分得清「口令不对」和「连不上」");
    }

    @Test
    @DisplayName("⚠️ ① 已吊销的口令必须拒——它曾经有效")
    void rejectsRevokedToken() {
        String token = tokens.issue("面板-甲");
        assertTrue(handshake(subprotocol("nova.token." + token)), "吊销前该通");

        EventStreamToken record = tokens.list().get(0);
        tokens.revoke(tokens.fingerprintOf(record));

        assertFalse(handshake(subprotocol("nova.token." + token)));
    }

    @Test
    @DisplayName("没出示口令应拒")
    void rejectsMissingToken() {
        tokens.issue("面板-甲");

        assertFalse(handshake(new HttpHeaders()));
    }

    @Test
    @DisplayName("🔴 ② 守卫：口令不从查询参数取——查询串会进反代访问日志与浏览器历史")
    void ignoresQueryParameter() {
        String token = tokens.issue("面板-甲");

        // 把正确的口令放进查询参数、请求头里什么都不放。
        // 若实现「顺手」支持了 ?token=，这条会通——那正是要防的：
        // 口令一旦进查询串，就被抄进了反代日志、浏览器历史与 Referer 三个我们管不到的地方
        assertFalse(handshake(new HttpHeaders(), "/nova/events?token=" + token),
                "查询参数里的口令一律不认");
    }

    @Test
    @DisplayName("非浏览器客户端可用 Authorization: Bearer")
    void acceptsBearerHeader() {
        String token = tokens.issue("采集脚本");

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);

        assertTrue(handshake(headers));
    }

    @Test
    @DisplayName("一个子协议头里逗号分隔写了多个时也要认得出来")
    void findsTokenAmongMultipleSubprotocols() {
        String token = tokens.issue("面板-甲");

        assertTrue(handshake(subprotocol("nova.v2, nova.token." + token + ", 其他")));
    }

    @Test
    @DisplayName("尺子先过阳性对照：这套 handshake 桩真的分得出通与不通")
    void handshakeStubIsDiscriminating() {
        // 上面多条用例靠 assertFalse(handshake(...)) 证明「拒住了」。
        // 若这个桩恒返回 false，它们会全部假绿
        String token = tokens.issue("面板-甲");

        assertTrue(handshake(subprotocol("nova.token." + token)), "阳性对照：该通的必须通");
        assertFalse(handshake(subprotocol("nova.token.x")));
    }

    private HttpHeaders subprotocol(String value) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Sec-WebSocket-Protocol", value);
        return headers;
    }

    private boolean handshake(HttpHeaders headers) {
        return handshake(headers, "/nova/events");
    }

    private boolean handshake(HttpHeaders headers, String uri) {
        return handshake(headers, uri, new MockHttpServletResponse());
    }

    private boolean handshake(HttpHeaders headers, MockHttpServletResponse raw) {
        return handshake(headers, "/nova/events", raw);
    }

    private boolean handshake(HttpHeaders headers, String uri, MockHttpServletResponse raw) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getHeaders()).thenReturn(headers);
        when(request.getURI()).thenReturn(URI.create("http://127.0.0.1:7827" + uri));

        ServerHttpResponse response = new ServletServerHttpResponse(raw);
        return interceptor.beforeHandshake(request, response, null, new HashMap<>());
    }
}
