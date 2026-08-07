package com.starlwr.bot.bilibili.health;

import com.starlwr.bot.core.plugin.StarBotComponent;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * 风控与静默降级指标
 * <p>
 * 记录各类风控信号与静默降级的发生时刻，供健康探针按滚动窗口判定。
 * <p>
 * 为什么要有这个类：这些事件的共同点是<b>不报错、不进日志、数据上完全说得通</b>——
 * 412 会被重试逻辑吞掉、快照接口失败只会让报告里少一张卡、1006 只是一条 INFO。
 * 靠人翻日志才发现问题，等于没有发现机制。
 * <p>
 * <b>412 必须按响应状态码记，不要按日志文本 grep</b>：日志时间戳里的 {@code .412}
 * 会造成大量误匹配，这个坑上位调研踩过。
 */
@Slf4j
@StarBotComponent
public class BilibiliRiskMetrics {
    /**
     * 每类事件保留的最大条数，防止长期运行后无限增长
     */
    private static final int MAX_PER_KIND = 512;

    /**
     * 超过此时长的记录直接丢弃。取最长窗口（7 天）再留一点余量
     */
    private static final Duration RETENTION = Duration.ofDays(8);

    /**
     * 事件类型
     */
    public enum Kind {
        /**
         * 真实的 HTTP 412，风控质询的典型状态码
         */
        HTTP_412("HTTP 412"),

        /**
         * 业务码 -352，请求被风控拦截
         */
        CODE_352("业务码 -352"),

        /**
         * 业务码 -401，需要验证
         */
        CODE_401("业务码 -401"),

        /**
         * 业务码 -509，请求过于频繁
         */
        CODE_509("业务码 -509"),

        /**
         * gaia 风控质询或验证码拦截
         */
        GAIA("风控质询/验证码"),

        /**
         * 长连接 1006 断线
         */
        DISCONNECT_1006("长连接 1006 断线"),

        /**
         * 开播快照项缺失：应记 N 项、实记 M 项，M 小于 N
         * <p>
         * 这类静默降级已实际发生过：大航海端点若被下线，
         * {@code getGuardCount} 返回空、调用方 {@code .ifPresent} 直接跳过，
         * 报告里那张卡整个消失，唯一痕迹是一条 debug 日志。
         * <b>按「数值为 0」去告警永远不会触发，必须按「项缺失」判定。</b>
         */
        SNAPSHOT_MISSING("开播快照项缺失");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    private final Map<Kind, Deque<Instant>> events = new EnumMap<>(Kind.class);

    private final Map<Kind, String> lastDetails = new EnumMap<>(Kind.class);

    public BilibiliRiskMetrics() {
        for (Kind kind : Kind.values()) {
            events.put(kind, new ArrayDeque<>());
        }
    }

    /**
     * 记录一次事件
     * @param kind 事件类型
     * @param detail 细节描述，用于在健康页上说明最近一次发生了什么
     */
    public void record(Kind kind, String detail) {
        Deque<Instant> deque = events.get(kind);
        synchronized (deque) {
            deque.addLast(Instant.now());
            while (deque.size() > MAX_PER_KIND) {
                deque.pollFirst();
            }
        }
        if (detail != null && !detail.isBlank()) {
            lastDetails.put(kind, detail);
        }
        log.debug("记录风控指标 {}: {}", kind.getLabel(), detail);
    }

    /**
     * 统计滚动窗口内的发生次数
     * @param kind 事件类型
     * @param window 窗口长度
     * @return 次数
     */
    public long count(Kind kind, Duration window) {
        Instant earliest = Instant.now().minus(window);
        Deque<Instant> deque = events.get(kind);
        synchronized (deque) {
            prune(deque);
            return deque.stream().filter(t -> t.isAfter(earliest)).count();
        }
    }

    /**
     * 最近一次发生的时刻
     * @param kind 事件类型
     * @return 发生时刻，从未发生时为空
     */
    public Optional<Instant> last(Kind kind) {
        Deque<Instant> deque = events.get(kind);
        synchronized (deque) {
            prune(deque);
            return Optional.ofNullable(deque.peekLast());
        }
    }

    /**
     * 最近一次的细节描述
     * @param kind 事件类型
     * @return 细节描述
     */
    public Optional<String> lastDetail(Kind kind) {
        return Optional.ofNullable(lastDetails.get(kind));
    }

    /**
     * 丢弃超出保留期的记录
     */
    private void prune(Deque<Instant> deque) {
        Instant limit = Instant.now().minus(RETENTION);
        while (!deque.isEmpty() && deque.peekFirst().isBefore(limit)) {
            deque.pollFirst();
        }
    }
}
