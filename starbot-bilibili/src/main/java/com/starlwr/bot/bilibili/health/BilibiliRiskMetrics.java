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
import java.util.concurrent.atomic.AtomicLong;

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
     * <p>
     * 顶到这个数之后最老的记录被挤出去，{@link #count} 随之封顶——所以挤掉了几条必须
     * 单独记在 {@link #overflow} 里。不记的话「恰好 2000 次」与「20 万次」读出来一样，
     * 而这两种情况要做的事完全不同。
     */
    private static final int MAX_PER_KIND = 2000;

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
        SNAPSHOT_MISSING("开播快照项缺失"),

        /**
         * 长连接业务消息出现分派表里没有的 cmd。
         * <p>
         * 结构性信号：未知令牌出现，不是数量判据。
         */
        UNKNOWN_CMD("未知消息类型"),

        /**
         * 长连接数据包出现 {@code DataPackType} 枚举外的操作码。
         * <p>
         * 结构性信号：未知令牌出现，不是数量判据。
         */
        UNKNOWN_OP("未知操作码"),

        /**
         * 长连接数据包头部出现已知编码方式之外的协议版本。
         * <p>
         * 结构性信号：未知令牌出现，不是数量判据。出现即说明 B 站改了压缩或封装方式，
         * 此刻新版本被当裸负载塞进包里，内容多半解不开——数据在静默流失。
         */
        UNKNOWN_VER("未知协议版本"),

        /**
         * 消息解析不出来：分派表内的 cmd 解析抛异常，或负载根本不是合法 JSON。
         * <p>
         * 逐条计数，按 cmd 名去重的只是 detail 里那份文本样本，detail 不含报文正文。
         */
        PARSE_FAILURE("消息解析失败"),

        /**
         * 报文里本应取到的关键字段取不到（弹幕 info 过短、pb 截断、礼物块缺失）。
         * <p>
         * 这类静默降级的表现是「数据悄悄变少」：弹幕少一条、礼物少入账一笔，
         * 计数上完全说得通，按「数值为 0」去发现永远找不到。
         */
        FIELD_MISSING("关键字段缺失"),

        /**
         * HTTP 接口应答 code=0 却没有 data 字段。
         * <p>
         * 字段整片消失的主要静默通道：调用方拿到空对象继续走，
         * 下游只表现为报告里少了一张卡。
         */
        API_DATA_MISSING("接口应答缺 data"),

        /**
         * 长连接数据包在协议层就没读下来：解压失败、解压产出超预算、长度字段异常。
         * <p>
         * 这三种失败都会让整批数据包被丢掉，而它们此前只有一条 warn 日志——
         * 一批里可能有几十条弹幕与礼物，丢掉之后计数上完全说得通。
         * 具体是哪一种写在 detail 的第一段（{@code decompress-failed}／
         * {@code budget-blown}／{@code bad-length}）。
         */
        PACKET_CORRUPT("数据包异常");

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

    /**
     * 因超出 {@link #MAX_PER_KIND} 而被挤掉的条数，按类型
     */
    private final Map<Kind, AtomicLong> overflows = new EnumMap<>(Kind.class);

    public BilibiliRiskMetrics() {
        for (Kind kind : Kind.values()) {
            events.put(kind, new ArrayDeque<>());
            overflows.put(kind, new AtomicLong());
        }
    }

    /**
     * 记录一次事件
     * <p>
     * <b>每调用一次就计一次，没有任何去重</b>：调用方那边按名字、按量级做的去重
     * 只决定「要不要换一份文本样本」，不能决定计数——两者混在一起的时候，
     * {@link #count} 读出来的是<b>写入次数</b>而不是发生次数，
     * 6 类事件曾因此把 25 次读成 2 次，而所有阈值都建在这个读数上。
     * @param kind 事件类型
     * @param detail 细节描述，用于在健康页上说明最近一次发生了什么；
     *               传 null 表示这一次只计数、沿用上一份样本
     */
    public void record(Kind kind, String detail) {
        Deque<Instant> deque = events.get(kind);
        synchronized (deque) {
            deque.addLast(Instant.now());
            while (deque.size() > MAX_PER_KIND) {
                deque.pollFirst();
                overflows.get(kind).incrementAndGet();
            }
        }
        if (detail != null && !detail.isBlank()) {
            lastDetails.put(kind, detail);
        }
        log.debug("记录风控指标 {}: {}", kind.getLabel(), detail);
    }

    /**
     * 统计滚动窗口内的发生次数
     * <p>
     * 逐次真值，读数封顶在 {@link #MAX_PER_KIND}；顶到之后被挤掉的条数见 {@link #overflow}。
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
     * 因超出每类保留上限而被挤掉的条数
     * <p>
     * {@link #count} 封顶在 {@link #MAX_PER_KIND}，这个数是它读不到的那一截。
     * 非零就说明该类事件的真实次数至少是「读数 ＋ 这个数」。
     * @param kind 事件类型
     * @return 被挤掉的条数
     */
    public long overflow(Kind kind) {
        return overflows.get(kind).get();
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
