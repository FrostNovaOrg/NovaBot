package com.starlwr.bot.adapter.onebot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.adapter.onebot.config.OneBotAdapterPluginProperties;
import com.starlwr.bot.adapter.onebot.model.OneBotSender;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.adapter.onebot.health.OneBotConnectionState;
import com.starlwr.bot.core.alert.AlertService;
import com.starlwr.bot.core.event.remote.StarBotRemoteMessageEvent;
import com.starlwr.bot.core.util.StringUtil;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

/**
 * OneBot Websocket 服务
 */
@Slf4j
@StarBotComponent
public class OneBotWebsocketService {
    private final TaskScheduler taskScheduler;

    private final ThreadPoolTaskExecutor executor;

    private final OneBotAdapterPluginProperties properties;

    private final AlertService alertService;

    private final OneBotConnectionState state;

    /**
     * 构建信息，用于回复 status 指令时取真实版本号
     * <p>
     * 不要写死版本字符串：写死的那份不会随 pom 的版本变化，升版本后仍报旧号，
     * 而这类偏差没有任何测试会发现
     */
    private final BuildProperties buildProperties;

    /**
     * 收到聊天消息时发布远程消息事件，供各平台模块实现消息命令
     */
    private final ApplicationEventPublisher publisher;

    private final Map<String, ScheduledFuture<?>> detectTasks = new HashMap<>();

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    @Autowired
    public OneBotWebsocketService(TaskScheduler taskScheduler, @Qualifier("oneBotThreadPool") ThreadPoolTaskExecutor executor, OneBotAdapterPluginProperties properties, AlertService alertService, OneBotConnectionState state, BuildProperties buildProperties, ApplicationEventPublisher publisher) {
        this.taskScheduler = taskScheduler;
        this.executor = executor;
        this.properties = properties;
        this.alertService = alertService;
        this.state = state;
        this.buildProperties = buildProperties;
        this.publisher = publisher;
    }

    /**
     * 连接 OneBot Websocket
     */
    @Order(-10000)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReadyEvent() {
        for (OneBotSender sender : properties.getSenders()) {
            if (sender.isWebsocket()) {
                if (StringUtil.isBlank(sender.getOneBotWebsocketToken())) {
                    log.error("推送平台 {} 未配置 OneBot Websocket Token, 请完善配置", sender.getName());
                    state.websocketDisconnected(sender.getName(), "未配置 Websocket Token");
                    continue;
                }

                connect(sender);
            } else {
                state.websocketDisabled(sender.getName());
            }
        }
    }

    /**
     * 连接到 OneBot Websocket 服务
     * @param sender OneBot 推送平台信息
     */
    public void connect(OneBotSender sender) {
        executor.submit(() -> {
            int retryCount = 0;
            int retryInterval = 1;
            while (true) {
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
                    OneBotWebSocketHandler handler = new OneBotWebSocketHandler(this, sender);
                    sessionFuture = webSocketClient.execute(handler, headers, URI.create(url));

                    if (handler.awaitConnection()) {
                        sessionFuture.get();
                        break;
                    } else {
                        throw new TimeoutException();
                    }
                } catch (Exception e) {
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
     * Websocket 消息接收检测
     * @param handler WebSocket 处理器
     */
    private void startDetect(OneBotWebSocketHandler handler) {
        String platformName = handler.sender.getName();

        if (detectTasks.containsKey(platformName)) {
            detectTasks.get(platformName).cancel(false);
            detectTasks.remove(platformName);
        }

        int detectInterval = properties.getDetect().getWebsocketDetectInterval();

        ScheduledFuture<?> detectTask = taskScheduler.scheduleAtFixedRate(() -> executor.submit(() -> {
            if (Instant.now().minusSeconds(detectInterval).isBefore(handler.lastReceiveTime)) {
                alertService.resolve("onebot-ws:" + platformName);
                return;
            }

            String alarm = "推送平台 " + platformName + " 的 OneBot Websocket 在 " + formatter.format(handler.lastReceiveTime) + " ~ " + formatter.format(Instant.now()) + " 期间未收到任何消息, 请检查服务状态及连接情况";
            log.warn(alarm);

            // 收敛交由告警服务统一处理，此处不再各自维护一套间隔逻辑
            alertService.alert("onebot-ws:" + platformName, "NovaBot OneBot Websocket 连接异常告警", alarm);
        }), Instant.now().plusSeconds(detectInterval), Duration.ofSeconds(detectInterval));

        detectTasks.put(platformName, detectTask);
    }

    /**
     * WebSocket 处理器
     */
    private static class OneBotWebSocketHandler implements WebSocketHandler {
        private final OneBotWebsocketService service;

        private final OneBotSender sender;

        private final ThreadPoolTaskExecutor executor;

        private final CountDownLatch latch = new CountDownLatch(1);

        private final StringBuilder messageBuffer = new StringBuilder();

        private boolean connectTimeout = false;

        private Boolean tokenVerify = null;

        private Instant lastReceiveTime = Instant.now();

        private OneBotWebSocketHandler(OneBotWebsocketService service, OneBotSender sender) {
            this.service = service;
            this.sender = sender;
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
            latch.countDown();

            synchronized (this) {
                if (connectTimeout) {
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
                                            lastReceiveTime = Instant.now();
                                            service.startDetect(this);
                                        }
                                    }
                                }

                                if (service.properties.getDetect().isEnableWebsocketDetect() && "message".equals(rawMessage.getString("post_type"))) {
                                    lastReceiveTime = Instant.now();
                                }

                                if ("message".equals(rawMessage.getString("post_type"))
                                        && StringUtil.isNotBlank(rawMessage.getString("raw_message"))) {
                                    String messageType = rawMessage.getString("message_type");
                                    Long num = "group".equals(messageType)
                                            ? rawMessage.getLong("group_id")
                                            : rawMessage.getLong("user_id");
                                    service.publisher.publishEvent(new StarBotRemoteMessageEvent(
                                            sender.getName(), messageType, num,
                                            rawMessage.getLong("user_id"), rawMessage.getString("raw_message")));
                                }

                                if ("status".equalsIgnoreCase(rawMessage.getString("raw_message"))) {
                                    JSONObject operation = new JSONObject();
                                    operation.put("reply", "Running on NovaBot v" + service.buildProperties.getVersion());

                                    JSONObject params = new JSONObject();
                                    params.put("context", rawMessage);
                                    params.put("operation", operation);

                                    JSONObject response = new JSONObject();
                                    response.put("action", ".handle_quick_operation");
                                    response.put("params", params);

                                    session.sendMessage(new TextMessage(response.toJSONString()));
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
         * 传输错误
         * @param session WebSocket 会话
         * @param exception 异常
         */
        @Override
        public void handleTransportError(@NonNull WebSocketSession session, @NonNull Throwable exception) {
            executor.submit(() -> {
                log.warn("与 {} 的 Websocket 连接异常, 将在 1 秒后重新连接", sender.getName(), exception);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("重新连接 {} 的 Websocket 时中断", sender.getName(), e);
                }
                service.connect(sender);
            });
        }

        /**
         * 连接关闭
         * @param session WebSocket 会话
         * @param closeStatus 关闭状态
         */
        @Override
        public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus closeStatus) {
            if (connectTimeout) {
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
                service.connect(sender);
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
