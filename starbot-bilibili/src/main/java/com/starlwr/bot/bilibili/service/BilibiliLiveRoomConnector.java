package com.starlwr.bot.bilibili.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.enums.ConnectStatus;
import com.starlwr.bot.bilibili.enums.DataHeaderType;
import com.starlwr.bot.bilibili.enums.DataPackType;
import com.starlwr.bot.bilibili.event.live.BilibiliConnectedEvent;
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
     * 风控检测窗口内收到的消息总数
     */
    private final AtomicInteger totalMessages = new AtomicInteger();

    /**
     * 风控检测窗口内收到的进房类消息数
     */
    private final AtomicInteger interactMessages = new AtomicInteger();

    public BilibiliLiveRoomConnector(@NonNull LiveStreamerInfo source,
                                     @NonNull BilibiliApiUtil api,
                                     @NonNull BilibiliEventParser parser,
                                     @NonNull StarBotBilibiliProperties properties,
                                     @NonNull ApplicationEventPublisher publisher,
                                     @NonNull TaskScheduler scheduler,
                                     @NonNull WebSocketClient client,
                                     @NonNull BilibiliLiveStateGate stateGate,
                                     @NonNull BilibiliConnectGate connectGate,
                                     @NonNull BilibiliRiskMetrics riskMetrics) {
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

            this.session = client.execute(this, headers, URI.create(address.toWebSocketUrl())).get();
            sendVerify(info);
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

        this.status = ConnectStatus.CONNECTED;
        this.lastMessageTime = Instant.now();

        startHeartbeat();
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

        for (BilibiliPacket packet : BilibiliPacketCodec.decode(data)) {
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
     * 直播间被风控时，弹幕、礼物等消息会被屏蔽，长连接上只剩进房消息。
     * 因此以进房消息在总消息中的占比作为风控判据。
     * @param data 消息内容
     */
    private void countForRiskDetection(JSONObject data) {
        if (!properties.getLive().isAutoDetectLiveRoomRisk()) {
            return;
        }

        totalMessages.incrementAndGet();
        String cmd = data.getString("cmd");
        if (cmd != null && cmd.startsWith("INTERACT_WORD")) {
            interactMessages.incrementAndGet();
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

        int total = totalMessages.getAndSet(0);
        int interact = interactMessages.getAndSet(0);

        // 样本过少时不足以判断，避免冷清的直播间被误判
        if (total < 10) {
            return false;
        }

        int ratio = interact * 100 / total;
        if (ratio < properties.getLive().getAutoDetectLiveRoomRiskRatio()) {
            return false;
        }

        log.warn("直播间 {} 的进房消息占比达到 {}%, 判定为直播间数据风控", source.getRoomId(), ratio);
        status = ConnectStatus.RISK;

        return true;
    }

    @Override
    public void handleTransportError(@NonNull WebSocketSession session, @NonNull Throwable exception) {
        log.debug("直播间 {} 连接异常: {}", source.getRoomId(), exception.getMessage());
        status = ConnectStatus.ERROR;
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus closeStatus) {
        cancelHeartbeat();

        // 1006 是「连接被切断且没有关闭帧」。单次属正常抖动，成串出现才是风暴，
        // 计数交给健康探针按窗口判定，这里只如实记一笔
        if (closeStatus.getCode() == ABNORMAL_CLOSURE) {
            riskMetrics.record(BilibiliRiskMetrics.Kind.DISCONNECT_1006,
                    "直播间 " + source.getRoomId());
        }

        if (closed.get()) {
            return;
        }

        log.info("与直播间 {} 的连接已断开 ({}), 将尝试重连", source.getRoomId(), closeStatus.getCode());
        status = ConnectStatus.CLOSED;

        publisher.publishEvent(new BilibiliDisconnectedEvent(source));
        scheduleReconnect();
    }

    /**
     * 立即重连
     */
    private void reconnect() {
        closeSession();
        scheduleReconnect();
    }

    /**
     * 按退避间隔安排一次重连
     * <p>
     * 连续失败时逐步拉长间隔，避免在服务端故障或本机断网时高频重试加重风控。
     */
    private void scheduleReconnect() {
        if (closed.get()) {
            return;
        }

        int attempts = reconnectAttempts.incrementAndGet();
        long base = Math.max(1000, properties.getLive().getLiveRoomReconnectInterval());
        long delay = Math.min(base * (1L << Math.min(attempts - 1, 8)), MAX_RECONNECT_INTERVAL.toMillis());

        // 退避是本房间自己的节奏，闸门再把它和别的房间排到同一条时间轴上，
        // 两者叠加：既不会比退避更早重连，也不会和其它房间挤在同一瞬间
        Instant at = connectGate.submit(this::connect, Instant.now().plusMillis(delay));
        log.debug("直播间 {} 第 {} 次重连，退避 {} 毫秒，闸门放行于 {}",
                source.getRoomId(), attempts, delay, at);
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
     */
    private void closeSession() {
        WebSocketSession current = session;
        session = null;

        if (current != null && current.isOpen()) {
            try {
                current.close();
            } catch (IOException e) {
                log.debug("关闭直播间 {} 的连接时发生异常: {}", source.getRoomId(), e.getMessage());
            }
        }
    }

    /**
     * 获取所连接的直播间信息
     * @return 直播间信息
     */
    public LiveStreamerInfo getSource() {
        return source;
    }
}
