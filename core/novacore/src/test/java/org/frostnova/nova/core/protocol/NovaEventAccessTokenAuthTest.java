package org.frostnova.nova.core.protocol;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.properties.EventStreamProperties;
import org.frostnova.nova.core.properties.LiveProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件流首帧也认访问令牌
 * <p>
 * 访问令牌的存放与账号判定在外壳里，这里用一个替身代替外壳那一侧，只量端点这一侧：
 * 认不认、回什么原因、连着时失效了断不断、失败多了停不停、只读口令那一路变没变。
 */
@DisplayName("事件流首帧认访问令牌")
class NovaEventAccessTokenAuthTest {
    @TempDir
    Path dir;

    private EventStreamTokenService readonly;

    private NovaEventStream stream;

    private StubAccessTokens access;

    private NovaEventEndpoint endpoint;

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

    private final List<NovaEventEndpointTest.FakeSession> sessions = new ArrayList<>();

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    private final Logger protocolLogger = (Logger) LoggerFactory.getLogger("org.frostnova.nova.core.protocol");

    @BeforeEach
    void setUp() {
        LiveProperties live = new LiveProperties();
        live.setLiveDataPath(dir.resolve("data.json").toString());
        readonly = new EventStreamTokenService(live);
        stream = new NovaEventStream(64);
        access = new StubAccessTokens();
        logs.start();
        protocolLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        protocolLogger.detachAppender(logs);
        logs.stop();
        if (endpoint != null) {
            endpoint.shutdown();
        }
    }

    /** 复核间隔给得很长：量「当场」的那几格不能靠定时复核碰巧赶上 */
    private NovaEventEndpoint endpoint(long recheckMillis) {
        endpoint = new NovaEventEndpoint(stream, readonly, access, NovaEventEndpoint.Timings.DEFAULT,
                new EventStreamProperties(), recheckMillis,
                new AccessTokenFailures(AccessTokenFailures.MAX_FAILURES,
                        TimeUnit.SECONDS.toNanos(AccessTokenFailures.WINDOW_SECONDS), nanos::get));
        return endpoint;
    }

    private NovaEventEndpointTest.FakeSession connect(String token) {
        NovaEventEndpointTest.FakeSession session = new NovaEventEndpointTest.FakeSession("s" + sessions.size());
        sessions.add(session);
        endpoint.afterConnectionEstablished(session);
        JSONObject frame = new JSONObject();
        frame.put("kind", "auth");
        frame.put("token", token);
        endpoint.handleTextMessage(session, new TextMessage(frame.toJSONString()));
        return session;
    }

    private static void awaitClosed(NovaEventEndpointTest.FakeSession session) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (session.closedWith == null && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertNotNull(session.closedWith, "连接应当被关掉");
    }

    private static JSONObject danmaku() {
        JSONObject envelope = new JSONObject();
        envelope.put("v", 2);
        envelope.put("kind", "danmaku");
        envelope.put("ts", 1786111565000L);
        envelope.put("room", 10000);
        return envelope;
    }

    private String logText() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : logs.list) {
            text.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                text.append(event.getThrowableProxy().getMessage()).append('\n');
            }
        }
        return text.toString();
    }

    // 抓的故障：管理员签了一把勾了「读取事件流」的访问令牌，面板在本机连上、首帧带它，却等不到 hello、收不到事件。
    @Test
    void grantedAccessTokenGetsHelloAndThenEvents() throws Exception {
        endpoint(3_600_000);
        String token = access.grant("nvb_panel-token-1", "t1");

        NovaEventEndpointTest.FakeSession session = connect(token);

        JSONObject hello = session.next();
        assertNotNull(hello, "访问令牌通过之后应当照常发 hello");
        assertEquals("hello", hello.getString("kind"));
        endpoint.handleTextMessage(session, new TextMessage("{\"kind\":\"resume\",\"data\":{\"fromSeq\":0}}"));
        stream.publish(danmaku());
        JSONObject event = session.next();
        assertNotNull(event, "认证之后应当照常收事件");
        assertEquals("danmaku", event.getString("kind"));
        assertTrue(session.isOpen());
        assertTrue(logText().contains("账号 测试管理员 的访问令牌「面板」"), "认证成功应记下是哪个账号的哪一把：\n" + logText());
    }

    // 抓的故障：已吊销、已过期、账号停用或降成成员的访问令牌照样连得上；或者回的原因认不出、混成一种。
    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"REVOKED,revoked", "EXPIRED,expired", "BAD_TOKEN,bad_token"})
    void refusedAccessTokenGetsAuthFailedWithItsReason(EventStreamTokenService.Verdict verdict, String wire)
            throws Exception {
        endpoint(3_600_000);
        access.checks.put("nvb_refused", EventStreamAccessTokens.Check.refused(verdict));

        NovaEventEndpointTest.FakeSession session = connect("nvb_refused");

        JSONObject failed = session.next();
        assertNotNull(failed, "要先回一帧说明原因");
        assertEquals("auth_failed", failed.getString("kind"));
        assertEquals(wire, failed.getString("reason"));
        assertEquals(2, failed.getIntValue("v"));
        assertFalse(session.isOpen(), "回完就该关");
        assertEquals(CloseStatus.NORMAL.getCode(), session.closedWith.getCode(), "与只读口令不对时同一种关法");
        assertEquals(0, session.sentCount(), "失败之后不该再有 hello 或事件");
    }

    // 抓的故障：只勾了外发箱范围（或只有推送范围）的访问令牌，事件流的回应与乱填一串不一样，
    // 于是拿着令牌的人能分辨「这把令牌存在、只是没这个范围」。
    @Test
    void tokenThatIsNotAnEventCredentialLooksExactlyLikeGarbage() throws Exception {
        endpoint(3_600_000);
        // 替身对这一把回「不是事件流凭据」：外壳那一侧对表里没有的、没勾范围的都这么回
        access.checks.put("nvb_outbox-only", EventStreamAccessTokens.Check.notMine());

        NovaEventEndpointTest.FakeSession withoutScope = connect("nvb_outbox-only");
        NovaEventEndpointTest.FakeSession garbage = connect("nvb_made-up-string");

        JSONObject first = withoutScope.next();
        JSONObject second = garbage.next();
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(second, first, "两种情形回的那一帧应当一模一样");
        assertEquals("bad_token", first.getString("reason"));
        assertEquals(garbage.closedWith, withoutScope.closedWith, "关法也应当一样");
        assertFalse(withoutScope.isOpen());
    }

    // 抓的故障：升级后原来用只读口令的面板连不上了；或者只读口令也被拿去问了访问令牌那一侧。
    @Test
    void readonlyTokensWorkExactlyAsBefore() throws Exception {
        endpoint(3_600_000);
        String token = readonly.issue("老面板");

        NovaEventEndpointTest.FakeSession session = connect(token);

        assertEquals("hello", session.next().getString("kind"));
        assertEquals(0, access.checkCalls.get(), "只读口令不该交给访问令牌那一侧去核");
        readonly.revoke(readonly.fingerprintOf(readonly.list().get(0)));
        awaitClosed(session);
        assertEquals(CloseStatus.NORMAL.withReason("口令已吊销"), session.closedWith, "只读口令的吊销照旧当场断开");
    }

    // 抓的故障：某把旧的只读口令碰巧以访问令牌的开头起头（随机串里撞上的），升级后这块面板就连不上了。
    @Test
    void readonlyTokenThatHappensToStartWithTheAccessPrefixStillWorks() throws Exception {
        endpoint(3_600_000);
        String token = "nvb_" + "Q".repeat(20) + "x7Kp2mZ9vB4nL8sD1fG6hJ3";
        Files.writeString(dir.resolve("event-stream-tokens.jsonl"),
                JSONObject.of("hash", sha256(token), "label", "撞了开头的旧面板", "issuedAt", 1L, "revokedAt", 0L)
                        .toJSONString() + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        NovaEventEndpointTest.FakeSession session = connect(token);

        JSONObject hello = session.next();
        assertNotNull(hello);
        assertEquals("hello", hello.getString("kind"), "访问令牌那一侧不认的，要照旧交给只读口令去核");
        assertEquals(1, access.checkCalls.get(), "先问过访问令牌那一侧");
    }

    // 抓的故障：访问令牌在控制台吊销了（或账号被停用、被降为成员），外部面板还一直连着收数据。
    @Test
    @Timeout(20)
    void connectionIsClosedAsSoonAsTheTokenStopsBeingValid() throws Exception {
        endpoint(3_600_000);
        String token = access.grant("nvb_panel-token-2", "t2");
        NovaEventEndpointTest.FakeSession session = connect(token);
        assertEquals("hello", session.next().getString("kind"));
        NovaEventEndpointTest.FakeSession other = connect(access.grant("nvb_other-panel", "t3"));
        assertEquals("hello", other.next().getString("kind"));
        NovaEventEndpointTest.FakeSession old = connect(readonly.issue("老面板"));
        assertEquals("hello", old.next().getString("kind"));

        access.rechecks.put("t2", EventStreamTokenService.Verdict.REVOKED);
        access.changed();

        awaitClosed(session);
        assertEquals(CloseStatus.NORMAL.withReason("口令已吊销"), session.closedWith, "与吊销只读口令时同一种关法");
        assertEquals(0, session.sentCount(), "关闭前不该补发 auth_failed 或别的帧");
        assertTrue(other.isOpen(), "别的令牌的连接不受影响");
        assertTrue(old.isOpen(), "只读口令的连接不受影响");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!logText().contains("编号 t2") && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(logText().contains("编号 t2"), "断开要记下是哪一把：\n" + logText());
    }

    // 抓的故障：访问令牌到期了，面板却一直连着——到期没有人去「通知」，只能靠定时复核发现。
    @Test
    @Timeout(20)
    void expiryIsFoundByThePeriodicRecheck() throws Exception {
        assertTrue(NovaEventEndpoint.RECHECK_INTERVAL_MS <= 60_000, "失效之后最迟一分钟内要断开");
        endpoint(50);
        NovaEventEndpointTest.FakeSession session = connect(access.grant("nvb_expiring", "t4"));
        assertEquals("hello", session.next().getString("kind"));

        access.rechecks.put("t4", EventStreamTokenService.Verdict.EXPIRED);

        awaitClosed(session);
        assertEquals(CloseStatus.NORMAL.withReason("口令已过期"), session.closedWith);
    }

    // 抓的故障：账号被降成成员、或令牌失效时说不清原因，面板只看见一个没头没脑的断开。
    @Test
    @Timeout(20)
    void otherInvalidationsCloseWithAPlainReason() throws Exception {
        endpoint(50);
        NovaEventEndpointTest.FakeSession session = connect(access.grant("nvb_demoted", "t5"));
        assertEquals("hello", session.next().getString("kind"));

        access.rechecks.put("t5", EventStreamTokenService.Verdict.BAD_TOKEN);

        awaitClosed(session);
        assertEquals(CloseStatus.NORMAL.withReason("口令已失效"), session.closedWith);
    }

    // 抓的故障：吊销恰好发生在面板认证的那一刻（核对已经通过、连接还没登记），这条连接就漏掉了，一直连着。
    @Test
    @Timeout(20)
    void revocationDuringTheCheckIsNotMissed() throws Exception {
        endpoint(3_600_000);
        String token = access.grant("nvb_racing", "t6");
        access.duringCheck = () -> {
            access.rechecks.put("t6", EventStreamTokenService.Verdict.REVOKED);
            access.changed();
        };

        NovaEventEndpointTest.FakeSession session = connect(token);

        awaitClosed(session);
        assertEquals(CloseStatus.NORMAL.withReason("口令已吊销"), session.closedWith);
    }

    // 抓的故障：有人在本机换着访问令牌不停地试，事件流的首帧一次也不拦，比统一接口更好猜。
    @Test
    void tooManyFailuresPauseAccessTokensButNotReadonlyTokens() throws Exception {
        endpoint(3_600_000);
        String valid = access.grant("nvb_good-panel", "t7");
        for (int i = 0; i < AccessTokenFailures.MAX_FAILURES; i++) {
            NovaEventEndpointTest.FakeSession guess = connect("nvb_guess-" + i);
            assertEquals("bad_token", guess.next().getString("reason"), "第 " + (i + 1) + " 次还该照常核对、照常回原因");
        }
        int checked = access.checkCalls.get();

        NovaEventEndpointTest.FakeSession paused = connect(valid);

        assertEquals(checked, access.checkCalls.get(), "暂停受理时不该再核对：核了的话，猜中的那一把照样连得上");
        awaitClosed(paused);
        assertEquals(CloseStatus.SERVICE_OVERLOAD.withReason("事件输出暂忙，请稍后重连"), paused.closedWith,
                "要让正经面板当成暂忙去退避重连，而不是当成令牌不对去换令牌");
        assertEquals(0, paused.sentCount(), "暂停时既不发 hello 也不发 auth_failed");
        assertTrue(logText().contains("暂停受理访问令牌"), "开始暂停时要在日志里说一句：\n" + logText());

        NovaEventEndpointTest.FakeSession old = connect(readonly.issue("老面板"));
        assertEquals("hello", old.next().getString("kind"), "只读口令那一路不受这个计数影响");

        nanos.addAndGet(TimeUnit.SECONDS.toNanos(AccessTokenFailures.WINDOW_SECONDS));
        NovaEventEndpointTest.FakeSession later = connect(valid);
        assertEquals("hello", later.next().getString("kind"), "窗口过了自动恢复");
    }

    // 抓的故障：随便一串假令牌、空令牌、超长的令牌让服务端抛了异常，或者把令牌原文写进了日志。
    @Test
    void oddTokensGetAuthFailedAndNeverReachTheLog() throws Exception {
        endpoint(3_600_000);
        String overlong = "nvb_" + "A".repeat(100_000);
        List<String> presented = List.of("nvb_fake-token-for-the-log-check", "", overlong, "nvb_");

        for (String token : presented) {
            NovaEventEndpointTest.FakeSession session = connect(token);
            JSONObject failed = session.next();
            assertNotNull(failed, "要回 auth_failed，而不是无声断开");
            assertEquals("bad_token", failed.getString("reason"));
            assertFalse(session.isOpen());
        }
        NovaEventEndpointTest.FakeSession missing = new NovaEventEndpointTest.FakeSession("missing");
        endpoint.afterConnectionEstablished(missing);
        endpoint.handleTextMessage(missing, new TextMessage("{\"kind\":\"auth\"}"));
        assertEquals("bad_token", missing.next().getString("reason"));

        String text = logText();
        assertFalse(text.contains("nvb_fake-token-for-the-log-check"), "日志里出现了令牌原文");
        assertFalse(text.contains("fake-token-for-the-log-check"), "日志里出现了令牌原文的一截");
        assertFalse(text.contains("AAAAAAAAAAAAAAAA"), "日志里出现了超长令牌的原文");
    }

    // 抓的故障：外壳那一侧出了错（例如状态文件读坏了），事件流就把异常抛给了容器，连接挂在那里没人回。
    @Test
    void failureInsideTheCheckIsABadTokenNotACrash() throws Exception {
        endpoint(3_600_000);
        access.failure = new IllegalStateException("boom nvb_secret-in-message");

        NovaEventEndpointTest.FakeSession session = connect("nvb_secret-in-message");

        JSONObject failed = session.next();
        assertNotNull(failed);
        assertEquals("bad_token", failed.getString("reason"));
        assertFalse(session.isOpen());
        assertFalse(logText().contains("nvb_secret-in-message"), "日志里出现了令牌原文：\n" + logText());
    }

    // 抓的故障：端点关停之后还挂在外壳的通知上，或者复核线程还在跑。
    @Test
    void shutdownDetachesFromChangeNotifications() {
        endpoint(3_600_000);
        assertEquals(1, access.listeners.size(), "端点应当登记通知");
        endpoint.shutdown();
        assertEquals(0, access.listeners.size(), "关停后应当撤掉通知");
        endpoint = null;
    }

    // 抓的故障：有人把访问令牌塞进握手（请求头、子协议、地址栏参数）也被放行了，令牌就此进了代理日志；
    // 或者从别的机器带着访问令牌来握手，事件流因为认访问令牌了就放了进来。
    @Test
    void handshakeRulesDoNotBendForAccessTokens() throws Exception {
        NovaEventStreamConfiguration configuration = new NovaEventStreamConfiguration();
        try {
            EventStreamProperties properties = new EventStreamProperties();
            properties.setEnabled(true);
            org.springframework.web.servlet.handler.SimpleUrlHandlerMapping mapping =
                    (org.springframework.web.servlet.handler.SimpleUrlHandlerMapping) configuration
                            .novaEventStreamHandlerMapping(properties, stream, readonly, access);
            org.springframework.web.socket.server.support.WebSocketHttpRequestHandler handler =
                    (org.springframework.web.socket.server.support.WebSocketHttpRequestHandler)
                            mapping.getUrlMap().get(properties.getPath());
            String token = access.grant("nvb_valid-panel-token", "t9");

            assertEquals(200, handshake(handler, "127.0.0.1", null, null, null), "阳性对照：干净的本机握手放行");
            assertEquals(400, handshake(handler, "127.0.0.1", "Bearer " + token, null, null));
            assertEquals(400, handshake(handler, "127.0.0.1", null, "nova.token." + token, null));
            assertEquals(400, handshake(handler, "127.0.0.1", null, null, "token=" + token));
            assertEquals(400, handshake(handler, "127.0.0.1", null, null, "access_token=" + token));
            assertEquals(-1, handshake(handler, "192.0.2.10", null, null, null), "本机以外的地址照旧被拒");
            assertEquals(-1, handshake(handler, "192.0.2.10", "Bearer " + token, null, null), "带着有效令牌也一样");
            assertEquals(0, access.checkCalls.get(), "握手里的任何东西都不该拿去核对");
        } finally {
            configuration.destroy();
        }
    }

    // 抓的故障：外壳里登记了两份访问令牌的实现，程序起不来，或者随便认了其中一份。
    @Test
    void twoImplementationsMeanReadonlyTokensOnly() throws Exception {
        StubAccessTokens other = new StubAccessTokens();
        String token = access.grant("nvb_two-impls", "t10");
        other.grant(token, "t11");
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory(
                Map.of("first", access, "second", other));
        NovaEventStreamConfiguration configuration = new NovaEventStreamConfiguration();
        try {
            EventStreamProperties properties = new EventStreamProperties();
            properties.setEnabled(true);
            var mapping = (org.springframework.web.servlet.handler.SimpleUrlHandlerMapping) configuration
                    .novaEventStreamHandlerMapping(properties, stream, readonly,
                            beans.getBeanProvider(EventStreamAccessTokens.class));
            var handler = (org.springframework.web.socket.server.support.WebSocketHttpRequestHandler)
                    mapping.getUrlMap().get(properties.getPath());
            NovaEventEndpoint built = (NovaEventEndpoint) org.springframework.web.socket.handler.WebSocketHandlerDecorator
                    .unwrap(handler.getWebSocketHandler());

            NovaEventEndpointTest.FakeSession session = new NovaEventEndpointTest.FakeSession("two");
            built.afterConnectionEstablished(session);
            built.handleTextMessage(session, new TextMessage("{\"kind\":\"auth\",\"token\":\"" + token + "\"}"));
            assertEquals("bad_token", session.next().getString("reason"));
            assertEquals(0, access.checkCalls.get() + other.checkCalls.get(), "两份都不该被问到");

            NovaEventEndpointTest.FakeSession old = new NovaEventEndpointTest.FakeSession("two-old");
            built.afterConnectionEstablished(old);
            built.handleTextMessage(old, new TextMessage("{\"kind\":\"auth\",\"token\":\"" + readonly.issue("老面板") + "\"}"));
            assertEquals("hello", old.next().getString("kind"), "只读口令照常");
        } finally {
            configuration.destroy();
        }
    }

    /**
     * 按生产的顺序走一遍握手拦截
     * @return 全部放行为 200；被拦下时为拦截写下的状态码，没写状态码为 -1
     */
    private static int handshake(org.springframework.web.socket.server.support.WebSocketHttpRequestHandler handler,
                                 String remote, String authorization, String subprotocol, String query) throws Exception {
        org.springframework.mock.web.MockHttpServletRequest raw =
                new org.springframework.mock.web.MockHttpServletRequest("GET", "/nova/events");
        raw.setRemoteAddr(remote);
        raw.setRemotePort(40000);
        if (authorization != null) {
            raw.addHeader("Authorization", authorization);
        }
        if (subprotocol != null) {
            raw.addHeader("Sec-WebSocket-Protocol", subprotocol);
        }
        if (query != null) {
            raw.setQueryString(query);
        }
        org.springframework.mock.web.MockHttpServletResponse rawResponse = new org.springframework.mock.web.MockHttpServletResponse();
        var request = new org.springframework.http.server.ServletServerHttpRequest(raw);
        var response = new org.springframework.http.server.ServletServerHttpResponse(rawResponse);
        for (var interceptor : handler.getHandshakeInterceptors()) {
            if (!interceptor.beforeHandshake(request, response, handler.getWebSocketHandler(), new java.util.HashMap<>())) {
                response.flush();
                return rawResponse.getStatus() == 200 ? -1 : rawResponse.getStatus();
            }
        }
        return 200;
    }

    private static String sha256(String token) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    /** 外壳那一侧的替身：按表回结论，通知由用例手动发 */
    static final class StubAccessTokens implements EventStreamAccessTokens {
        final Map<String, Check> checks = new ConcurrentHashMap<>();

        final Map<String, EventStreamTokenService.Verdict> rechecks = new ConcurrentHashMap<>();

        final List<Runnable> listeners = new CopyOnWriteArrayList<>();

        final AtomicInteger checkCalls = new AtomicInteger();

        volatile Runnable duringCheck = () -> { };

        volatile RuntimeException failure;

        String grant(String token, String id) {
            checks.put(token, Check.granted(new Grant(id, "账号 测试管理员 的访问令牌「面板」（编号 " + id + "）")));
            return token;
        }

        void changed() {
            listeners.forEach(Runnable::run);
        }

        @Override
        public boolean claims(String presented) {
            return presented != null && presented.startsWith("nvb_");
        }

        @Override
        public Check check(String presented) {
            checkCalls.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            Check check = checks.getOrDefault(presented, Check.notMine());
            duringCheck.run();
            return check;
        }

        @Override
        public EventStreamTokenService.Verdict recheck(Grant grant) {
            return rechecks.getOrDefault(grant.id(), EventStreamTokenService.Verdict.OK);
        }

        @Override
        public void addChangeListener(Runnable listener) {
            listeners.add(listener);
        }

        @Override
        public void removeChangeListener(Runnable listener) {
            listeners.remove(listener);
        }
    }
}
