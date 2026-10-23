package org.frostnova.nova.core.protocol;

import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.properties.EventStreamProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** 事件输出连接的额度与握手登记。 */
@Slf4j
final class EventConnectionLimits implements HandshakeInterceptor {
    private static final String ATTRIBUTE = EventConnectionLimits.class.getName();
    private final ScheduledExecutorService timer;
    final int maxConnections;
    final int maxPending;
    final int maxPerSource;
    final int maxHandshakes;
    final int handshakeTimeout;
    final int ioTimeout;
    private final Map<String, Integer> sources = new HashMap<>();
    private final Map<ServerHttpRequest, Reservation> handshakes = new IdentityHashMap<>();
    private final Map<String, Long> notices = new HashMap<>();
    /** 提示限频用的时钟，纳秒 */
    LongSupplier clock = System::nanoTime;
    private int connections;
    private int pending;
    private boolean stopped;

    EventConnectionLimits(EventStreamProperties properties, ScheduledExecutorService timer) {
        this.timer = timer;
        maxConnections = positive("max-connections", properties.getMaxConnections(), 256);
        maxPending = positive("max-pending-connections", properties.getMaxPendingConnections(), 128);
        maxPerSource = positive("max-connections-per-source", properties.getMaxConnectionsPerSource(), 128);
        maxHandshakes = positive("max-concurrent-handshakes", properties.getMaxConcurrentHandshakes(), 64);
        handshakeTimeout = positive("handshake-timeout-ms", properties.getHandshakeTimeoutMs(), 10000);
        ioTimeout = positive("io-timeout-ms", properties.getIoTimeoutMs(), 10000);
    }

    private static int positive(String key, int value, int fallback) {
        if (value > 0) return value;
        log.warn("事件输出 {}.{} 请填写正整数，本次使用默认值 {}；可修改后重启",
                EventStreamProperties.PREFIX, key, fallback);
        return fallback;
    }

    /** 同一种提示 5 秒内只记一条：这一条该略过时返回 {@code true}。 */
    private boolean quiet(String key) {
        long now = clock.getAsLong();
        Long previous = notices.get(key);
        if (previous != null && now - previous < TimeUnit.SECONDS.toNanos(5)) return true;
        notices.put(key, now);
        return false;
    }

    private void full(String key, int value) {
        if (quiet(key)) return;
        log.warn("事件输出当前额度已用完（{}={}），请稍后重连；需要更多连接时可调大 {}.{} 后重启",
                key, value, EventStreamProperties.PREFIX, key);
    }

    private Reservation reserve(InetSocketAddress remote) {
        if (stopped) return null;
        String source = remote == null ? "local" : remote.getAddress() == null
                ? remote.getHostString() : remote.getAddress().getHostAddress();
        if (connections >= maxConnections) { full("max-connections", maxConnections); return null; }
        if (pending >= maxPending) { full("max-pending-connections", maxPending); return null; }
        if (sources.getOrDefault(source, 0) >= maxPerSource) {
            full("max-connections-per-source", maxPerSource);
            return null;
        }
        connections++;
        pending++;
        sources.merge(source, 1, Integer::sum);
        return new Reservation(source);
    }

    @Override
    public synchronized boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                                WebSocketHandler handler, Map<String, Object> attributes) {
        Reservation reservation = null;
        if (handshakes.size() >= maxHandshakes) {
            full("max-concurrent-handshakes", maxHandshakes);
        } else {
            reservation = reserve(request.getRemoteAddress());
        }
        if (reservation == null) {
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            response.getHeaders().set("Retry-After", "5");
            return false;
        }
        reservation.handshaking = true;
        handshakes.put(request, reservation);
        attributes.put(ATTRIBUTE, reservation);
        Reservation registered = reservation;
        reservation.deadline = timer.schedule(() -> expire(registered), handshakeTimeout, TimeUnit.MILLISECONDS);
        return true;
    }

    private synchronized void expire(Reservation reservation) {
        if (reservation.claimed || reservation.released) return;
        reservation.expired = true;
        if (!quiet("handshake-timeout-ms")) {
            log.warn("事件输出握手等待已到 {} 毫秒，请稍后重连；可调整 {}.handshake-timeout-ms 后重启",
                    handshakeTimeout, EventStreamProperties.PREFIX);
        }
        if (!reservation.handshaking) reservation.release();
    }

    @Override
    public synchronized void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                            WebSocketHandler handler, Exception exception) {
        Reservation reservation = handshakes.remove(request);
        if (reservation == null) return;
        reservation.handshaking = false;
        boolean completed = response instanceof ServletServerHttpResponse servlet
                && servlet.getServletResponse().getStatus() == 101;
        if (exception != null || !completed || reservation.expired) reservation.release();
    }

    synchronized Reservation claim(Map<String, Object> attributes, InetSocketAddress source) {
        Object attribute = attributes.get(ATTRIBUTE);
        Reservation reservation;
        if (attribute instanceof EventConnectionLimits.Reservation existing) {
            if (existing.owner() != this || existing.claimed || existing.expired || existing.released) return null;
            reservation = existing;
        } else {
            reservation = reserve(source);
        }
        if (reservation != null) {
            reservation.claimed = true;
            if (reservation.deadline != null) reservation.deadline.cancel(false);
        }
        return reservation;
    }

    synchronized void stop() {
        stopped = true;
        for (Reservation reservation : handshakes.values()) reservation.release();
        handshakes.clear();
    }

    final class Reservation {
        private final String source;
        private boolean awaitingAuth = true;
        private boolean claimed;
        private boolean handshaking;
        private boolean expired;
        private boolean released;
        private ScheduledFuture<?> deadline;

        private Reservation(String source) { this.source = source; }
        private EventConnectionLimits owner() { return EventConnectionLimits.this; }

        void authenticated() {
            synchronized (EventConnectionLimits.this) {
                if (!awaitingAuth || released) return;
                awaitingAuth = false;
                pending--;
            }
        }

        void release() {
            synchronized (EventConnectionLimits.this) {
                if (released) return;
                released = true;
                if (deadline != null) deadline.cancel(false);
                connections--;
                if (awaitingAuth) pending--;
                sources.computeIfPresent(source, (key, count) -> count == 1 ? null : count - 1);
            }
        }
    }
}
