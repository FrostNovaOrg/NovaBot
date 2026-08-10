package com.starlwr.bot.bilibili.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.enums.DataPackType;
import com.starlwr.bot.bilibili.health.BilibiliRiskMetrics;
import com.starlwr.bot.bilibili.model.ConnectAddress;
import com.starlwr.bot.bilibili.model.ConnectInfo;
import com.starlwr.bot.bilibili.protocol.BilibiliPacket;
import com.starlwr.bot.bilibili.protocol.BilibiliPacketCodec;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import org.mockito.stubbing.Answer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 直播间连接器测试脚手架（Phase 1.5 第 6 项）
 * <p>
 * 连接器此前几乎没有测试，原因是它的行为全在时序上：
 * 握手回调、认证包、心跳、断线、重连各自跑在不同线程，
 * 而**出错的地方恰恰是这些线程的交错次序**。已经栽过两次：
 * <ul>
 *     <li>{@code 8eafd67}：握手包的 uid 与 token 取自不同瞬间，
 *         登录若落在这个窗口内就发出「匿名 token + 登录 uid」，被服务端切断（1006）</li>
 *     <li>启动心跳竞态：{@code afterConnectionEstablished} 回调时 {@code this.session}
 *         尚未赋值，首个心跳拿到 null 就把刚建好的连接掐掉</li>
 * </ul>
 * <p>
 * <b>本脚手架刻意做成单线程的。</b>用真线程去撞竞态窗口写出来的测试必然是概率性的——
 * 时好时坏的测试比没有测试更坏，它会训练人去重跑而不是去看。
 * 这里改为把「谁先谁后」变成显式可控的调用：
 * <ul>
 *     <li>{@link #fireHeartbeatWhenScheduled} —— 让调度器在 {@code scheduleAtFixedRate}
 *         的那一刻就同步跑第一次心跳。这不是人为构造的极端情况：
 *         Spring 的 {@code scheduleAtFixedRate} 本来就是「尽快开始」，
 *         真实环境里第一次心跳确实可能跑在 {@code this.session} 赋值之前</li>
 *     <li>{@link #callbackDuringHandshake} —— 握手回调发生在 {@code execute()} 的
 *         future 完成之前，这是真实顺序（默认开启）</li>
 * </ul>
 * 于是竞态变成确定性的：要么必然复现，要么必然不复现，没有中间态。
 */
class BilibiliConnectorHarness {
    static final long ROOM_ID = 20000001L;

    static final long STREAMER_UID = 3000003L;

    /** 取 token 那一瞬间的身份：匿名 */
    static final long SNAPSHOT_UID = 0L;

    /** 握手往返期间「登录完成」后的身份，用于证明认证包没有去重新读它 */
    static final long LOGGED_IN_UID = 500000002L;

    static final String TOKEN = "snapshot-token";

    private final BilibiliApiUtil api = mock(BilibiliApiUtil.class);

    private final BilibiliEventParser parser = mock(BilibiliEventParser.class);

    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

    private final TaskScheduler scheduler = mock(TaskScheduler.class);

    private final WebSocketClient client = mock(WebSocketClient.class);

    private final BilibiliLiveStateGate stateGate = mock(BilibiliLiveStateGate.class);

    private final BilibiliConnectGate connectGate = mock(BilibiliConnectGate.class);

    private final BilibiliRiskMetrics riskMetrics = mock(BilibiliRiskMetrics.class);

    private final WebSocketSession session = mock(WebSocketSession.class);

    private final StarBotBilibiliProperties properties = new StarBotBilibiliProperties();

    /** 连接器发出去的原始字节，按顺序 */
    private final List<byte[]> sent = new ArrayList<>();

    /** 被 scheduleAtFixedRate 收下的任务，按登记顺序 */
    private final List<Runnable> scheduled = new ArrayList<>();

    /** 交给闸门排队的重连任务 */
    private final List<Runnable> queuedReconnects = new ArrayList<>();

    private final AtomicInteger handshakes = new AtomicInteger();

    private boolean sessionOpen = true;

    private boolean sessionClosed;

    /** 握手回调是否发生在 future 完成之前（真实顺序） */
    private boolean callbackDuringHandshake = true;

    /** 调度器是否在登记的那一刻就同步跑一次——用来复现启动心跳竞态 */
    private boolean fireHeartbeatWhenScheduled;

    /** 下一次 execute() 是否直接失败 */
    private boolean failNextHandshake;

    private final BilibiliLiveRoomConnector connector;

    BilibiliConnectorHarness() {
        LiveStreamerInfo source = new LiveStreamerInfo(STREAMER_UID, "测试主播", ROOM_ID);

        stubSession();
        stubApi();
        stubScheduler();
        stubClient();
        stubConnectGate();

        this.connector = new BilibiliLiveRoomConnector(source, api, parser, properties, publisher,
                scheduler, client, stateGate, connectGate, riskMetrics);
    }

    // ================ 配置 ================

    /**
     * 让调度器在登记心跳的那一刻就同步执行一次
     * <p>
     * 这是复现启动心跳竞态的开关：{@code startHeartbeat()} 在
     * {@code afterConnectionEstablished} 里调用，而该回调发生在 {@code this.session}
     * 赋值之前，因此这一次心跳看到的 session 是 null。
     */
    BilibiliConnectorHarness heartbeatFiresOnSchedule() {
        this.fireHeartbeatWhenScheduled = true;
        return this;
    }

    /**
     * 让握手回调推迟到 {@code execute()} 的 future 完成之后
     * <p>
     * 与真实顺序相反，仅用于对照：证明竞态确实来自回调与赋值的先后，而不是别的原因。
     */
    BilibiliConnectorHarness callbackAfterHandshake() {
        this.callbackDuringHandshake = false;
        return this;
    }

    BilibiliConnectorHarness failNextHandshake() {
        this.failNextHandshake = true;
        return this;
    }

    // ================ 驱动 ================

    BilibiliLiveRoomConnector connector() {
        return connector;
    }

    void connect() {
        connector.connect();
    }

    /**
     * 手工跑一次已登记的心跳任务
     */
    void fireHeartbeat() {
        if (scheduled.isEmpty()) {
            throw new IllegalStateException("还没有登记过心跳任务");
        }
        scheduled.get(0).run();
    }

    /**
     * 手工触发握手回调（用于 {@link #callbackAfterHandshake()} 的场景）
     */
    void fireConnectionEstablished() throws Exception {
        connector.afterConnectionEstablished(session);
    }

    /**
     * 跑掉闸门里排着的重连任务
     */
    void runQueuedReconnects() {
        List<Runnable> pending = new ArrayList<>(queuedReconnects);
        queuedReconnects.clear();
        pending.forEach(Runnable::run);
    }

    // ================ 观测 ================

    /**
     * 握手次数，即 {@code client.execute()} 被调用的次数
     */
    int handshakes() {
        return handshakes.get();
    }

    /**
     * 排队等待重连的任务数
     */
    int queuedReconnects() {
        return queuedReconnects.size();
    }

    /**
     * 会话是否被关掉过
     */
    boolean sessionClosed() {
        return sessionClosed;
    }

    /**
     * 发出去的包，已解码
     */
    List<BilibiliPacket> sentPackets() {
        List<BilibiliPacket> packets = new ArrayList<>();
        for (byte[] data : sent) {
            packets.addAll(BilibiliPacketCodec.decode(data));
        }
        return packets;
    }

    /**
     * 认证包的内容，没发出过则为空
     */
    Optional<JSONObject> verifyPacket() {
        return sentPackets().stream()
                .filter(p -> p.getOperation() == DataPackType.VERIFY.getCode())
                .findFirst()
                .map(p -> JSON.parseObject(p.getBodyAsText()));
    }

    /**
     * 已发出的心跳包数量
     */
    long heartbeatsSent() {
        return sentPackets().stream().filter(p -> p.getOperation() == DataPackType.HEARTBEAT.getCode()).count();
    }

    // ================ 打桩 ================

    private void stubSession() {
        when(session.getId()).thenReturn("harness-session");
        when(session.isOpen()).thenAnswer(invocation -> sessionOpen);

        try {
            doAnswer(invocation -> {
                BinaryMessage message = invocation.getArgument(0);
                ByteBuffer payload = message.getPayload();
                byte[] copy = new byte[payload.remaining()];
                payload.duplicate().get(copy);
                sent.add(copy);
                return null;
            }).when(session).sendMessage(any());

            doAnswer(invocation -> {
                sessionClosed = true;
                sessionOpen = false;
                return null;
            }).when(session).close();
        } catch (IOException e) {
            // sendMessage 与 close 声明了 IOException，打桩时必须处理；打桩本身不会真的抛
            throw new IllegalStateException("打桩失败", e);
        }
    }

    private void stubApi() {
        ConnectAddress address = new ConnectAddress();
        address.setHost("harness.chat.bilibili.com");
        address.setPort(2243);
        address.setWssPort(443);
        address.setWsPort(2244);

        ConnectInfo info = new ConnectInfo();
        info.setToken(TOKEN);
        info.setUid(SNAPSHOT_UID);
        info.setAddresses(new ArrayList<>(List.of(address)));

        when(api.getLiveRoomConnectInfo(anyLong())).thenReturn(info);

        // 「取完 token 之后登录完成了」：实时身份与快照不一致。
        // 认证包若去重新读这个值，就会发出 token 与 uid 对不上的组合——8eafd67 修的就是它
        when(api.getLoginUid()).thenReturn(LOGGED_IN_UID);
        // liveRoomHeartbeat 返回 void，mock 默认就是空实现，不需要打桩
    }

    private void stubScheduler() {
        Answer<ScheduledFuture<?>> answer = invocation -> {
            Runnable task = invocation.getArgument(0);
            scheduled.add(task);

            // 真实调度器的 scheduleAtFixedRate 是「尽快开始」，第一次可以立刻跑。
            // 打开这个开关就把那一瞬间固定下来，用于确定性地复现启动竞态
            if (fireHeartbeatWhenScheduled) {
                task.run();
            }

            return mock(ScheduledFuture.class);
        };

        when(scheduler.scheduleAtFixedRate(any(Runnable.class), any(Duration.class))).thenAnswer(answer);
    }

    private void stubClient() {
        when(client.execute(any(WebSocketHandler.class), any(WebSocketHttpHeaders.class), any(URI.class)))
                .thenAnswer(invocation -> {
                    handshakes.incrementAndGet();

                    if (failNextHandshake) {
                        failNextHandshake = false;
                        return CompletableFuture.failedFuture(new IllegalStateException("握手失败"));
                    }

                    sessionOpen = true;

                    // 真实顺序：底层先回调 afterConnectionEstablished，
                    // 之后 execute() 的 future 才完成、connect() 才把 session 赋给字段
                    if (callbackDuringHandshake) {
                        WebSocketHandler handler = invocation.getArgument(0);
                        handler.afterConnectionEstablished(session);
                    }

                    return CompletableFuture.completedFuture(session);
                });
    }

    private void stubConnectGate() {
        when(connectGate.submit(any(Runnable.class), any())).thenAnswer(invocation -> {
            queuedReconnects.add(invocation.getArgument(0));
            return Instant.EPOCH;
        });
        when(connectGate.submit(any(Runnable.class))).thenAnswer(invocation -> {
            queuedReconnects.add(invocation.getArgument(0));
            return Instant.EPOCH;
        });
    }
}
