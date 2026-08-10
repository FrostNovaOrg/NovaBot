package com.starlwr.bot.bilibili.service;

import lombok.Getter;

import java.time.Duration;
import java.time.Instant;

/**
 * 分阶段退避
 * <p>
 * 给一件「按周期反复尝试」的事用：成功就回到基准周期，连续失败则逐级拉长间隔，到上限为止。
 * <p>
 * <b>为什么要有上限。</b> 无上限的指数退避在长时间故障后会把间隔拉到几天，
 * 那时故障早已恢复而我们还在等——退避是为了别在故障期间空转，不是为了放弃。
 * <p>
 * <b>为什么不做成「失败就停」。</b> 凭据维护这类事的失败绝大多数是网络抖动，
 * 停掉等于把一次抖动升级成永久失效，而且不会有任何报错。
 * <p>
 * <b>本类不持有时钟。</b> 时刻一律由调用方传入，因此可以用固定时刻把每一级退避
 * 确定性地测出来，而不是靠 sleep 去撞。这也是它不放在 {@code BilibiliAccountService}
 * 里的原因：那里面每一条路径都要碰凭据，而退避的级数与凭据无关。
 */
public class BilibiliStagedBackoff {
    /**
     * 基准间隔，即一切正常时的尝试周期
     */
    private final Duration base;

    /**
     * 退避上限
     */
    private final Duration cap;

    /**
     * 连续失败次数。成功即归零
     */
    @Getter
    private int consecutiveFailures;

    /**
     * 下一次允许尝试的时刻。为空表示「现在就可以」
     */
    private Instant nextAttemptAt;

    /**
     * @param base 基准间隔，非正值按 1 秒处理
     * @param cap 退避上限，小于基准时按基准处理（等于关闭退避）
     */
    public BilibiliStagedBackoff(Duration base, Duration cap) {
        this.base = base == null || base.isNegative() || base.isZero() ? Duration.ofSeconds(1) : base;
        this.cap = cap == null || cap.compareTo(this.base) < 0 ? this.base : cap;
    }

    /**
     * 现在是否该尝试
     * @param now 当前时刻
     * @return 到点了则为真
     */
    public boolean due(Instant now) {
        return nextAttemptAt == null || !now.isBefore(nextAttemptAt);
    }

    /**
     * 记一次成功：回到基准周期
     * @param now 当前时刻
     */
    public void succeeded(Instant now) {
        consecutiveFailures = 0;
        nextAttemptAt = now.plus(base);
    }

    /**
     * 记一次失败：间隔升一级
     * @param now 当前时刻
     * @return 本次采用的间隔，便于日志如实写出「下次多久后再试」
     */
    public Duration failed(Instant now) {
        consecutiveFailures++;
        Duration delay = currentDelay();
        nextAttemptAt = now.plus(delay);
        return delay;
    }

    /**
     * 当前这一级的间隔
     * <p>
     * 第 n 次失败后为 {@code base × 2^(n-1)}，到上限即停在上限。
     * <b>移位次数封在 30 以内</b>：{@code 1L << 31} 起就会溢出，
     * 而一个跑了很久的实例完全可能攒到那么多次失败——那时算出来的会是负间隔，
     * 于是 {@code due} 永远为真，退避变成不退避。
     */
    private Duration currentDelay() {
        if (consecutiveFailures <= 0) {
            return base;
        }
        int shift = Math.min(consecutiveFailures - 1, 30);
        Duration scaled = base.multipliedBy(1L << shift);
        return scaled.compareTo(cap) > 0 ? cap : scaled;
    }

    /**
     * 距离下一次允许尝试还有多久，已到点则为零
     * @param now 当前时刻
     * @return 剩余时长
     */
    public Duration remaining(Instant now) {
        if (due(now)) {
            return Duration.ZERO;
        }
        return Duration.between(now, nextAttemptAt);
    }
}
