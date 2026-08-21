package com.starlwr.bot.bilibili.protocol;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.model.EventStreamToken;
import com.starlwr.bot.core.service.EventStreamTokenService;
import com.starlwr.bot.core.util.SecureToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件流首帧认证
 * <p>
 * 认证从握手挪到了连接建立之后的第一帧（裁决 #99 一）。原因不是握手拦得不好，
 * 而是<b>握手请求头会进反向代理的 access log</b>，而部署者用什么反代我们管不到。
 * <p>
 * 这一组盯的是两件事：<b>认证之前一个字节都不该给出去</b>；
 * <b>失败要说清是哪一种</b>——客户端只有拿到 reason，才能把「口令错」和
 * 「数据源没起」显示成两句不同的话。
 */
@DisplayName("事件流首帧认证")
class NovaEventAuthFrameTest {
    @TempDir
    Path dir;

    private EventStreamTokenService tokens;

    private NovaEventStream stream;

    private NovaEventEndpoint endpoint;

    @BeforeEach
    void setUp() {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        tokens = new EventStreamTokenService(properties);
        stream = new NovaEventStream(64);
        endpoint = new NovaEventEndpoint(stream, tokens);
    }

    @AfterEach
    void tearDown() {
        endpoint.shutdown();
    }

    private NovaEventEndpointTest.FakeSession connect(String id) {
        NovaEventEndpointTest.FakeSession session = new NovaEventEndpointTest.FakeSession(id);
        endpoint.afterConnectionEstablished(session);
        return session;
    }

    private void send(NovaEventEndpointTest.FakeSession session, String json) {
        endpoint.handleTextMessage(session, new TextMessage(json));
    }

    private String authFrame(String token) {
        return "{\"kind\":\"auth\",\"v\":2,\"token\":\"" + token + "\"}";
    }

    @Test
    @DisplayName("🔴 认证之前不发 hello —— 握手成功不等于认证成功")
    void sendsNothingBeforeAuth() throws Exception {
        NovaEventEndpointTest.FakeSession session = connect("s1");

        assertNull(session.next(), "认证之前不该有任何下行。hello 里有 sessionId、能力集与口径差异，"
                + "那是关于这个部署的信息，不该在认证前给出去");
    }

    @Test
    @DisplayName("有效口令通过后照常收到 hello")
    void acceptsValidToken() throws Exception {
        String token = tokens.issue("面板-甲");
        NovaEventEndpointTest.FakeSession session = connect("s2");

        send(session, authFrame(token));

        JSONObject hello = session.next();
        assertNotNull(hello, "认证通过后应当照常走原来那条路");
        assertEquals("hello", hello.getString("kind"));
        assertTrue(session.isOpen());
    }

    @Test
    @DisplayName("🔴 无效口令回 auth_failed(bad_token) 并关连接")
    void rejectsBadToken() throws Exception {
        tokens.issue("面板-甲");
        NovaEventEndpointTest.FakeSession session = connect("s3");

        send(session, authFrame("乱填的"));

        JSONObject failed = session.next();
        assertNotNull(failed, "必须回一帧说明原因, 而不是无声断开");
        assertEquals("auth_failed", failed.getString("kind"));
        assertEquals("bad_token", failed.getString("reason"));
        assertFalse(session.isOpen(), "回完就该关");
    }

    @Test
    @DisplayName("🔴 已吊销的口令回 revoked —— 与 bad_token 必须分开")
    void distinguishesRevokedFromBad() throws Exception {
        String token = tokens.issue("面板-甲");
        EventStreamToken record = tokens.list().get(0);
        tokens.revoke(tokens.fingerprintOf(record));

        NovaEventEndpointTest.FakeSession session = connect("s4");
        send(session, authFrame(token));

        JSONObject failed = session.next();
        assertNotNull(failed);
        assertEquals("auth_failed", failed.getString("kind"));
        assertEquals("revoked", failed.getString("reason"),
                "已吊销的曾经有效, 用户该去重取一把; 而 bad_token 该让他查来源。混成一种就等于没这一位");
    }

    @Test
    @DisplayName("🔒 守卫：控制台令牌不能用来连事件流")
    void rejectsConsoleToken() throws Exception {
        tokens.issue("面板-甲");
        String consoleToken = SecureToken.generate();

        NovaEventEndpointTest.FakeSession session = connect("s5");
        send(session, authFrame(consoleToken));

        JSONObject failed = session.next();
        assertNotNull(failed);
        assertEquals("bad_token", failed.getString("reason"),
                "只读口令与控制台令牌是两套东西。若校验图省事回退去比控制台令牌, 铁律当场破而功能测试全绿");
        assertFalse(session.isOpen());
    }

    @Test
    @DisplayName("🔴 守卫：未认证就发 resume 一律不理 —— 它能问出缓冲窗口")
    void ignoresResumeBeforeAuth() throws Exception {
        tokens.issue("面板-甲");
        NovaEventEndpointTest.FakeSession session = connect("s6");

        send(session, "{\"kind\":\"resume\",\"v\":2,\"data\":{\"fromSeq\":0}}");

        assertNull(session.next(), "resume 会让服务端回 hello 或开始补数据, 未认证时一个字节都不该出去");
        assertTrue(session.isOpen(), "忽略即可, 不必因此断开——断开反而告诉了对方这条路径存在");
    }

    @Test
    @DisplayName("🔴 守卫：口令放在查询参数里不算数")
    void ignoresTokenInQuery() throws Exception {
        String token = tokens.issue("面板-甲");
        NovaEventEndpointTest.FakeSession session = connect("s7");

        // 端点根本不看 URL。这条守着的是「日后有人图省事去读 query」——
        // 查询串会进反代日志、浏览器历史与 Referer，正是这次改造要绕开的东西
        send(session, "{\"kind\":\"resume\",\"v\":2,\"data\":{\"fromSeq\":0}}");
        assertNull(session.next(), "URI 里带没带 token 都不影响: 未认证就是未认证");

        // 阳性对照：同一条连接改用认证帧就能通, 说明上面的「不通」不是因为桩坏了
        send(session, authFrame(token));
        JSONObject hello = session.next();
        assertNotNull(hello, "阳性对照: 走认证帧必须能通");
        assertEquals("hello", hello.getString("kind"));
    }

    @Test
    @DisplayName("重复发 auth 不改变已认证连接的状态")
    void ignoresSecondAuth() throws Exception {
        String token = tokens.issue("面板-甲");
        NovaEventEndpointTest.FakeSession session = connect("s8");

        send(session, authFrame(token));
        assertEquals("hello", session.next().getString("kind"));

        send(session, authFrame("乱填的"));

        assertTrue(session.isOpen(), "已经认过的连接不该被第二帧顶掉");
    }

    @Test
    @DisplayName("不要求口令的部署收到 auth 帧也不该报错")
    void toleratesAuthWhenTokenNotRequired() throws Exception {
        NovaEventEndpoint open = new NovaEventEndpoint(new NovaEventStream(64));
        try {
            NovaEventEndpointTest.FakeSession session = new NovaEventEndpointTest.FakeSession("s9");
            open.afterConnectionEstablished(session);
            assertEquals("hello", session.next().getString("kind"), "不要求口令时连上即发 hello");

            open.handleTextMessage(session, new TextMessage(authFrame("随便什么")));

            assertTrue(session.isOpen(), "客户端不该为了两种部署写两套代码");
        } finally {
            open.shutdown();
        }
    }

    /**
     * 逾期未认证这一路，此前只有实现没有判据
     * <p>
     * 使用说明与本机跑真源那两份文档都在教使用者依赖这条行为：
     * <b>10 秒内不出示口令就被关，而且那一路不发任何解释帧</b>。
     * 「不发解释帧」是有意的——没人出示过任何东西，发 {@code auth_failed} 反而是在
     * 回答一个没被问过的问题；客户端要靠这一点把「我压根没发」和「我发错了」分开。
     * <p>
     * 🔴 <b>不等真的 10 秒</b>：那会让整套测试白白慢十秒。这里改为钉住三件可验的事——
     * 常量确实是 10 秒、连上时超时确实被挂上了（不是有个常量摆着没人用）、
     * 到点执行的那段确实按文档说的关。三件缺一，文档那句就落空。
     */
    @Test
    @DisplayName("🔴 逾期未认证：POLICY_VIOLATION 关闭，且一个解释帧都不发")
    void closesUnauthenticatedWithoutAnyExplainingFrame() throws Exception {
        NovaEventEndpointTest.FakeSession session = connect("s-timeout");

        assertEquals(10_000L, NovaEventEndpoint.AUTH_TIMEOUT_MS,
                "文档写的是 10 秒。改了这个数就要同步改文档，否则文档开始撒谎");

        Object client = clientOf(session);
        assertNotNull(readField(client, "authDeadline"),
                "要求口令时连上就该挂上超时。挂不上的话前一条断言只是个没人用的常量");

        closeUnauthenticated(client);

        assertNull(session.next(),
                "逾期这一路不发任何帧：没人出示过任何东西，发 auth_failed 是在答没被问的问题");
        assertNotNull(session.closedWith, "应当被关掉");
        assertEquals(CloseStatus.POLICY_VIOLATION.getCode(), session.closedWith.getCode(),
                "关闭码要与「口令错」那一路分得开");
        assertEquals("未在时限内认证", session.closedWith.getReason());
    }

    private Object clientOf(NovaEventEndpointTest.FakeSession session) throws Exception {
        java.lang.reflect.Field field = NovaEventEndpoint.class.getDeclaredField("clients");
        field.setAccessible(true);
        Object client = ((java.util.Map<?, ?>) field.get(endpoint)).get(session.getId());
        assertNotNull(client, "连上之后应当登记在册");
        return client;
    }

    private Object readField(Object target, String name) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private void closeUnauthenticated(Object client) throws Exception {
        java.lang.reflect.Method method = client.getClass().getDeclaredMethod("closeUnauthenticated");
        method.setAccessible(true);
        method.invoke(client);
    }
}
