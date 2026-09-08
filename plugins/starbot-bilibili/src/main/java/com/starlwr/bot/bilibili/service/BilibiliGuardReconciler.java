package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.core.event.live.StarBotBaseLiveEvent;
import com.starlwr.bot.core.plugin.StarBotComponent;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 大航海消息归并
 * <p>
 * 同一次开通最多会由三条消息播报：{@code GUARD_BUY}、{@code USER_TOAST_MSG}、
 * {@code USER_TOAST_MSG_V2}。三条都要收——2026-08-06 抓的 60 笔上舰里，
 * 只有 {@code GUARD_BUY} 的 11 笔、只有 toast 的 24 笔、只以 V2 下发的 7 笔，
 * <b>任何一条单独拿出来都覆盖不全</b>。
 * <p>
 * <b>但多条都收就必须归并。</b>大航海是本项目金额最大的一类事件，算两遍会让营收凭空翻倍，
 * 而且这个错误在数据上完全说得通——没有任何地方会报错。
 *
 * <h2>为什么要压一会儿再发</h2>
 * {@code GUARD_BUY} 的 {@code price} 是<b>挂牌价</b>，35 个样本里 32 个舰长全是 198000，
 * 一次都没变过；toast 的 {@code price} 才是<b>实际成交价</b>（实测 138000/168000/198000 都有）。
 * 25 笔配对样本按挂牌价记会高估 15.4%。
 * <p>
 * 偏偏 {@code GUARD_BUY} <b>恒定先到</b>（25/25，且只差一条消息），所以「先到先得」必然选中
 * 挂牌价。要拿到实际成交价，只能把 {@code GUARD_BUY} 压住等一等 toast——实测两者相差不超过
 * 1 秒，{@link #DEFAULT_GRACE} 留了足够余量。
 * <p>
 * 压不到 toast 时才把 {@code GUARD_BUY} 发出去，此时金额只有挂牌价可用，会偏高。
 * 这一段没有在本轮修完：真实金额在 138/168/198 之间无从判断，记挂牌价偏高、记空又丢了量级，
 * 正确解法是把「纸面价值」与「实收」拆成两个字段分别存。见任务 #45。
 */
@Slf4j
@StarBotComponent
public class BilibiliGuardReconciler {
    /**
     * {@code GUARD_BUY} 等待 toast 的时长
     * <p>
     * 实测两者到达相差 0~1 秒（25 个样本）。取 5 秒是留足余量：
     * 这点延迟对推送毫无影响，而等不到就会用挂牌价，宁可多等。
     */
    static final Duration DEFAULT_GRACE = Duration.ofSeconds(5);

    /**
     * 判重时间窗
     * <p>
     * 三条消息的时间字段来源不同（{@code send_time} 毫秒、{@code start_time} 秒），
     * 拿时间戳做键会因为差几秒判成两次，因此改为在一个窗口内认作同一次。
     */
    private static final Duration WINDOW = Duration.ofSeconds(30);

    /**
     * 各记录表的条目上限，防止长时间运行后无限增长
     */
    private static final int MAX_ENTRIES = 512;

    /**
     * 已发出的 toast，用于压制随后到达的 {@code GUARD_BUY}
     * <p>
     * 键只用「谁 + 什么等级」，<b>不含开通数量</b>。若把数量算进键，
     * 一旦两条消息的 {@code num} 对不上就会各发一次，也就是把一次记成两次；
     * 而不含数量的代价，是同一人在 30 秒内连买两笔不同月数时少记一笔。
     * 后者近乎不存在，且本就该按项目一贯的取舍来——<b>少记一次远好过记成两次</b>。
     */
    private final Map<String, Instant> recentToasts = new ConcurrentHashMap<>();

    /**
     * 正在等 toast 的 {@code GUARD_BUY}
     */
    private final Map<String, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();

    /**
     * 已处理过的支付流水号，用于 v1 与 V2 之间去重
     * <p>
     * {@code payflow_id} 是这两条消息共有的字段，实测 72 条 toast <b>无一缺失</b>，
     * 且同一流水号下 v1 与 V2 的金额完全一致。比按「人 + 等级 + 时间窗」猜要可靠得多。
     * <p>
     * 值只用于清理时判断新旧，<b>判重时不看它</b>——理由见 {@link #acceptToast}。
     */
    private final Map<String, Instant> seenFlows = new ConcurrentHashMap<>();

    private final ApplicationEventPublisher publisher;

    private final ScheduledExecutorService scheduler;

    private final Duration grace;

    @Autowired
    public BilibiliGuardReconciler(ApplicationEventPublisher publisher) {
        this(publisher, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "guard-reconciler");
            thread.setDaemon(true);
            return thread;
        }), DEFAULT_GRACE);
    }

    BilibiliGuardReconciler(ApplicationEventPublisher publisher, ScheduledExecutorService scheduler, Duration grace) {
        this.publisher = publisher;
        this.scheduler = scheduler;
        this.grace = grace;
    }

    /**
     * 收到一条 {@code GUARD_BUY}
     * <p>
     * 不立即发出：先压住等 toast，等到了就作废（toast 的金额才是实际成交价），
     * 等不到再发。调用方因此拿不到可返回的事件，一律返回空。
     * @param uid 开通者 UID
     * @param guardLevel 大航海等级
     * @param at 消息时刻
     * @param event 已解析好的事件
     */
    public void holdGuardBuy(Long uid, Integer guardLevel, Instant at, StarBotBaseLiveEvent event) {
        if (event == null) {
            return;
        }

        String key = keyOf(uid, guardLevel);
        if (key == null) {
            // 认不出是谁买的就没法归并，直接发出——漏记比整条丢掉好
            publish(event);
            return;
        }

        Instant toastAt = recentToasts.get(key);
        if (toastAt != null && withinWindow(toastAt, at)) {
            log.debug("大航海开通已由 toast 播报, 忽略 GUARD_BUY: uid={} level={}", uid, guardLevel);
            return;
        }

        sweep(at);
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            pending.remove(key);
            log.debug("大航海开通等不到 toast, 按挂牌价发出: uid={} level={}", uid, guardLevel);
            publish(event);
        }, grace.toMillis(), TimeUnit.MILLISECONDS);

        ScheduledFuture<?> previous = pending.put(key, future);
        if (previous != null) {
            // 同一人同等级的上一条还压着，说明重复播报，留新的即可
            previous.cancel(false);
        }
    }

    /**
     * 收到一条 toast（{@code USER_TOAST_MSG} 或 {@code USER_TOAST_MSG_V2}）
     * <p>
     * 同时会撤掉正在等待的 {@code GUARD_BUY}：两者播报的是同一次开通，
     * 而 toast 带的是实际成交价。
     * @param payflowId 支付流水号，v1 与 V2 共有
     * @param uid 开通者 UID
     * @param guardLevel 大航海等级
     * @param at 消息时刻
     * @return 本次开通的第一条为 true；v1/V2 的重复播报为 false
     */
    public boolean acceptToast(String payflowId, Long uid, Integer guardLevel, Instant at) {
        if (payflowId != null && !payflowId.isBlank()) {
            // 流水号本身就是一笔交易的唯一标识，见过就是重复，<b>不再看时间</b>。
            // 加时间窗反而会漏：两条消息的时刻来源不同（v1 取 send_time 毫秒，
            // V2 缺 send_time 时回退到 guard_info.start_time 秒），
            // 只要两者对不上就会双双放行，把同一笔记成两笔
            if (seenFlows.putIfAbsent(payflowId, at) != null) {
                log.debug("大航海 toast 重复播报, 已忽略: payflow={}", payflowId);
                return false;
            }
            sweep(at);
        }

        String key = keyOf(uid, guardLevel);
        if (key == null) {
            return true;
        }

        // 流水号缺失时退回按「人 + 等级 + 时间窗」判重，否则 v1/V2 会各发一次
        if ((payflowId == null || payflowId.isBlank())) {
            Instant previous = recentToasts.get(key);
            if (previous != null && withinWindow(previous, at)) {
                log.debug("大航海 toast 缺流水号且落在同一窗口内, 按重复处理: uid={} level={}", uid, guardLevel);
                return false;
            }
        }

        recentToasts.put(key, at);

        ScheduledFuture<?> waiting = pending.remove(key);
        if (waiting != null) {
            waiting.cancel(false);
            log.debug("大航海开通已收到 toast, 撤销挂牌价播报: uid={} level={}", uid, guardLevel);
        }
        return true;
    }

    private void publish(StarBotBaseLiveEvent event) {
        try {
            publisher.publishEvent(event);
        } catch (Exception e) {
            log.error("发布大航海事件异常", e);
        }
    }

    private String keyOf(Long uid, Integer guardLevel) {
        return uid == null || guardLevel == null ? null : uid + ":" + guardLevel;
    }

    private boolean withinWindow(Instant previous, Instant now) {
        return Duration.between(previous, now).abs().compareTo(WINDOW) <= 0;
    }

    /**
     * 清掉已经超出时间窗的记录
     */
    private void sweep(Instant now) {
        if (recentToasts.size() >= MAX_ENTRIES) {
            recentToasts.entrySet().removeIf(e -> !withinWindow(e.getValue(), now));
        }
        if (seenFlows.size() >= MAX_ENTRIES) {
            seenFlows.entrySet().removeIf(e -> !withinWindow(e.getValue(), now));
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
