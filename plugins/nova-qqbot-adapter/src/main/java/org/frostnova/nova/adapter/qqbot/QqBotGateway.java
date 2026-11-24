package org.frostnova.nova.adapter.qqbot;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.health.HealthStatus;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@NovaComponent
final class QqBotGateway {
    private static final int GROUP_AND_C2C_EVENT = 1 << 25;
    private static final long MAX_BACKOFF_MILLIS = 60_000;
    private static final long STABLE_CONNECTION_MILLIS = 60_000;
    private static final long OUTAGE_MILLIS = 5 * 60_000;
    private static final long HANDSHAKE_TIMEOUT_MILLIS = 30_000;
    private static final long RATE_LIMIT_DELAY_MILLIS = 5_000;
    private static final HealthStatus CONNECTING = HealthStatus.degraded("正在连接 QQ 开放平台",
            "稍后查看连接状态");
    private static final HealthStatus OUTAGE = HealthStatus.down("连不上 QQ 开放平台已超过 5 分钟",
            "请检查本机网络能否访问 QQ 开放平台", "unreachable");

    private final QqBotApiClient api;
    private final QqBotTokenService tokens;
    private final QqBotWebSocketTransport transport;
    private final QqBotGatewayScheduler scheduler;
    private final ObjectProvider<QqBotEventListener> listeners;
    private final AtomicReference<QqBotWebSocketTransport.Connection> socket = new AtomicReference<>();
    private volatile Status status = new Status(null, CONNECTING, null, null);
    private volatile boolean closed;

    // 除关闭连接与探针读数外，网关状态由调度线程串行维护。
    private QqBotSettings.Credentials settings;
    private Attempt attempt;
    private Future<?> retry;
    private int retryCount;
    private String sessionId;
    private String robotName;
    private Long sequence;

    QqBotGateway(QqBotApiClient api, QqBotTokenService tokens, QqBotWebSocketTransport transport,
                 QqBotGatewayScheduler scheduler, ObjectProvider<QqBotEventListener> listeners) {
        this.api = api;
        this.tokens = tokens;
        this.transport = transport;
        this.scheduler = scheduler;
        this.listeners = listeners;
    }

    void credentialsChanged() {
        var snapshot = tokens.settings().credentials();
        schedule(() -> {
            if (snapshot == tokens.settings().credentials()) {
                reset(snapshot);
            }
        }, 0);
    }

    void start() {
        var snapshot = tokens.settings().credentials();
        schedule(() -> {
            if (closed || snapshot != tokens.settings().credentials()) {
                return;
            }
            reset(snapshot);
            if (snapshot.configured() && attempt == null && retry == null
                    && status.health().level() != HealthStatus.Level.DOWN) {
                if (tokens.health().level() == HealthStatus.Level.OK) {
                    connect();
                } else {
                    retry(0);
                }
            }
        }, 0);
    }

    HealthStatus health() {
        Status current = status;
        return health(current, current.settings() == tokens.settings().credentials());
    }

    ConnectionState connectionState() {
        Status current = status;
        boolean sameSettings = current.settings() == tokens.settings().credentials();
        return new ConnectionState(health(current, sameSettings), sameSettings ? current.robotName() : null);
    }

    private HealthStatus health(Status current, boolean sameSettings) {
        if (sameSettings && current.health().level() == HealthStatus.Level.DOWN) {
            return current.health();
        }
        HealthStatus tokenHealth = tokens.health();
        if (tokenHealth.level() != HealthStatus.Level.OK) {
            return tokenHealth;
        }
        if (!sameSettings) {
            return CONNECTING;
        }
        if (current.disconnectedAt() != null && scheduler.nowMillis() - current.disconnectedAt() >= OUTAGE_MILLIS) {
            return OUTAGE;
        }
        return current.health();
    }

    private void reset(QqBotSettings.Credentials snapshot) {
        if (snapshot != settings) {
            disconnect();
            cancel(retry);
            retry = null;
            retryCount = 0;
            forgetSession();
            settings = snapshot;
            status = new Status(settings, CONNECTING, null, null);
        }
    }

    private void connect() {
        if (closed || settings != tokens.settings().credentials()) {
            return;
        }
        var next = new Attempt();
        attempt = next;
        try {
            tokens.token();
            Endpoint endpoint = discover();
            if (!isCurrent(next)) {
                return;
            }
            if (endpoint.waitMillis() > 0) {
                attempt = null;
                scheduleConnect(endpoint.waitMillis());
                return;
            }
            socket.set(transport.connect(endpoint.uri(), next));
            if (!isCurrent(next)) {
                closeSocket();
                return;
            }
            watchHandshake(next);
        } catch (IOException | RuntimeException e) {
            log.debug("未能建立 QQ 开放平台长连接");
            connectionFailed(next);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            connectionFailed(next);
        }
    }

    private Endpoint discover() {
        try {
            return endpoint("/gateway/bot");
        } catch (QqBotApiException | IllegalArgumentException e) {
            return endpoint("/gateway");
        }
    }

    private Endpoint endpoint(String path) {
        if (api.request("GET", path, null) instanceof JSONObject response && response.get("url") instanceof String url) {
            URI uri = URI.create(url);
            if ("wss".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getFragment() == null) {
                if (response.get("session_start_limit") instanceof JSONObject limit
                        && Long.valueOf(0).equals(number(limit.get("remaining")))) {
                    Long resetAfter = number(limit.get("reset_after"));
                    return new Endpoint(uri, resetAfter != null && resetAfter > 0 ? resetAfter : 1_000);
                }
                return new Endpoint(uri, 0);
            }
        }
        throw new IllegalArgumentException("QQ 开放平台未返回可用的长连接地址");
    }

    private void receive(Attempt source, String text) {
        if (!isCurrent(source)) {
            return;
        }
        JSONObject frame;
        try {
            frame = JSON.parseObject(text);
        } catch (RuntimeException e) {
            log.debug("收到无法读取的 QQ 开放平台报文");
            return;
        }
        if (frame == null || !(frame.get("op") instanceof Number op)) {
            return;
        }
        switch (op.intValue()) {
            case 0 -> dispatch(source, frame);
            case 7 -> reconnect(source, true, 0);
            case 9 -> reconnect(source, Boolean.TRUE.equals(frame.get("d")), 0);
            case 10 -> hello(source, object(frame.get("d")));
            case 11 -> source.unacknowledged = 0;
            default -> { }
        }
    }

    private void hello(Attempt source, JSONObject data) {
        if (source.intervalMillis != 0) {
            return;
        }
        Long interval = number(data.get("heartbeat_interval"));
        if (interval == null || interval <= 0) {
            connectionFailed(source);
            return;
        }
        source.intervalMillis = interval;
        try {
            source.token = tokens.token();
        } catch (QqBotApiException e) {
            connectionFailed(source);
            return;
        }
        if (!isCurrent(source)) {
            return;
        }
        String authorization = "QQBot " + source.token.value();
        source.resuming = sessionId != null;
        if (source.resuming) {
            send(source, 6, JSONObject.of("token", authorization, "session_id", sessionId, "seq", sequence));
        } else {
            send(source, 2, JSONObject.of("token", authorization, "intents", GROUP_AND_C2C_EVENT,
                    "shard", JSONArray.of(0, 1), "properties", new JSONObject()));
        }
        watchHandshake(source);
        heartbeat(source);
    }

    private void heartbeat(Attempt source) {
        if (!isCurrent(source)) {
            return;
        }
        if (source.unacknowledged == 2) {
            log.debug("QQ 开放平台连续两个心跳间隔未应答");
            connectionFailed(source);
            return;
        }
        source.unacknowledged++;
        send(source, 1, sequence);
        source.heartbeat = schedule(() -> heartbeat(source), source.intervalMillis);
    }

    private void watchHandshake(Attempt source) {
        cancel(source.watchdog);
        source.watchdog = schedule(() -> connectionFailed(source), HANDSHAKE_TIMEOUT_MILLIS);
    }

    private void dispatch(Attempt source, JSONObject frame) {
        if (source.intervalMillis == 0 || !(frame.get("t") instanceof String event)) {
            return;
        }
        Object data = frame.get("d");
        Long receivedSequence = number(frame.get("s"));
        if (log.isDebugEnabled()) {
            // 消息事件可能携带认领码等用户输入，仅记录事件名。
            log.debug("收到 QQ 开放平台事件 {}",
                    event.endsWith("MESSAGE_CREATE") ? event : QqBotGatewayEventLog.mask(frame, source.token));
        }
        switch (event) {
            case "READY" -> {
                JSONObject ready = object(data);
                String session = text(ready.get("session_id"));
                if (session == null || session.isBlank()) {
                    connectionFailed(source);
                    return;
                }
                sessionId = session;
                sequence = receivedSequence;
                robotName = robotName(object(ready.get("user")), source.token);
                connected(source);
            }
            case "RESUMED" -> {
                if (source.resuming) {
                    rememberSequence(receivedSequence);
                    connected(source);
                }
            }
            default -> {
                if (source.readyAt != null || source.resuming) {
                    String eventId = text(frame.get("id"));
                    listeners.orderedStream().forEach(listener -> {
                        try {
                            listener.onEventForApp(source.token.appId(), event, data, eventId, receivedSequence);
                        } catch (Exception e) {
                            log.warn("QQ 开放平台事件监听处理失败");
                        }
                    });
                    rememberSequence(receivedSequence);
                    if (source.readyAt == null) {
                        watchHandshake(source);
                    }
                }
            }
        }
    }

    private void connected(Attempt source) {
        cancel(source.watchdog);
        source.watchdog = null;
        if (source.readyAt == null) {
            source.readyAt = scheduler.nowMillis();
        }
        status = new Status(settings, HealthStatus.ok("已连接 · " + robotName), null, robotName);
    }

    private void rememberSequence(Long receivedSequence) {
        if (receivedSequence != null) {
            sequence = receivedSequence;
        }
    }

    private void send(Attempt source, int op, Object data) {
        var connection = socket.get();
        if (connection == null || !isCurrent(source)) {
            return;
        }
        try {
            connection.send(JSONObject.of("op", op, "d", data).toJSONString(JSONWriter.Feature.WriteNulls))
                    .whenComplete((sent, error) -> {
                        if (error != null) {
                            source.onFailure();
                        }
                    });
        } catch (RuntimeException e) {
            source.onFailure();
        }
    }

    private void handleClose(Attempt source, int code) {
        if (!isCurrent(source)) {
            return;
        }
        log.debug("QQ 开放平台长连接关闭，关闭码 {}", code);
        switch (code) {
            case 4009 -> reconnect(source, true, 0);
            case 4008 -> reconnect(source, true, RATE_LIMIT_DELAY_MILLIS);
            case 4006, 4007 -> reconnect(source, false, 0);
            case 4914 -> stop("机器人已下架，只能连沙箱环境", "核对 QQ 开放平台上的机器人发布状态", "account");
            case 4915 -> stop("机器人被封禁", "到 QQ 开放平台核对机器人状态", "account");
            case 4001, 4002, 4010, 4011, 4012, 4013, 4014 ->
                    stop("连接参数被平台拒绝（关闭码 " + code + "），请把日志交给维护者",
                            "请把日志交给维护者", "");
            default -> {
                if (code >= 4900 && code <= 4913) {
                    reconnect(source, false, 0);
                } else {
                    connectionFailed(source);
                }
            }
        }
    }

    private void stop(String summary, String advice, String reason) {
        disconnect();
        forgetSession();
        status = new Status(settings, HealthStatus.down(summary, advice, reason), null, null);
    }

    private void connectionFailed(Attempt source) {
        reconnect(source, !source.resuming || source.readyAt != null, 0);
    }

    private void reconnect(Attempt source, boolean keepSession, long minimumDelay) {
        if (!isCurrent(source)) {
            return;
        }
        if (source.readyAt != null && scheduler.nowMillis() - source.readyAt >= STABLE_CONNECTION_MILLIS) {
            retryCount = 0;
        }
        if (!keepSession) {
            forgetSession();
        }
        disconnect();
        retry(minimumDelay);
    }

    private void retry(long minimumDelay) {
        retryCount++;
        long delay = Math.min(MAX_BACKOFF_MILLIS, 1_000L << Math.min(retryCount - 1, 6));
        Long disconnectedAt = status.disconnectedAt();
        status = new Status(settings, HealthStatus.degraded("连接断开，正在重连（第 " + retryCount + " 次）",
                "请检查本机网络，连接会自动重试"),
                disconnectedAt == null ? scheduler.nowMillis() : disconnectedAt.longValue(), robotName);
        scheduleConnect(Math.max(delay, minimumDelay));
    }

    private void scheduleConnect(long delay) {
        retry = schedule(() -> {
            retry = null;
            connect();
        }, delay);
    }

    private void forgetSession() {
        sessionId = null;
        sequence = null;
        robotName = null;
    }

    private boolean isCurrent(Attempt source) {
        return !closed && attempt == source && settings == tokens.settings().credentials();
    }

    private void disconnect() {
        if (attempt != null) {
            cancel(attempt.heartbeat);
            cancel(attempt.watchdog);
            attempt = null;
        }
        closeSocket();
    }

    private void closeSocket() {
        var connection = socket.getAndSet(null);
        if (connection != null) {
            connection.close();
        }
    }

    private Future<?> schedule(Runnable task, long delayMillis) {
        if (!closed) {
            try {
                return scheduler.schedule(task, delayMillis);
            } catch (RejectedExecutionException e) {
                if (!closed) {
                    throw e;
                }
            }
        }
        return null;
    }

    @PreDestroy
    void close() {
        closed = true;
        closeSocket();
        scheduler.shutdown();
    }

    private static void cancel(Future<?> task) {
        if (task != null) {
            task.cancel(false);
        }
    }

    private static JSONObject object(Object value) {
        return value instanceof JSONObject json ? json : new JSONObject();
    }

    private static String text(Object value) {
        return value instanceof String string ? string : null;
    }

    private static Long number(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static String robotName(JSONObject user, QqBotTokenService.Token token) {
        String name = text(user.get("username"));
        if (name == null || name.isBlank() || token.echoedIn(name)) {
            return QqBotPluginAutoConfiguration.DISPLAY_NAME;
        }
        String id = text(user.get("id"));
        if (id != null && (id.length() > 8 || name.equals(id))) {
            return name.replace(id, QqBotGatewayEventLog.maskId(id));
        }
        return name;
    }

    private record Endpoint(URI uri, long waitMillis) {
    }

    record ConnectionState(HealthStatus health, String robotName) {
    }

    private record Status(QqBotSettings.Credentials settings, HealthStatus health, Long disconnectedAt, String robotName) {
    }

    private final class Attempt implements QqBotWebSocketTransport.Listener {
        private QqBotTokenService.Token token;
        private long intervalMillis;
        private int unacknowledged;
        private boolean resuming;
        private Long readyAt;
        private Future<?> heartbeat;
        private Future<?> watchdog;

        @Override
        public void onText(String text) {
            schedule(() -> receive(this, text), 0);
        }

        @Override
        public void onClose(int code) {
            schedule(() -> handleClose(this, code), 0);
        }

        @Override
        public void onFailure() {
            schedule(() -> connectionFailed(this), 0);
        }
    }
}
