package org.frostnova.nova.adapter.onebot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.adapter.onebot.config.OneBotAdapterPluginProperties;
import org.frostnova.nova.adapter.onebot.converter.OneBotIncomingMessage;
import org.frostnova.nova.adapter.onebot.model.OneBotSender;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.adapter.onebot.health.OneBotConnectionState;
import org.frostnova.nova.adapter.onebot.health.OneBotLivenessTracker;
import org.frostnova.nova.core.event.remote.NovaRemoteMessageEvent;
import org.frostnova.nova.core.lang.StringUtil;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

/**
 * OneBot Websocket 服务
 */
@Slf4j
@NovaComponent
public class OneBotWebsocketService {
    /**
     * 存活检测的执行周期
     */
    private static final Duration CHECK_INTERVAL = Duration.ofSeconds(30);

    private final TaskScheduler taskScheduler;

    private final ThreadPoolTaskExecutor executor;

    private final OneBotAdapterPluginProperties properties;

    private final OneBotConnectionState state;

    /**
     * 收到聊天消息时发布远程消息事件，供各平台模块实现消息命令
     * <p>
     * 适配器对聊天消息只做这一件事。<b>不在这里直接回话</b>：那样会绕过核心统一把关的几条
     * 横切约束——有没有 @、这个会话配没配推送、冷却、菜单，一条都不过。
     */
    private final ApplicationEventPublisher publisher;

    /**
     * 各推送平台当前在用的那一代连接
     * <p>
     * 连接、重连与断开分别发生在不同线程上，注册与取消都可能并发发生，因此不能用普通 HashMap
     */
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    /**
     * 已提示过「未推送心跳」的推送平台
     */
    private final Set<String> noHeartbeatWarned = ConcurrentHashMap.newKeySet();

    @Autowired
    public OneBotWebsocketService(TaskScheduler taskScheduler, @Qualifier("oneBotThreadPool") ThreadPoolTaskExecutor executor, OneBotAdapterPluginProperties properties, OneBotConnectionState state, ApplicationEventPublisher publisher) {
        this.taskScheduler = taskScheduler;
        this.executor = executor;
        this.properties = properties;
        this.state = state;
        this.publisher = publisher;
    }

    /**
     * 连接 OneBot Websocket
     */
    @Order(-10000)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReadyEvent() {
        for (OneBotSender sender : properties.getSenders()) {
            start(sender);
        }
    }

    /**
     * 按这个推送平台<b>当前的</b>配置把 Websocket 接上
     * <p>
     * 🔴 <b>启动那条路走的也是这里。</b>「开机时连一次」与「运行期改了配置再连一次」
     * 若各写一份，两份迟早分家——而分家之后，从界面上配出来的那条连接与从配置文件里
     * 起出来的那条会在细节上不一样（补不补检测任务、Token 空了怎么记），
     * 且只在其中一条路上表现出来。
     * <p>
     * 反复调用是正常用法（使用者会连按保存）：每次都先把旧的那一代断掉，
     * 因此调三次之后仍然只有<b>一条</b>连接。
     * @param sender OneBot 推送平台信息
     */
    public synchronized void start(OneBotSender sender) {
        // 先断旧，且不论接下来连不连得成：改成「不启用 Websocket」之后旧连接还挂着的话，
        // 界面上写着未启用而消息照收，那是最难看出来的一种不一致
        stop(sender.getName());

        if (!sender.isWebsocket()) {
            state.websocketDisabled(sender.getName());
            return;
        }

        if (StringUtil.isBlank(sender.getOneBotWebsocketToken())) {
            log.warn("推送平台 {} 尚未配置 OneBot Websocket Token, 配好前推送不可用", sender.getName());
            state.websocketDisconnected(sender.getName(), "未配置 Websocket Token");
            return;
        }

        Connection connection = new Connection(sender);
        connections.put(sender.getName(), connection);
        connect(connection);
    }

    /**
     * 断开这个推送平台的 Websocket，并回收它的重连与存活检测任务
     * <p>
     * 作废整一代而不是只关一个套接字：正在退避等待的重试循环、已经发出去还没回来的握手、
     * 挂着的存活检测，都属于这一代。只关套接字的话，那条重试循环会<b>接着往旧地址重连</b>，
     * 并把状态改回它自己那一份——界面上于是看到新地址连上了又断、断了又连。
     * @param platformName 推送平台名
     */
    public synchronized void stop(String platformName) {
        Connection previous = connections.remove(platformName);
        if (previous == null) {
            return;
        }

        previous.retired = true;
        stopDetect(previous);

        WebSocketSession session = previous.session;
        if (session != null && session.isOpen()) {
            try {
                session.close();
            } catch (Exception e) {
                log.warn("断开 {} 的旧 OneBot Websocket 连接异常", platformName, e);
            }
        }
    }

    /**
     * 连接到 OneBot Websocket 服务
     * @param connection 这一代连接
     */
    private void connect(Connection connection) {
        OneBotSender sender = connection.sender;
        executor.submit(() -> {
            int retryCount = 0;
            int retryInterval = 1;
            while (!connection.retired) {
                log.info("准备连接 {} 的 OneBot Websocket 服务", sender.getName());
                log.info("{} 的 OneBot Websocket 连接地址: ws://{}:{}/", sender.getName(), sender.getOneBotAddress(), sender.getOneBotWebsocketPort());

                CompletableFuture<WebSocketSession> sessionFuture = null;
                try {
                    String url = String.format("ws://%s:%d", sender.getOneBotAddress(), sender.getOneBotWebsocketPort());

                    WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
                    headers.add("Authorization", "Bearer " + sender.getOneBotWebsocketToken());

                    WebSocketContainer container = ContainerProvider.getWebSocketContainer();
                    container.setDefaultMaxTextMessageBufferSize(8 * 1024 * 1024);
                    StandardWebSocketClient webSocketClient = new StandardWebSocketClient(container);
                    OneBotWebSocketHandler handler = new OneBotWebSocketHandler(this, connection);
                    sessionFuture = webSocketClient.execute(handler, headers, URI.create(url));

                    if (handler.awaitConnection()) {
                        sessionFuture.get();
                        break;
                    } else {
                        throw new TimeoutException();
                    }
                } catch (Exception e) {
                    if (connection.retired) {
                        log.info("已停止重连 {} 的 OneBot Websocket 服务", sender.getName());
                        break;
                    }

                    retryCount++;
                    retryInterval = Math.min(retryInterval * 2, 60);

                    if (e instanceof TimeoutException) {
                        log.warn("连接 {} 的 OneBot Websocket 服务超时, 将在 {} 秒后进行第 {} 次重试", sender.getName(), retryInterval, retryCount);
                        state.websocketDisconnected(sender.getName(), "连接超时，正在重试（第 " + retryCount + " 次）");
                        // 该 Future 的任务就运行在当前线程上，以 true 取消会把自己的中断标志置位，
                        // 使随后的退避等待立刻抛出 InterruptedException，退化为满核空转的重试循环
                        sessionFuture.cancel(false);
                    } else {
                        log.error("{} 的 OneBot Websocket 服务不可用, 请检查配置和服务状态, 将在 {} 秒后进行第 {} 次重试", sender.getName(), retryInterval, retryCount, e);
                        state.websocketDisconnected(sender.getName(), "连接失败，正在重试（第 " + retryCount + " 次）: " + e.getMessage());
                    }

                    try {
                        Thread.sleep(retryInterval * 1000L);
                    } catch (InterruptedException ex) {
                        // 收到中断即视为要求停止重连：恢复中断标志后退出循环。
                        // 若仅恢复标志而继续循环，下一次等待会立即再次抛出，导致线程持续空转
                        Thread.currentThread().interrupt();
                        log.info("已停止重连 {} 的 OneBot Websocket 服务", sender.getName());
                        break;
                    }
                }
            }
        });
    }

    /**
     * 造一个不连任何东西的处理器，供判据直接驱动
     * <p>
     * 判据要量的是「收到一条上报之后适配器做了什么」，而那件事只发生在处理器里。
     * 有这个口，判据就不必按名字去反射一个私有构造器——那种判据认的是写法：
     * 构造器多一个参数它就整个跑不起来，报出来的还是一句与被测行为毫无关系的
     * {@code NoSuchMethodException}。
     * @param sender OneBot 推送平台信息
     * @return 处理器
     */
    WebSocketHandler handlerFor(OneBotSender sender) {
        return new OneBotWebSocketHandler(this, new Connection(sender));
    }

    /**
     * 一个推送平台的一代 Websocket 连接
     * <p>
     * 断旧连新时整代作废：连接本身、正在退避的重试循环、存活检测任务都挂在这上面。
     * 作废之后这一代不再改动连接状态——否则一条已经被换掉的连接，会拿它自己的死讯
     * 去覆盖新连接刚写下的「已连接」。
     */
    private static final class Connection {
        private final OneBotSender sender;

        private volatile boolean retired;

        private volatile WebSocketSession session;

        private volatile ScheduledFuture<?> detectTask;

        private Connection(OneBotSender sender) {
            this.sender = sender;
        }
    }

    /**
     * Websocket 存活检测
     * <p>
     * 检测周期与判定超时是两件事：判定超时按心跳间隔取（见 {@link OneBotLivenessTracker}），
     * 而检查本身只是读两个时间戳，足够便宜，因此固定按 {@link #CHECK_INTERVAL} 频繁地查，
     * 以免超时早已到达却要等到下一个检测周期才被发现。
     * @param handler WebSocket 处理器
     */
    private void startDetect(OneBotWebSocketHandler handler) {
        Connection connection = handler.connection;
        String platformName = connection.sender.getName();
        stopDetect(connection);

        Duration timeout = Duration.ofSeconds(properties.getDetect().getWebsocketSilenceTimeout());

        connection.detectTask = taskScheduler.scheduleAtFixedRate(() -> executor.submit(() -> {
            if (connection.retired) {
                return;
            }

            OneBotLivenessTracker.Verdict verdict = handler.liveness.evaluate(Instant.now(), timeout);

            switch (verdict.state()) {
                case ALIVE -> state.websocketConnected(platformName);
                case SILENT -> {
                    String silence = formatSilence(verdict.silence());
                    log.warn("推送平台 {} 的 OneBot Websocket 已 {} 未收到任何数据帧（含心跳）, 连接很可能已失效", platformName, silence);
                    // 告警统一由健康探针与 HealthAlertMonitor 发出，此处只记录状态
                    state.websocketDisconnected(platformName, "已 " + silence + " 未收到任何数据帧（含心跳）");
                }
                case NO_HEARTBEAT -> warnNoHeartbeat(platformName);
            }
        }), Instant.now().plus(CHECK_INTERVAL), CHECK_INTERVAL);
    }

    /**
     * 停止某一代连接的存活检测
     * <p>
     * 连接断开后必须停掉：检测任务持有的是旧连接的计时器，留着会在重连期间按旧数据把状态改回「已连接」，
     * 覆盖掉「正在重连」的真实状态。
     * <p>
     * 🔴 挂在<b>这一代</b>上而不是按平台名记一份：换地址时旧连接的关闭回调常常晚于新连接建立，
     * 按平台名去停，停掉的会是刚刚挂好的那一个——而它此后再也不检测，界面上却一切正常。
     * @param connection 这一代连接
     */
    private void stopDetect(Connection connection) {
        ScheduledFuture<?> previous = connection.detectTask;
        if (previous != null) {
            previous.cancel(false);
            connection.detectTask = null;
        }
    }

    /**
     * 提示该实现未推送心跳
     * <p>
     * 每个周期都喊一遍只会淹没日志，这里每个推送平台只提示一次。
     */
    private void warnNoHeartbeat(String platformName) {
        if (noHeartbeatWarned.add(platformName)) {
            log.warn("推送平台 {} 的 OneBot 实现未推送心跳, 无法据此判断长连接是否已静默失效; " +
                    "请在 OneBot 实现中开启心跳（NapCat 为 heartInterval），否则只能依赖 HTTP 检测", platformName);
        }
    }

    /**
     * 把静默时长写成人话
     */
    private String formatSilence(Duration silence) {
        long minutes = silence.toMinutes();
        return minutes > 0 ? minutes + " 分钟" : silence.toSeconds() + " 秒";
    }

    /**
     * WebSocket 处理器
     */
    private static class OneBotWebSocketHandler implements WebSocketHandler {
        private final OneBotWebsocketService service;

        private final Connection connection;

        private final OneBotSender sender;

        private final ThreadPoolTaskExecutor executor;

        private final CountDownLatch latch = new CountDownLatch(1);

        private final StringBuilder messageBuffer = new StringBuilder();

        private boolean connectTimeout = false;

        private Boolean tokenVerify = null;

        /**
         * 是否已就「上报里没有 self_id」报过一次
         */
        private boolean selfIdWarned = false;

        private final OneBotLivenessTracker liveness = new OneBotLivenessTracker(Instant.now());

        private OneBotWebSocketHandler(OneBotWebsocketService service, Connection connection) {
            this.service = service;
            this.connection = connection;
            this.sender = connection.sender;
            this.executor = service.executor;
        }

        /**
         * 等待 WebSocket 连接成功
         * @return 连接是否成功
         */
        public boolean awaitConnection() {
            synchronized (this) {
                try {
                    if (latch.await(3, TimeUnit.SECONDS)) {
                        return true;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                connectTimeout = true;
                return false;
            }
        }

        /**
         * 连接建立
         * @param session WebSocket 会话
         */
        @Override
        public void afterConnectionEstablished(@NonNull WebSocketSession session) {
            connection.session = session;
            latch.countDown();

            synchronized (this) {
                // 这一代已经被换掉时同样要关：断旧连新那一刻这个握手可能正在路上，
                // 关不掉的话，旧地址上会留着一条谁也不认识、却照常收消息的连接
                if (connectTimeout || connection.retired) {
                    try {
                        session.close();
                    } catch (Exception e) {
                        log.error("断开 {} 的超时 OneBot Websocket 服务异常", sender.getName(), e);
                    }
                    return;
                }
            }

            service.state.websocketConnected(sender.getName());
            executor.submit(() -> log.info("已连接到 {} 的 OneBot Websocket 服务", sender.getName()));
        }

        /**
         * 消息处理
         * @param session WebSocket 会话
         * @param webSocketRawMessage WebSocket 消息
         */
        @Override
        public void handleMessage(@NonNull WebSocketSession session, @NonNull WebSocketMessage<?> webSocketRawMessage) {
            // 收到任何东西都说明链路是通的，因此在解析之前就先计时：内容能不能解析、是不是聊天消息，
            // 与「连接还活着吗」是两个问题
            liveness.frameReceived(Instant.now());

            try {
                if (webSocketRawMessage instanceof TextMessage webSocketMessage) {
                    messageBuffer.append(webSocketMessage.getPayload());

                    if (webSocketMessage.isLast()) {
                        String fullMessage = messageBuffer.toString();
                        messageBuffer.setLength(0);

                        executor.submit(() -> {
                            try {
                                JSONObject rawMessage = JSON.parseObject(fullMessage);
                                if (tokenVerify == null) {
                                    if ("1403".equals(rawMessage.getString("retcode"))) {
                                        tokenVerify = false;
                                        log.error("{} 的 OneBot Websocket Token 配置不正确, 将无法处理消息, 请检查 Token 配置", sender.getName());
                                    }
                                    if ("meta_event".equals(rawMessage.getString("post_type")) && "lifecycle".equals(rawMessage.getString("meta_event_type")) && "connect".equals(rawMessage.getString("sub_type"))) {
                                        tokenVerify = true;
                                        log.info("{} 的 OneBot Websocket Token 认证成功", sender.getName());

                                        if (service.properties.getDetect().isEnableWebsocketDetect()) {
                                            service.startDetect(this);
                                        }
                                    }
                                }

                                if ("meta_event".equals(rawMessage.getString("post_type"))
                                        && "heartbeat".equals(rawMessage.getString("meta_event_type"))) {
                                    handleHeartbeat(rawMessage);
                                }

                                if ("message".equals(rawMessage.getString("post_type"))
                                        && StringUtil.isNotBlank(rawMessage.getString("raw_message"))) {
                                    String messageType = rawMessage.getString("message_type");
                                    Long num = "group".equals(messageType)
                                            ? rawMessage.getLong("group_id")
                                            : rawMessage.getLong("user_id");
                                    // sender.role 取值为 owner / admin / member，管理类命令据此判权限。
                                    // 私聊没有群角色一说，取不到即为空
                                    JSONObject messageSender = rawMessage.getJSONObject("sender");
                                    String senderRole = messageSender == null ? null : messageSender.getString("role");

                                    warnOnceIfSelfIdMissing(rawMessage, messageType);
                                    OneBotIncomingMessage incoming = OneBotIncomingMessage.of(rawMessage);

                                    service.publisher.publishEvent(new NovaRemoteMessageEvent(
                                            sender.getName(), messageType, num,
                                            rawMessage.getLong("user_id"), incoming.text(),
                                            senderRole, incoming.mentionsBot()));
                                }
                            } catch (Exception e) {
                                log.error("处理 {} 的 OneBot Websocket 消息时发生异常", sender.getName(), e);
                                messageBuffer.setLength(0);
                            }
                        });
                    }
                }
            } catch (Exception e) {
                log.error("处理 {} 的 OneBot Websocket 分片消息发生异常", sender.getName(), e);
                messageBuffer.setLength(0);
            }
        }

        /**
         * 群消息里缺 {@code self_id} 时报一次
         * <p>
         * 群聊命令要靠「@ 的是不是我」来判，而这一判需要知道自己的账号。缺了它，
         * <b>全部群聊命令会一起失灵，且不报任何错</b>——机器人只是从此不再应答，
         * 谁也说不上来是从哪一刻起变成这样的。所以在这里响一声；
         * 每条连接只响一次，免得把日志刷满。
         * @param rawMessage 收到的消息事件
         * @param messageType 消息类型
         */
        private void warnOnceIfSelfIdMissing(JSONObject rawMessage, String messageType) {
            if (selfIdWarned || !"group".equals(messageType) || rawMessage.getLong("self_id") != null) {
                return;
            }
            selfIdWarned = true;
            log.warn("{} 上报的群消息里没有 self_id, 无法判断消息是否 @ 了机器人, 群聊命令将不会响应", sender.getName());
        }

        /**
         * 处理心跳
         * <p>
         * 心跳除了证明链路还在，还顺带捎了一份 {@code status.online}：账号掉线时 OneBot 实现本身一切正常，
         * 接口照常返回，只是消息谁也收不到。靠这个字段能在半分钟内发现，而定时轮询接口最长要等一个检测周期。
         * @param rawMessage 心跳消息
         */
        private void handleHeartbeat(JSONObject rawMessage) {
            Long interval = rawMessage.getLong("interval");
            liveness.heartbeatReceived(Instant.now(), interval == null ? 0L : interval);

            JSONObject status = rawMessage.getJSONObject("status");
            Boolean online = status == null ? null : status.getBoolean("online");
            if (online == null) {
                // 字段缺失只说明这个实现不上报，不能据此断定账号掉线
                return;
            }

            if (online) {
                service.state.accountOnline(sender.getName(), "在线");
            } else {
                service.state.accountOffline(sender.getName(), "QQ 账号已掉线");
            }
        }

        /**
         * 传输错误
         * @param session WebSocket 会话
         * @param exception 异常
         */
        @Override
        public void handleTransportError(@NonNull WebSocketSession session, @NonNull Throwable exception) {
            if (connection.retired) {
                return;
            }

            executor.submit(() -> {
                log.warn("与 {} 的 Websocket 连接异常, 将在 1 秒后重新连接", sender.getName(), exception);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("重新连接 {} 的 Websocket 时中断", sender.getName(), e);
                }
                if (!connection.retired) {
                    service.connect(connection);
                }
            });
        }

        /**
         * 连接关闭
         * @param session WebSocket 会话
         * @param closeStatus 关闭状态
         */
        @Override
        public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus closeStatus) {
            // 存活检测认的是这个连接的计时器，连接没了就得停，否则它会拿旧数据把状态改回「已连接」
            service.stopDetect(connection);

            // 这一代是被主动换掉的：既不重连，也不写连接状态。写了的话，
            // 一条已经作废的连接会拿它的死讯覆盖新连接刚记下的「已连接」
            if (connectTimeout || connection.retired) {
                return;
            }

            if (Boolean.FALSE.equals(tokenVerify)) {
                service.state.websocketDisconnected(sender.getName(), "Token 校验失败");
                return;
            }

            service.state.websocketDisconnected(sender.getName(),
                    "连接断开（" + closeStatus.getCode() + "），正在重连");

            executor.submit(() -> {
                log.warn("与 {} 的 Websocket 连接断开 ({}: {}), 将在 1 秒后重新连接", sender.getName(), closeStatus.getCode(), closeStatus.getReason());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("重新连接 {} 的 Websocket 时中断", sender.getName(), e);
                }
                if (!connection.retired) {
                    service.connect(connection);
                }
            });
        }

        /**
         * 是否支持部分消息
         * @return 是否支持部分消息
         */
        @Override
        public boolean supportsPartialMessages() {
            return true;
        }
    }
}
