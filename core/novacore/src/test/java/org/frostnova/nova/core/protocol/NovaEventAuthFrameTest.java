package org.frostnova.nova.core.protocol;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.properties.LiveProperties;
import org.frostnova.nova.core.model.EventStreamToken;
import org.frostnova.nova.core.lang.SecureToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 事件流首帧认证
 * <p>
 * 认证从握手挪到了连接建立之后的第一帧。原因不是握手拦得不好，
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
        LiveProperties live = new LiveProperties();
        live.setLiveDataPath(dir.resolve("data.json").toString());
        tokens = new EventStreamTokenService(live);
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

    /**
     * 🔴 超时清理这条路上，关闭帧是这条连接收到的<b>第一个、也是唯一一个</b>下行帧
     * <p>
     * 这不是协议洁癖，它挡的是一个<b>并发</b>问题：逾期未认证的清理跑在<b>全局共享的</b>
     * 心跳线程上，而关一条 WebSocket 要发一帧关闭帧——那是一次<b>阻塞写</b>。
     * 眼下它写得出去，只是因为认证之前这条连接上一个字节都没下行过、发送缓冲是空的。
     * <p>
     * 也就是说，它<b>不是因为安全才安全</b>，是因为现在没人往那儿写。
     * 将来谁往认证之前加了下行（横幅、版本协商、排队提示……），对端不读时缓冲会填满，
     * 这一帧就写不动，<b>一条没认证的连接会把共享心跳线程钉住</b>——
     * 所有连接的 ping、认证时限、回补窗口跟着一起停。
     * <p>
     * 所以这一格拦的是那个人：先在这里被拦下来，去把清理那一步改成不阻塞共享线程，
     * 再回来加下行。
     * <p>
     * <b>射程只钉「超时」这一条路。</b>口令错那条路是<b>有</b>下行的（先发 auth_failed 再关），
     * 别把这一格写宽了去误伤它。
     */
    @Test
    @DisplayName("🔴 超时清理：关闭帧是这条连接唯一的下行帧（认证前既不发也不排队）")
    void timeoutCleanupSendsNothingBeforeTheCloseFrame() throws Exception {
        NovaEventEndpointTest.FakeSession session = connect("s-nothing-before-close");
        Object client = clientOf(session);

        // 🔴 两样都要量：**已经写出去的**与**还排在待发队列里的**。
        //    只量写出去的话，将来那条认证前下行若走 `send(...)`（进待发队列、由发送线程异步写），
        //    这一处就是竞态；而 `beginClose()` 关的时候会清空待发队列，
        //    那一帧可能**永远写不出去**——于是这一格静静保持绿，而洞是真的开了。
        //    两样合起来才是完整的：这一刻它要么已写出、要么还排着，不可能两处都看不见。
        assertEquals(0, session.sentCount(),
                "认证之前这条连接上不该有任何下行帧。加了的话，逾期清理那一帧关闭帧"
                        + "就可能写不动，而它跑在全局共享的心跳线程上");
        assertEquals(0, outboxSize(client),
                "认证之前也不该有任何帧**排进待发队列**。排队的帧同样会撑满对端缓冲，"
                        + "而它在关闭时会被清掉——只量「已写出的」的话，这一半就漏了");

        closeUnauthenticated(client);

        assertNotNull(session.closedWith, "逾期就该关掉");
        assertEquals(0, session.sentCount(),
                "关闭帧之外，这条连接自始至终不该收到任何下行帧");
    }

    /** 这条连接此刻排了几帧待发 */
    private int outboxSize(Object client) throws Exception {
        return ((java.util.Collection<?>) readField(client, "outbox")).size();
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

    /**
     * 使用说明写着「{@code token} 是唯一必填项，带上 {@code v} 也可以，本端不校验它」
     * <p>
     * 此前所有用例都走 {@link #authFrame} 而它<b>恒带 {@code v:2}</b>——
     * 也就是说「不带 v 也行」这半句从没被验过。
     * 🔴 <b>它还得是「不校验」而不是「刚好也认」</b>：所以另发一帧 {@code v} 写成
     * 完全不对的值，同样该通过。两条都过，文档那句才立得住。
     */
    @Test
    @DisplayName("认证帧不带 v 照样通过；v 写错也不拦 —— 它是装饰字段")
    void acceptsAuthFrameRegardlessOfVersionField() throws Exception {
        NovaEventEndpointTest.FakeSession noVersion = connect("s-no-v");
        send(noVersion, "{\"kind\":\"auth\",\"token\":\"" + tokens.issue("面板-无v") + "\"}");
        JSONObject hello = noVersion.next();
        assertNotNull(hello, "token 是唯一必填项，不带 v 也该通过");
        assertEquals("hello", hello.getString("kind"));

        NovaEventEndpointTest.FakeSession wrongVersion = connect("s-bad-v");
        send(wrongVersion, "{\"kind\":\"auth\",\"v\":9999,\"token\":\"" + tokens.issue("面板-错v") + "\"}");
        JSONObject hello2 = wrongVersion.next();
        assertNotNull(hello2, "v 不被校验，写错也不该拦 —— 别拿它做版本协商");
        assertEquals("hello", hello2.getString("kind"));
    }

    /**
     * 回补窗口是 2 秒，且它在<b>认证成功那一刻</b>才挂上
     * <p>
     * 「迟到的 resume 会收到一份新的 hello」那条行为另有判据看着，但**2 这个数**没有：
     * 文档同时在教「收到 hello 后 2 秒内发 resume」。窗口挂在认证之后这件事也要钉——
     * 挂在连上那一刻的话，认证花掉的时间会从回补窗口里扣走。
     */
    @Test
    @DisplayName("🔴 回补窗口是 2 秒，且认证成功后才挂上")
    void resumeGraceIsTwoSecondsAndArmedAfterAuth() throws Exception {
        assertEquals(2_000L, NovaEventEndpoint.RESUME_GRACE_MS,
                "文档写的是 2 秒。改了这个数就要同步改文档");

        NovaEventEndpointTest.FakeSession session = connect("s-grace");
        Object client = clientOf(session);
        assertNull(readField(client, "grace"),
                "认证之前不该挂回补窗口：认证花掉的时间不该从窗口里扣走");

        send(session, authFrame(tokens.issue("面板-窗口")));
        assertEquals("hello", session.next().getString("kind"));
        assertNotNull(readField(client, "grace"), "认证成功之后窗口才该挂上");
    }

    /**
     * 消费过慢 → {@code SERVICE_OVERLOAD}
     * <p>
     * 使用说明把这条列成了三种断法之一，而此前只有实现没有判据。
     * 要驱动它就得让下行**真的写不动**：普通夹具写进队列即返回，队列永远排不满。
     * 这里用一个卡住不返回的会话，把出队那一侧堵死。
     */
    @Test
    @Timeout(60)
    @DisplayName("🔴 消费过慢：队列排满即 SERVICE_OVERLOAD 断开")
    void closesSlowConsumerWithServiceOverload() throws Exception {
        StalledSession session = new StalledSession("s-slow");
        endpoint.afterConnectionEstablished(session);
        send(session, authFrame(tokens.issue("面板-慢")));

        // 🔴 认证完还不够：转入实时流之前，publish 只进事件流的缓冲，一条都不会进这个客户端的队列
        //    （{@code onFrame} 要等 {@code goLive} 里 subscribe 之后才会被调到）。
        //    发一帧 resume 让它当场转入实时流，否则这一格量的是「没订阅的人收不到」，不是背压。
        send(session, "{\"kind\":\"resume\",\"data\":{\"fromSeq\":" + stream.snapshot().lastSeq() + "}}");

        // 🔴 堵只能在转入实时流<b>之后</b>开：writeDirect 是攥着本连接的锁去写的，
        //    开早了，hello 那一帧就把锁攥死，上面那句 resume 压根进不来——
        //    不是用例失败，是整个构建吊住（本用例第一版就是这么吊死的）
        session.stall();

        try {
            int capacity = stream.getCapacity() + 256;
            for (int i = 0; i < capacity + 64 && session.closedWith == null; i++) {
                stream.publish(slowEnvelope());
            }

            // 🔴 断开是在发布线程上记账、关闭帧交给发送线程写的：publish 返回时关闭帧未必已经写了。
            //    上面的循环可能把余下的帧发完才出来，所以这里有界地等一下，不空等、也不无限等
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (session.closedWith == null && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }

            assertNotNull(session.closedWith, "排满了就该断开，而不是无限堆积");
            assertEquals(CloseStatus.SERVICE_OVERLOAD.getCode(), session.closedWith.getCode(),
                    "要与「认证超时」「口令错」那两种断法分得开");
            assertEquals("客户端消费过慢", session.closedWith.getReason());
        } finally {
            session.release();
        }
    }

    /** 带这个记号的那一帧，是 A 断开之后才发的 */
    private static final String MARK = "probe-after-overload";

    /**
     * 消费过慢断开时，关闭帧不许在发布线程上写
     * <p>
     * 分发是在事件流的锁里挨个回调订阅者的，而发布线程就是采集线程。
     * 队列满的那一刻要断开这条连接，断开要发一帧关闭帧——那是一次<b>阻塞写</b>，
     * 落在写不动的连接上就停在那儿。在回调里就地写的话，停住的是攥着锁的发布线程：
     * 采集跟着停，别的连接一帧也收不到。
     * <p>
     * 夹具：A 写不动，关闭帧也写不动（关闭卡在闩上，有界）；B 正常读。
     * 后台线程一直发，直到 A 进了关闭。然后问四件事，一问一个 try，末尾一起报。
     * 第四问是阳性锚：放开之后 A 确实按「消费过慢」断开——它不绿，前三问的绿证不了夹具真走到了队列满。
     */
    @Test
    @Timeout(60)
    @DisplayName("🔴 消费过慢断开时，关闭帧不在发布线程上写：再发一帧照常返回、别的连接照收")
    void slowConsumerCloseFrameIsNotWrittenOnThePublishingThread() throws Exception {
        CloseStalledSession slow = new CloseStalledSession("s-slow-close");
        endpoint.afterConnectionEstablished(slow);
        send(slow, authFrame(tokens.issue("面板-慢关")));
        send(slow, "{\"kind\":\"resume\",\"data\":{\"fromSeq\":" + stream.snapshot().lastSeq() + "}}");
        slow.stall();

        MarkedSession healthy = new MarkedSession("s-healthy");
        Thread publisher = null;
        Thread probe = null;
        List<String> reds = new ArrayList<>();
        try {
            // 🔴 先把 A 的待发队列灌到差两格满，但不许灌到溢出：溢出那一发会在本线程上断开 A，
            //    下面几问就问错了线程。灌好了再接 B，B 只收后面几帧、不陪 A 收这几百帧——
            //    防的是 B 自己的待发队列被夹具灌满、先被断开，那样红的是夹具，不是被测。
            BlockingQueue<?> slowOutbox = (BlockingQueue<?>) readField(clientOf(slow), "outbox");
            for (int i = 0; i < 4 * (stream.getCapacity() + 256) && slowOutbox.remainingCapacity() > 2; i++) {
                stream.publish(slowEnvelope());
            }
            assertTrue(slowOutbox.remainingCapacity() <= 2,
                    "A 的待发队列灌不满（还余 " + slowOutbox.remainingCapacity() + " 格）——夹具没搭起来");

            endpoint.afterConnectionEstablished(healthy);
            send(healthy, authFrame(tokens.issue("面板-正常")));
            send(healthy, "{\"kind\":\"resume\",\"data\":{\"fromSeq\":" + stream.snapshot().lastSeq() + "}}");

            publisher = new Thread(() -> {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (slow.closeEntered.getCount() > 0 && System.nanoTime() < deadline) {
                    stream.publish(slowEnvelope());
                    try {
                        // 放慢一拍：A 差两格就满，几发就够，不必跟 B 的发送线程抢跑
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "nova-test-publisher");
            publisher.setDaemon(true);
            publisher.start();
            assertTrue(slow.closeEntered.await(10, TimeUnit.SECONDS),
                    "A 一直没进关闭——夹具没走到「队列满断开」，下面四问都不算数");

            // ① 再发一帧。另起一条线程去发、本线程限时等：发布被卡住时，本线程不能陪着卡
            JSONObject marked = slowEnvelope();
            marked.put("mark", MARK);
            FutureTask<NovaEventStream.Frame> probePublish = new FutureTask<>(() -> stream.publish(marked));
            probe = new Thread(probePublish, "nova-test-probe-publish");
            probe.setDaemon(true);
            probe.start();
            try {
                assertDoesNotThrow(() -> probePublish.get(2, TimeUnit.SECONDS),
                        "① A 断开之后再发一帧，2 秒内没返回——关闭帧是攥着事件流的锁写的，发布线程停住了");
            } catch (AssertionError e) {
                reds.add(e.getMessage());
            }

            // ② 正常读的 B 收不收得到这一帧
            try {
                assertTrue(healthy.marked.await(2, TimeUnit.SECONDS),
                        "② 正常读的连接 2 秒内没收到这一帧——一条写不动的连接把别人也拖住了");
            } catch (AssertionError e) {
                reds.add(e.getMessage());
            }

            // ③ A 的关闭帧在哪条线程上写
            try {
                String closer = slow.closeThread;
                assertTrue(closer != null && closer.startsWith("nova-event-sender-"),
                        "③ A 的关闭帧是在「" + closer + "」上写的，不是发送线程");
            } catch (AssertionError e) {
                reds.add(e.getMessage());
            }

            // ④ 阳性锚：放开之后，A 确实是按「消费过慢」断开的
            slow.releaseClose();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (slow.closedWith == null && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertNotNull(slow.closedWith, "④ 放开之后 5 秒 A 仍没关上——夹具没走到队列满断开，前三问的绿不作数");
                assertEquals(CloseStatus.SERVICE_OVERLOAD.getCode(), slow.closedWith.getCode(), "④ 关闭码不对");
                assertEquals("客户端消费过慢", slow.closedWith.getReason(), "④ 关闭原因不对");
            } catch (AssertionError e) {
                reds.add(e.getMessage());
            }

            // ⑤ 断开要退订：A 不该还挂在订阅者名单上，只剩 B
            try {
                assertEquals(1, stream.getSubscriberCount(), "⑤ A 断开之后还挂在订阅者名单上——断开没有退订");
            } catch (AssertionError e) {
                reds.add(e.getMessage());
            }
        } finally {
            // 🔴 放闩必须在这里、赶在 @AfterEach 的 shutdown 之前：闩不放，
            //    攥着锁的发布线程不走，shutdown 退订时就等在那把锁上，一直等到闩的时限
            slow.releaseClose();
            slow.release();
            if (publisher != null) {
                publisher.join(5_000);
            }
            if (probe != null) {
                probe.join(5_000);
            }
        }

        if (!reds.isEmpty()) {
            fail("红 " + reds.size() + " 问：\n" + String.join("\n", reds));
        }
    }

    private static JSONObject slowEnvelope() {
        JSONObject j = new JSONObject();
        j.put("v", 2);
        j.put("kind", "danmaku");
        j.put("ts", 1786111565000L);
        j.put("room", 10000);
        return j;
    }

    /**
     * 一个写下行时卡住不返回的会话：用来把出队那一侧堵死
     */
    private static class StalledSession extends NovaEventEndpointTest.FakeSession {
        private final java.util.concurrent.CountDownLatch block = new java.util.concurrent.CountDownLatch(1);

        private volatile boolean stalled;

        StalledSession(String id) {
            super(id);
        }

        @Override
        public void sendMessage(org.springframework.web.socket.WebSocketMessage<?> message) {
            if (!stalled) {
                super.sendMessage(message);
                return;
            }

            try {
                // 有界：这一格要的是「写不动」，不是「永远不返回」。
                // 无界的闩一旦等错了地方，失败的不是用例而是整个构建
                block.await(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * 从此刻起写不动
         */
        void stall() {
            stalled = true;
        }

        void release() {
            block.countDown();
        }
    }

    /**
     * 写不动、连关闭帧也写不动的会话：关闭卡在闩上（有界），并记下是哪条线程来关的
     */
    private static final class CloseStalledSession extends StalledSession {
        final java.util.concurrent.CountDownLatch closeEntered = new java.util.concurrent.CountDownLatch(1);

        private final java.util.concurrent.CountDownLatch closeGate = new java.util.concurrent.CountDownLatch(1);

        volatile String closeThread;

        CloseStalledSession(String id) {
            super(id);
        }

        @Override
        public void close(CloseStatus status) {
            if (closeThread == null) {
                closeThread = Thread.currentThread().getName();
            }
            closeEntered.countDown();
            try {
                // 有界，理由同上：等错了地方，失败的该是用例，不是整个构建
                closeGate.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            super.close(status);
        }

        void releaseClose() {
            closeGate.countDown();
        }
    }

    /**
     * 正常读的会话，另外记下带记号的那一帧到了没有
     */
    private static final class MarkedSession extends NovaEventEndpointTest.FakeSession {
        final java.util.concurrent.CountDownLatch marked = new java.util.concurrent.CountDownLatch(1);

        MarkedSession(String id) {
            super(id);
        }

        @Override
        public void sendMessage(org.springframework.web.socket.WebSocketMessage<?> message) {
            super.sendMessage(message);
            if (String.valueOf(message.getPayload()).contains(MARK)) {
                marked.countDown();
            }
        }
    }
}
