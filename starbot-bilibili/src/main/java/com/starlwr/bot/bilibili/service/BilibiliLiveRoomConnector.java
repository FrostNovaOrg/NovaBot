package com.starlwr.bot.bilibili.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.enums.ConnectStatus;
import com.starlwr.bot.bilibili.enums.DataHeaderType;
import com.starlwr.bot.bilibili.enums.DataPackType;
import com.starlwr.bot.bilibili.event.live.BilibiliConnectedEvent;
import com.starlwr.bot.bilibili.health.BilibiliDisconnectCause;
import com.starlwr.bot.bilibili.health.BilibiliDisconnectDigest;
import com.starlwr.bot.bilibili.health.BilibiliRiskMetrics;
import com.starlwr.bot.bilibili.event.live.BilibiliDisconnectedEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliLiveOffEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliLiveOnEvent;
import com.starlwr.bot.bilibili.model.ConnectAddress;
import com.starlwr.bot.bilibili.model.ConnectInfo;
import com.starlwr.bot.bilibili.protocol.BilibiliPacket;
import com.starlwr.bot.bilibili.protocol.BilibiliPacketCodec;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.event.live.StarBotBaseLiveEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 直播间长连接连接器
 * <p>
 * 负责与单个直播间的弹幕服务器保持长连接：建立连接、发送认证与心跳、接收并分发消息、断线重连。
 * 数据包的编解码由 {@link BilibiliPacketCodec} 承担，本类只关注连接生命周期。
 */
@Slf4j
public class BilibiliLiveRoomConnector extends BinaryWebSocketHandler {
    /**
     * 心跳发送间隔
     */
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);

    /**
     * 心跳响应超时时间，超过此时长未收到任何消息即判定连接已失效
     */
    private static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(90);

    /**
     * 重连退避的最大间隔
     */
    private static final Duration MAX_RECONNECT_INTERVAL = Duration.ofMinutes(5);

    /**
     * 观看心跳的上报间隔
     * <p>
     * 与心跳报文里声明的间隔保持一致——声明 60 就每 60 秒发一次。
     * 上游声明 60 却实发 30，我们按声明值发，更保守。
     */
    private static final Duration WATCH_HEARTBEAT_INTERVAL = Duration.ofSeconds(60);

    /**
     * WebSocket 关闭码 1006：连接异常中断且未收到关闭帧
     */
    private static final int ABNORMAL_CLOSURE = 1006;

    /**
     * 业务消息的 cmd 集合
     * <p>
     * 「业务消息」指主播真正关心、也是我们真正要采的那些：弹幕、礼物、上舰、醒目留言。
     * 进房、排行、点赞、看过人数这些属于环境消息，被限制下发时它们照样会来。
     */
    private static final Set<String> BUSINESS_COMMANDS = Set.of(
            "DANMU_MSG", "SEND_GIFT", "COMBO_SEND", "GUARD_BUY",
            "USER_TOAST_MSG", "USER_TOAST_MSG_V2", "SUPER_CHAT_MESSAGE");

    private final LiveStreamerInfo source;

    private final BilibiliApiUtil api;

    private final BilibiliEventParser parser;

    private final StarBotBilibiliProperties properties;

    private final ApplicationEventPublisher publisher;

    private final TaskScheduler scheduler;

    /**
     * 长连接客户端
     * <p>
     * 由外部传入并在所有直播间之间共享：每次 new 一个客户端都会创建独立的 WebSocket 容器与线程池，
     * 监听 N 个直播间就会产生 N 套线程池，在小内存机器上开销相当可观。
     */
    private final WebSocketClient client;

    private final BilibiliLiveStateGate stateGate;

    /**
     * 全局连接放行闸门。首连与重连都要经过它，否则多房间同时断线会叠成请求洪峰
     */
    private final BilibiliConnectGate connectGate;

    private final BilibiliRiskMetrics riskMetrics;

    private final BilibiliDisconnectDigest disconnectDigest;

    /**
     * 本条连接认证成功的时刻，未认证时为 null
     * <p>
     * 断线归因要用它算「这条连接活了多久」：认证都没过就断，与活了两小时才断，
     * 是两件完全不同的事，而关闭码把它们说成同一个 1006
     */
    private volatile Instant authenticatedAt;

    /**
     * 当前连接状态
     */
    @Getter
    private volatile ConnectStatus status = ConnectStatus.INIT;

    private volatile WebSocketSession session;

    private volatile ScheduledFuture<?> heartbeatTask;

    /**
     * 观看心跳任务，与长连接心跳分开：一个走 WebSocket 每 30 秒，一个走 HTTP 每 60 秒
     */
    private volatile ScheduledFuture<?> watchHeartbeatTask;

    /**
     * 最近一次收到消息的时间，用于判定连接是否已静默失效
     */
    private volatile Instant lastMessageTime = Instant.now();

    /**
     * 连续重连失败次数，用于计算退避间隔
     */
    private final AtomicInteger reconnectAttempts = new AtomicInteger();

    /**
     * 是否已被主动关闭，关闭后不再触发重连
     */
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 是否已有一次重连排在闸门里等着执行
     * <p>
     * 同一次断开会从两条路径各排一次队（详见 {@link #scheduleReconnect()}），
     * 这个标记把它们合成一次。执行那次重连时放开（{@link #runScheduledConnect()}）。
     */
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();

    /**
     * 被本端关掉的那条会话，用于断线归因
     * <p>
     * 本端主动关闭时容器回调给的关闭码是 1000，与服务端正常关闭无法区分，
     * 所以「是谁关的」只能由这里记着。建立新连接时清空：一条新会话上不该背着旧账。
     */
    private volatile WebSocketSession closedByUsSession;

    /**
     * 风控检测窗口内收到的消息总数
     */
    private final AtomicInteger totalMessages = new AtomicInteger();

    /**
     * 风控检测窗口内收到的进房类消息数，与业务消息一起构成样本量下限的分子
     */
    private final AtomicInteger interactMessages = new AtomicInteger();

    /**
     * 风控检测窗口内收到的业务消息数，这才是判据
     */
    private final AtomicInteger businessMessages = new AtomicInteger();

    /**
     * 跨窗口的判定器
     */
    private final BilibiliLiveRoomRiskDetector riskDetector;

    /**
     * 本段断流是否已经用掉那一次「先重连再判」的机会
     * <p>
     * 一段断流只重连一次。业务消息恢复后清零，下一段断流可以再用——
     * 不清零会让长时间运行的实例失去这道防线，一直清零则会变成断流期间反复重连。
     * <p>
     * 只在 {@code detectRisk} 里读写，而它由单线程的调度器串行调用，因此不用加锁。
     */
    private boolean reconnectedForStall;

    public BilibiliLiveRoomConnector(@NonNull LiveStreamerInfo source,
                                     @NonNull BilibiliApiUtil api,
                                     @NonNull BilibiliEventParser parser,
                                     @NonNull StarBotBilibiliProperties properties,
                                     @NonNull ApplicationEventPublisher publisher,
                                     @NonNull TaskScheduler scheduler,
                                     @NonNull WebSocketClient client,
                                     @NonNull BilibiliLiveStateGate stateGate,
                                     @NonNull BilibiliConnectGate connectGate,
                                     @NonNull BilibiliRiskMetrics riskMetrics,
                                     @NonNull BilibiliDisconnectDigest disconnectDigest) {
        this.source = source;
        this.api = api;
        this.parser = parser;
        this.properties = properties;
        this.publisher = publisher;
        this.scheduler = scheduler;
        this.client = client;
        this.stateGate = stateGate;
        this.connectGate = connectGate;
        this.riskMetrics = riskMetrics;
        this.disconnectDigest = disconnectDigest;
        this.riskDetector = new BilibiliLiveRoomRiskDetector(
                properties.getLive().getAutoDetectLiveRoomRiskWindows());
    }

    /**
     * 建立连接
     */
    public synchronized void connect() {
        if (closed.get() || status == ConnectStatus.CONNECTING || status == ConnectStatus.CONNECTED) {
            return;
        }

        status = ConnectStatus.CONNECTING;

        try {
            ConnectInfo info = api.getLiveRoomConnectInfo(source.getRoomId());
            if (!info.isAvailable()) {
                throw new IllegalStateException("未取得可用的弹幕服务器地址");
            }

            // 服务器地址列表按优先级排列，重连时轮换以避开单点故障
            List<ConnectAddress> addresses = info.getAddresses();
            ConnectAddress address = addresses.get(reconnectAttempts.get() % addresses.size());

            WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
            headers.add("User-Agent", properties.getNetwork().getUserAgent());
            headers.add("Origin", "https://live.bilibili.com");

            // 正常情况下 afterConnectionEstablished 已经把它赋好了，这里是兜底：
            // 万一某个客户端实现不在握手期回调，也不能让 session 空着。
            // 两次赋的是同一个对象，重复赋值无害
            this.session = client.execute(this, headers, URI.create(address.toWebSocketUrl())).get();
            sendVerify(info);

            // 认证包发出之后才开始心跳，保证它一定是这条连接上的第一个包
            startHeartbeat();
        } catch (Exception e) {
            log.error("连接直播间 {} 失败: {}", source.getRoomId(), e.getMessage());
            status = ConnectStatus.ERROR;
            scheduleReconnect();
        }
    }

    /**
     * 关闭连接，关闭后不再自动重连
     */
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        status = ConnectStatus.CLOSING;
        cancelHeartbeat();
        closeSession();
        status = ConnectStatus.CLOSED;
    }

    /**
     * 发送认证包
     * @param info 长连接信息，其中的 uid 与 token 取自同一瞬间
     * @throws IOException 发送失败时抛出
     */
    private void sendVerify(ConnectInfo info) throws IOException {
        JSONObject verify = new JSONObject();
        // uid 必须与取 token 时的身份一致，所以直接用 ConnectInfo 里那份快照，
        // 不要在这里重新读一次 api.getLoginUid()：本方法在 WebSocket 握手完成之后才执行，
        // 距离取 token 已经隔了一次网络往返，登录若恰好在这个窗口内完成就会两边对不上。
        // 服务端遇到不一致会握手后立刻切断且不发关闭帧——表现为 1006，
        // 重连再拿到新的绑定 token 又被拒，形成死循环（2026-08-04 实际发生过 96 次）
        Long identity = info.getUid();
        verify.put("uid", identity == null ? 0L : identity);
        verify.put("roomid", source.getRoomId());
        verify.put("protover", DataHeaderType.BROTLI_JSON.getCode());
        verify.put("platform", "web");
        verify.put("type", 2);
        verify.put("key", info.getToken());

        send(BilibiliPacketCodec.encode(DataPackType.VERIFY, verify.toJSONString()));
    }

    /**
     * 发送心跳包
     */
    private void sendHeartbeat() {
        if (closed.get()) {
            return;
        }

        // 超过超时时间未收到任何消息，说明连接已静默失效，主动重连
        if (Duration.between(lastMessageTime, Instant.now()).compareTo(HEARTBEAT_TIMEOUT) > 0) {
            log.warn("直播间 {} 超过 {} 秒未收到消息, 判定连接已失效", source.getRoomId(), HEARTBEAT_TIMEOUT.toSeconds());
            status = ConnectStatus.TIMEOUT;
            reconnect();
            return;
        }

        try {
            send(BilibiliPacketCodec.encode(DataPackType.HEARTBEAT, "[object Object]"));
        } catch (IOException e) {
            log.debug("直播间 {} 发送心跳失败: {}", source.getRoomId(), e.getMessage());
            reconnect();
        }
    }

    /**
     * 发送数据包
     * @param data 数据包
     * @throws IOException 发送失败时抛出
     */
    private void send(byte[] data) throws IOException {
        WebSocketSession current = session;
        if (current == null || !current.isOpen()) {
            throw new IOException("连接尚未建立或已断开");
        }

        synchronized (current) {
            current.sendMessage(new BinaryMessage(ByteBuffer.wrap(data)));
        }
    }

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) {
        log.info("已连接到直播间 {}", source.getRoomId());

        // 必须在这里就把会话记下来，不能等 connect() 里 client.execute().get() 返回。
        // 本回调发生在那个 future 完成之前，而它下面就要启动心跳，
        // 而 scheduleAtFixedRate 是「尽快开始」——第一次心跳完全可能跑在赋值之前。
        // 那时 send() 看到 null 会抛 IOException，被当成发送失败走进 reconnect()：
        // 轻则给一条刚建好的连接白排一次重连，重则 closeSession() 正好赶上赋值完成，
        // 把这条好连接直接掐掉。回调参数里的 session 就是权威的那一个，用它没有窗口。
        this.session = session;
        this.status = ConnectStatus.CONNECTED;
        this.lastMessageTime = Instant.now();
        // 新会话不背旧账：上一条会话若关了却没等到回调，那笔记录到此作废
        this.closedByUsSession = null;

        // 心跳<b>不在这里</b>启动，改由 connect() 在认证包发出之后启动。
        // 原因是把 session 提前赋值之后，这里启动的心跳就真的发得出去了——
        // 而 sendVerify 排在本回调之后，于是心跳会抢在认证包前面发出去。
        // 服务端要求认证包打头，未认证就发包同样会被切断，等于把一个竞态换成另一个。
        // 这一条是脚手架建成后第一次审启动时序就抓到的，测试 verifyMustBeTheFirstPacket 钉住了它。
        publisher.publishEvent(new BilibiliConnectedEvent(source));
    }

    @Override
    protected void handleBinaryMessage(@NonNull WebSocketSession session, BinaryMessage message) {
        lastMessageTime = Instant.now();
        // 退避计数在此清零而非握手完成时。握手成功不代表连接可用：认证被拒时服务端会
        // 握手后立刻切断，若在握手处清零，每次重连都从最短间隔重来，指数退避形同虚设——
        // 实测曾因此每秒重连约 10 次，最终把 getDanmuInfo 打成 -352 限流。
        // 服务端只在认证通过后才会下发数据包，故「收到消息」才是连接确实可用的证据
        reconnectAttempts.set(0);

        byte[] data = new byte[message.getPayload().remaining()];
        message.getPayload().get(data);

        BilibiliPacketCodec.Limits limits = new BilibiliPacketCodec.Limits(
                properties.getLive().getMaxDecompressedBytes(),
                properties.getLive().getMaxDecodeNestingDepth());

        for (BilibiliPacket packet : BilibiliPacketCodec.decode(data, limits)) {
            handlePacket(packet);
        }
    }

    /**
     * 处理单个数据包
     * @param packet 数据包
     */
    private void handlePacket(BilibiliPacket packet) {
        if (packet.getOperation() == DataPackType.VERIFY_SUCCESS_RESPONSE.getCode()) {
            log.debug("直播间 {} 认证成功", source.getRoomId());
            authenticatedAt = Instant.now();
            return;
        }

        if (packet.getOperation() == DataPackType.HEARTBEAT_RESPONSE.getCode()) {
            log.trace("直播间 {} 当前人气值 {}", source.getRoomId(), packet.getBodyAsInt());
            return;
        }

        if (packet.getOperation() != DataPackType.NOTICE.getCode()) {
            return;
        }

        JSONObject data;
        try {
            data = JSON.parseObject(packet.getBodyAsText());
        } catch (Exception e) {
            log.debug("直播间 {} 的消息不是合法 JSON, 已忽略", source.getRoomId());
            return;
        }

        countForRiskDetection(data);

        // 单条消息的处理失败不应影响同批次的其他消息
        try {
            parser.parse(data, source).ifPresent(this::publish);
        } catch (Exception e) {
            log.error("处理直播间 {} 的消息异常", source.getRoomId(), e);
        }
    }

    /**
     * 发布事件
     * @param event 事件
     */
    private void publish(StarBotBaseLiveEvent event) {
        // 开播与下播另有备用轮询这条发现路径，同一次状态变化只应推送一次，
        // 因此两条路径都要先过共享闸门。弹幕、礼物等事件只有长连接一条来源，不需要
        if (event instanceof BilibiliLiveOnEvent && !stateGate.admit(source.getUid(), true)) {
            log.debug("直播间 {} 的开播事件已由备用轮询推送, 跳过", source.getRoomId());
            return;
        }
        if (event instanceof BilibiliLiveOffEvent && !stateGate.admit(source.getUid(), false)) {
            log.debug("直播间 {} 的下播事件已由备用轮询推送, 跳过", source.getRoomId());
            return;
        }

        try {
            publisher.publishEvent(event);
        } catch (Exception e) {
            log.error("发布直播间 {} 的 {} 事件异常", source.getRoomId(), event.getClass().getSimpleName(), e);
        }
    }

    /**
     * 累计风控检测所需的计数
     * <p>
     * 判据是业务消息是否断流；进房类与业务一起构成样本量下限的分子，理由见
     * {@link BilibiliLiveRoomRiskDetector}。
     * @param data 消息内容
     */
    private void countForRiskDetection(JSONObject data) {
        if (!properties.getLive().isAutoDetectLiveRoomRisk()) {
            return;
        }

        totalMessages.incrementAndGet();
        String cmd = data.getString("cmd");
        if (cmd == null) {
            return;
        }
        if (cmd.startsWith("INTERACT_WORD")) {
            interactMessages.incrementAndGet();
        } else if (BUSINESS_COMMANDS.contains(cmd)) {
            businessMessages.incrementAndGet();
        }
    }

    /**
     * 执行一次风控检测，并重置计数窗口
     * @return 是否判定为风控
     */
    public boolean detectRisk() {
        if (!properties.getLive().isAutoDetectLiveRoomRisk() || status != ConnectStatus.CONNECTED) {
            return false;
        }

        // 是否在播必须一起交给判据：未开播的直播间没人发弹幕、业务消息必然为零，
        // 而排行、观看人数这些环境消息照旧在来，样本量下限挡不住，判据会稳定误报
        BilibiliLiveRoomRiskDetector.Window window = new BilibiliLiveRoomRiskDetector.Window(
                totalMessages.getAndSet(0), businessMessages.getAndSet(0), interactMessages.getAndSet(0),
                stateGate.isLiving(source.getUid()));

        if (window.business() > 0) {
            // 业务消息回来了，这一段断流结束，下一段可以重新用掉那次重连机会
            reconnectedForStall = false;
        }

        return riskDetector.accept(window).map(observation -> {
            // 先重连一次再判：重连是我们手上最便宜的动作（退避与闸门都现成），
            // 而它恰好就是区分「连接半死」与「平台真限制」的那个实验——
            // 半死的连接重连即恢复，真被限制时换一条连接照样收不到。
            // 少了这一步，一个重连就能自愈的故障会被报成平台问题，人也就白查一趟
            if (!reconnectedForStall) {
                reconnectedForStall = true;
                log.warn("直播间 {} 业务消息疑似断流, 先重连一次验证: {}", source.getRoomId(), observation);
                // 判定历史由 afterConnectionClosed 清空，重连后从零重新攒窗口
                reconnect();
                return false;
            }

            // 重连之后仍然断流，才升级为判定。只陈述观测到了什么，不断言原因——
            // 从这里分不清是平台限制了下发、协议变更导致业务消息解析不出来、
            // 还是主播那边确实没人说话但有人进出
            log.warn("直播间 {} 重连后业务消息仍然断流: {}", source.getRoomId(), observation);
            status = ConnectStatus.RISK;
            return true;
        }).orElse(false);
    }

    @Override
    public void handleTransportError(@NonNull WebSocketSession session, @NonNull Throwable exception) {
        log.debug("直播间 {} 连接异常: {}", source.getRoomId(), exception.getMessage());
        status = ConnectStatus.ERROR;
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus closeStatus) {
        cancelHeartbeat();

        // 断线后清空判定历史：跨连接累计会把重连前后的窗口混在一起
        riskDetector.reset();
        totalMessages.set(0);
        businessMessages.set(0);
        interactMessages.set(0);

        // 1006 是「连接被切断且没有关闭帧」。单次属正常抖动，成串出现才是风暴，
        // 计数交给健康探针按窗口判定，这里只如实记一笔
        if (closeStatus.getCode() == ABNORMAL_CLOSURE) {
            riskMetrics.record(BilibiliRiskMetrics.Kind.DISCONNECT_1006,
                    "直播间 " + source.getRoomId());
        }

        Instant authAt = authenticatedAt;
        Duration lived = authAt == null ? null : Duration.between(authAt, Instant.now());
        authenticatedAt = null;

        BilibiliDisconnectCause cause = BilibiliDisconnectCause.classify(
                closerOf(session), closeStatus.getCode(), authAt != null, lived);
        disconnectDigest.record(source.getRoomId(), cause, lived);

        if (closed.get()) {
            return;
        }

        // 逐次一行在断线风暴里数不清也看不出集中在哪，走 DEBUG；
        // 按窗口汇总的那条带归因的摘要由 BilibiliDisconnectDigest 打 WARN
        log.debug("与直播间 {} 的连接已断开 ({}, {}), 将尝试重连",
                source.getRoomId(), closeStatus.getCode(), cause.getLabel());
        status = ConnectStatus.CLOSED;

        publisher.publishEvent(new BilibiliDisconnectedEvent(source));
        scheduleReconnect();
    }

    /**
     * 立即重连
     * <p>
     * 关会话与排队两步都要做：会话已经不在（或从未建立）时容器不会回调
     * {@code afterConnectionClosed}，只关不排就再也不会重连了。
     * 重复排队由 {@link #scheduleReconnect()} 里的闸门挡住。
     */
    private void reconnect() {
        closeSession();
        scheduleReconnect();
    }

    /**
     * 按退避间隔安排一次重连
     * <p>
     * 连续失败时逐步拉长间隔，避免在服务端故障或本机断网时高频重试加重风控。
     * <p>
     * <b>一次断开只排一次。</b>{@code reconnect()} 先关会话再排队，而 {@code closeSession()}
     * 触发的容器回调 {@code afterConnectionClosed} 也会排一次，两条路径叠加让
     * {@code reconnectAttempts} <b>每断一次加 2</b>。2026-08-11 08:46:12 的日志里
     * 两条相隔 5 毫秒，是同一次断开：
     * <pre>
     * 08:46:12.865 直播间 500001 第 1 次重连，退避 1000 毫秒   ← 关闭回调排的
     * 08:46:12.870 直播间 500001 第 2 次重连，退避 2000 毫秒   ← reconnect() 自己排的
     * </pre>
     * 多排的那次本身是空转（第二个 {@code connect()} 看到状态已是 CONNECTING/CONNECTED
     * 就直接返回），真实代价在<b>后续</b>的重试上：计数虚高一级，于是从第二次真实重试起
     * 每次退避都是设计值的两倍，退避阶梯爬到上限所需的真实重试次数减半。
     * 同一段日志里下一次连接失败排的是「第 3 次、退避 4000 毫秒」，
     * 按设计本该是「第 2 次、退避 2000 毫秒」。
     */
    private void scheduleReconnect() {
        if (closed.get()) {
            return;
        }

        // 闸门在排出去的那次重连真的开始执行时打开（见 runScheduledConnect），
        // 而不是在握手成功时——握手前的这段时间里再来几次断开回调都只是同一次断开
        if (!reconnectScheduled.compareAndSet(false, true)) {
            log.debug("直播间 {} 已有待执行的重连, 不再重复排队", source.getRoomId());
            return;
        }

        int attempts = reconnectAttempts.incrementAndGet();
        long base = Math.max(1000, properties.getLive().getLiveRoomReconnectInterval());
        long delay = Math.min(base * (1L << Math.min(attempts - 1, 8)), MAX_RECONNECT_INTERVAL.toMillis());

        try {
            // 退避是本房间自己的节奏，闸门再把它和别的房间排到同一条时间轴上，
            // 两者叠加：既不会比退避更早重连，也不会和其它房间挤在同一瞬间
            Instant at = connectGate.submit(this::runScheduledConnect, Instant.now().plusMillis(delay));
            log.debug("直播间 {} 第 {} 次重连，退避 {} 毫秒，闸门放行于 {}",
                    source.getRoomId(), attempts, delay, at);
        } catch (RuntimeException e) {
            // 排不进去（如停机时调度器已拒收）就得把闸门放回去：
            // 留在「已排队」状态而实际没有任务，等于这个房间从此不再重连
            reconnectScheduled.set(false);
            throw e;
        }
    }

    /**
     * 执行一次排队中的重连
     * <p>
     * 先放开「已排队」的闸门再连：{@code connect()} 失败时会同步调
     * {@code scheduleReconnect()} 排下一次，闸门必须在那之前就已经开着，
     * 否则一次失败之后这个房间就永远不再重连了。
     */
    private void runScheduledConnect() {
        reconnectScheduled.set(false);
        connect();
    }

    /**
     * 启动心跳任务
     */
    private void startHeartbeat() {
        cancelHeartbeat();
        heartbeatTask = scheduler.scheduleAtFixedRate(this::sendHeartbeat, HEARTBEAT_INTERVAL);
        // 观看心跳只在连接存活期间上报：连接断了就不再声称自己在看
        watchHeartbeatTask = scheduler.scheduleAtFixedRate(
                () -> api.liveRoomHeartbeat(source.getRoomId(),
                        (int) WATCH_HEARTBEAT_INTERVAL.toSeconds()),
                WATCH_HEARTBEAT_INTERVAL);
    }

    /**
     * 取消心跳任务
     */
    private void cancelHeartbeat() {
        ScheduledFuture<?> task = heartbeatTask;
        if (task != null) {
            task.cancel(false);
            heartbeatTask = null;
        }

        ScheduledFuture<?> watch = watchHeartbeatTask;
        if (watch != null) {
            watch.cancel(false);
            watchHeartbeatTask = null;
        }
    }

    /**
     * 关闭当前会话
     * <p>
     * 关掉之前先记下「这一条是本端关的」。容器随后回调 {@code afterConnectionClosed}
     * 时给的关闭码是 <b>1000</b>，与服务端正常关闭一模一样——
     * 「是谁关的」这个信息不在关闭码里，只有这里知道。
     */
    private void closeSession() {
        WebSocketSession current = session;
        session = null;

        if (current != null && current.isOpen()) {
            // 记的是会话对象本身而不是一个布尔：布尔会在「关闭回调迟迟不来」时留给
            // 下一次断开，把平台关的说成我们关的。按对象比对就没有这个窗口
            closedByUsSession = current;
            try {
                current.close();
            } catch (IOException e) {
                log.debug("关闭直播间 {} 的连接时发生异常: {}", source.getRoomId(), e.getMessage());
            }
        }
    }

    /**
     * 判断这次关闭是谁发起的
     * <p>
     * 顺序有讲究：已被永久关闭时一律算停止监听，哪怕它同时也是本端关的会话——
     * 停止监听不是故障，不该进断线摘要。
     */
    private BilibiliDisconnectCause.Closer closerOf(WebSocketSession closedSession) {
        boolean byUs = closedSession.equals(closedByUsSession);
        if (byUs) {
            closedByUsSession = null;
        }

        if (closed.get()) {
            return BilibiliDisconnectCause.Closer.US_STOPPING;
        }
        return byUs
                ? BilibiliDisconnectCause.Closer.US_RECONNECTING
                : BilibiliDisconnectCause.Closer.PLATFORM;
    }

    /**
     * 获取所连接的直播间信息
     * @return 直播间信息
     */
    public LiveStreamerInfo getSource() {
        return source;
    }
}
