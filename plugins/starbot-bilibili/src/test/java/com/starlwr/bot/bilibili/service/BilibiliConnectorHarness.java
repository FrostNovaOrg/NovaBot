package com.starlwr.bot.bilibili.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.enums.DataPackType;
import com.starlwr.bot.bilibili.health.BilibiliDisconnectCause;
import com.starlwr.bot.bilibili.health.BilibiliDisconnectDigest;
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
import org.springframework.web.socket.CloseStatus;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
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
    static final long ROOM_ID = 47731877194803L;

    static final long STREAMER_UID = 3000003L;

    /** 取 token 那一瞬间的身份：匿名 */
    static final long SNAPSHOT_UID = 0L;

    /** 握手往返期间「登录完成」后的身份，用于证明认证包没有去重新读它 */
    static final long LOGGED_IN_UID = 19313558846753L;

    static final String TOKEN = "snapshot-token";

    private final BilibiliApiUtil api = mock(BilibiliApiUtil.class);

    private final BilibiliEventParser parser = mock(BilibiliEventParser.class);

    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

    private final TaskScheduler scheduler = mock(TaskScheduler.class);

    private final WebSocketClient client = mock(WebSocketClient.class);

    private final BilibiliLiveStateGate stateGate = mock(BilibiliLiveStateGate.class);

    private final BilibiliConnectGate connectGate = mock(BilibiliConnectGate.class);

    private final BilibiliRiskMetrics riskMetrics = mock(BilibiliRiskMetrics.class);

    /**
     * 断线摘要用真实对象而不是 mock：断线归因正是这个脚手架该看得见的东西。
     * 在构造体里赋值而不是在字段上——properties 与 scheduler 声明在后面，
     * 字段初始化器里引用它们是非法前向引用
     */
    private final BilibiliDisconnectDigest disconnectDigest;

    /**
     * 单房断线缺口的记录去处。用 mock 是为了能断言「到底记没记」——
     * 这一项一旦没接上，归档里的缺口就永远是 0，而 0 和「真的没断过」长得一模一样
     */
    private final com.starlwr.bot.core.service.LiveDataService liveDataService =
            org.mockito.Mockito.mock(com.starlwr.bot.core.service.LiveDataService.class);

    /** 摘要收下的归因，按顺序。归因错的方向只有从这里才看得出来 */
    private final List<BilibiliDisconnectCause> recordedCauses = new ArrayList<>();

    private final WebSocketSession session = mock(WebSocketSession.class);

    private final StarBotBilibiliProperties properties = new StarBotBilibiliProperties();

    /** 连接器发出去的原始字节，按顺序 */
    private final List<byte[]> sent = new ArrayList<>();

    /** 被 scheduleAtFixedRate 收下的任务，按登记顺序 */
    private final List<Runnable> scheduled = new ArrayList<>();

    /** 交给闸门排队的重连任务 */
    private final List<Runnable> queuedReconnects = new ArrayList<>();

    /**
     * 每次排队时连接器要求的「最早不得早于此刻」，与 {@link #queuedReconnects} 一一对应
     * <p>
     * 退避时长只出现在这个参数里（日志之外没有别的出口），而退避算错正是
     * 「重连排两次队」那个缺陷唯一能被看见的地方——排队次数多一次是空转的，
     * 拖长的间隔才是真代价。
     */
    private final List<Instant> reconnectDeadlines = new ArrayList<>();

    private final AtomicInteger handshakes = new AtomicInteger();

    private boolean sessionOpen = true;

    private boolean sessionClosed;

    /** 握手回调是否发生在 future 完成之前（真实顺序） */
    private boolean callbackDuringHandshake = true;

    /** 调度器是否在登记的那一刻就同步跑一次——用来复现启动心跳竞态 */
    private boolean fireHeartbeatWhenScheduled;

    /** 下一次 execute() 是否直接失败 */
    private boolean failNextHandshake;

    /** 下一次发包是否直接失败 */
    private boolean failNextSend;

    private final BilibiliLiveRoomConnector connector;

    BilibiliConnectorHarness() {
        LiveStreamerInfo source = new LiveStreamerInfo(STREAMER_UID, "测试主播", ROOM_ID);

        // 真实对象外面套一层 spy 只为把归因抄下来，逻辑仍走真实实现——
        // 归因指错方向这类缺陷，只有看「实际记下了哪一类」才能发现，
        // 看条数是看不出来的（错标同样会被记一条）
        this.disconnectDigest = spy(new BilibiliDisconnectDigest(properties, scheduler));
        doAnswer(invocation -> {
            recordedCauses.add(invocation.getArgument(1));
            return invocation.callRealMethod();
        }).when(disconnectDigest).record(anyLong(), any(BilibiliDisconnectCause.class), any());

        stubSession();
        stubApi();
        stubParser();
        stubScheduler();
        stubClient();
        stubConnectGate();

        this.connector = new BilibiliLiveRoomConnector(source, api, parser, properties, publisher,
                scheduler, client, stateGate, connectGate, riskMetrics, disconnectDigest, liveDataService);
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

    /**
     * 让下一次发包失败，用来走 {@code sendHeartbeat()} 里的 {@code reconnect()} 分支
     * <p>
     * 心跳还有一条更贴近实况的入口——「超过 90 秒没收到消息」——但那条要拨时钟，
     * 会把这个刻意做成单线程确定性的脚手架拖回到靠时间赌运气。
     * 两条入口进的是同一个 {@code reconnect()}，用发包失败这条即可。
     * <p>
     * <b>必须在 {@code connect()} 之后调用</b>：认证包也走 send()，
     * 提前打开会让首次连接就失败，测的就不是重连了。
     */
    BilibiliConnectorHarness failNextSend() {
        this.failNextSend = true;
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
     * 让连接器收到一条消息，走真实的解码与计数路径
     * <p>
     * 不直接改计数字段：断流判据的分子取决于 {@code cmd} 怎么被归类，
     * 而那段归类逻辑正是要测的东西之一。绕过它等于把判据的一半打了桩。
     * @param cmd 消息的 cmd，例如 {@code DANMU_MSG} 或 {@code ONLINE_RANK_COUNT}
     */
    void receive(String cmd) {
        byte[] encoded = BilibiliPacketCodec.encode(DataPackType.NOTICE,
                "{\"cmd\":\"" + cmd + "\"}");
        try {
            connector.handleMessage(session, new BinaryMessage(encoded));
        } catch (Exception e) {
            // 收消息这条路上抛异常本身就是缺陷，别让调用方每处都写 throws 把它藏进签名里
            throw new IllegalStateException("喂消息时连接器抛了异常: " + cmd, e);
        }
    }

    /**
     * 让解析器把某个 cmd 的消息报成「解析失败」（事件照旧为空、降级标志为真）。
     * <p>
     * 复现协议变更的形状：包还在收、归类还在走，只是解析不出来。
     * 具体桩要压在构造体里的默认桩之上，因此只对指名的 cmd 生效
     * @param cmd 要标成解析失败的 cmd 名
     */
    void parseDegradedFor(String cmd) {
        when(parser.parseMessage(argThat((JSONObject data) -> cmd.equals(data.getString("cmd"))), any()))
                .thenAnswer(invocation -> new BilibiliEventParser.ParsedMessage(Optional.empty(), true));
    }

    /** 断线缺口记到哪儿去了，供断言 */
    com.starlwr.bot.core.service.LiveDataService getLiveDataService() {
        return liveDataService;
    }

    /**
     * 模拟服务端回「认证成功」
     * <p>
     * 采集缺口的终点是<b>认证成功</b>而不是 TCP 连上——认证之前服务端不发业务消息，
     * 那段时间同样什么都没收到。所以测缺口必须走这一条，不能只 fireConnectionEstablished。
     */
    void fireVerifySuccess() {
        byte[] encoded = BilibiliPacketCodec.encode(DataPackType.VERIFY_SUCCESS_RESPONSE, "{}");
        try {
            connector.handleMessage(session, new BinaryMessage(encoded));
        } catch (Exception e) {
            throw new IllegalStateException("喂认证成功包时连接器抛了异常", e);
        }
    }

    /**
     * 让主播处于在播状态。未开播时判据一律不判定，测断流必须先把这一条摘掉
     */
    BilibiliConnectorHarness living() {
        when(stateGate.isLiving(STREAMER_UID)).thenReturn(true);
        return this;
    }

    /**
     * 模拟容器回调「连接已关闭」
     * <p>
     * 连接器自己调 reconnect() 时只关会话、不改状态——状态要等容器把这个回调送回来才变。
     * 测试里不补这一步，connect() 会因为状态还是 CONNECTED 而直接返回，看起来像「重连没发生」。
     * @param code 关闭码，1000 为正常关闭
     */
    void fireConnectionClosed(int code) {
        sessionOpen = false;
        connector.afterConnectionClosed(session, new CloseStatus(code));
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
     * 连接器算出来、交给摘要的归因，按顺序
     * <p>
     * 抄的是<b>入参</b>，所以 {@code BY_US} 也会出现在这里——摘要本身会把它丢掉，
     * 而这里要看的是「连接器认为这次是谁关的」，那正是错标发生的地方。
     */
    List<BilibiliDisconnectCause> recordedCauses() {
        return List.copyOf(recordedCauses);
    }

    /**
     * 连接器一共排过几次队（含已经跑掉的）
     * <p>
     * 与 {@link #queuedReconnects()} 不同：那个数只算还没跑的，
     * 而「同一次断开排了两次队」这件事在跑掉之后就看不见了。
     */
    int scheduleCount() {
        return reconnectDeadlines.size();
    }

    /**
     * 最近一次排队时连接器要求的退避时长（从排队那一刻算起，四舍五入到秒）
     * <p>
     * 用秒而不是毫秒比较：脚手架跑在真实时钟上，从连接器算出 {@code now} 到
     * 这里读到参数之间会过去几毫秒，按毫秒断言就是一条时好时坏的测试。
     * 退避的量级是秒，秒级精度足够把「一个间隔」和「两个间隔」分开。
     * <p>
     * <b>刻意只给「最近一次」，不给按下标取。</b>按下标取会写出「碰巧通过」的断言：
     * 重连排两次队时，下标 1 恰好就是那次多余排队，而它的退避正好等于
     * 修好之后第二次真实重试的值——于是断言在修好之前也是绿的。
     * 第一版这四条测试里就有一条这样白过了，靠对照跑（把修改 stash 掉再跑一遍）才发现。
     */
    long lastBackoffSeconds() {
        if (reconnectDeadlines.isEmpty()) {
            throw new IllegalStateException("还没有排过队");
        }

        Instant deadline = reconnectDeadlines.get(reconnectDeadlines.size() - 1);
        if (deadline == null) {
            throw new IllegalStateException("最近一次排队没有附带退避时刻");
        }
        return Math.round(Duration.between(Instant.now(), deadline).toMillis() / 1000.0);
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
                if (failNextSend) {
                    failNextSend = false;
                    throw new IOException("发送失败");
                }

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

    /**
     * 解析器默认桩：一切消息都解析成功且不产出事件（与旧 {@code parse} 的默认行为等价）。
     * <p>
     * {@code parseMessage} 返回的是 record，mock 对没打桩的方法返回 null——
     * 不给这条默认桩的话，连接器数消息那一步会静默 NPE，所有断流测试一起失真
     */
    private void stubParser() {
        when(parser.parseMessage(any(), any())).thenAnswer(invocation ->
                new BilibiliEventParser.ParsedMessage(Optional.empty(), false));
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
            reconnectDeadlines.add(invocation.getArgument(1));
            return Instant.EPOCH;
        });
        when(connectGate.submit(any(Runnable.class))).thenAnswer(invocation -> {
            queuedReconnects.add(invocation.getArgument(0));
            reconnectDeadlines.add(null);
            return Instant.EPOCH;
        });
    }
}
