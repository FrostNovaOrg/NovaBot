package org.frostnova.nova.core.protocol;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 首帧里访问令牌认证失败的计数
 *
 * <h2>为什么要有</h2>
 * 统一接口对出示无效令牌的来源限速；事件流的首帧若不计数，就成了一个可以不停换着令牌试的口子：
 * 每试一次只要开一条连接、发一帧，失败了关掉再开。只读口令那一路从前就不计数，<b>这里不去动它</b>，
 * 只数归访问令牌管的那些（{@link EventStreamAccessTokens#claims}）。
 *
 * <h2>怎么算</h2>
 * 一个窗口从第一次失败算起，长 {@link #WINDOW_SECONDS} 秒；窗口里失败满 {@link #MAX_FAILURES} 次之后，
 * 这个窗口余下的时间里再出示访问令牌的连接<b>不核对</b>，直接以「暂忙，请稍后重连」关闭。
 * 不核对是要点：核了的话，猜中的那一把照样能连上，计数就挡不住什么。
 * 窗口过了自动恢复。认证成功不清零：拿一把真令牌夹在中间连，不能把计数刷掉。
 *
 * <h2>不分来源</h2>
 * 事件流只接受本机回环的连接，能连进来的都是这台机器上的程序；本机回环又不止一个地址
 * （整个 {@code 127.0.0.0/8} 都是），按地址分格等于给换着地址试的人一格一格的新额度。所以只记一份。
 * 代价是一个写错令牌、不停重连的程序会让同一台机器上用访问令牌的面板晚一会儿才连上——
 * 它们收到的是「暂忙」而不是「令牌不对」，会照常退避重连，不会被误导去换令牌。只读口令不受影响。
 */
final class AccessTokenFailures {
    /** 一个窗口内容忍的失败次数，与统一接口的无效令牌限速同一个数 */
    static final int MAX_FAILURES = 10;

    /** 窗口长度，单位：秒 */
    static final long WINDOW_SECONDS = 60;

    private final int maxFailures;

    private final long windowNanos;

    private final LongSupplier clock;

    /** 当前窗口的起点（纳秒）；还没有失败时无意义 */
    private long windowStart;

    /** 当前窗口里的失败次数 */
    private int failures;

    AccessTokenFailures() {
        this(MAX_FAILURES, TimeUnit.SECONDS.toNanos(WINDOW_SECONDS), System::nanoTime);
    }

    /**
     * @param maxFailures 一个窗口内容忍的失败次数
     * @param windowNanos 窗口长度，纳秒
     * @param clock       时钟，纳秒
     */
    AccessTokenFailures(int maxFailures, long windowNanos, LongSupplier clock) {
        this.maxFailures = maxFailures;
        this.windowNanos = windowNanos;
        this.clock = clock;
    }

    /** 一个窗口内容忍的失败次数 */
    int maxFailures() {
        return maxFailures;
    }

    /**
     * 此刻是不是暂停受理访问令牌
     * @return 暂停时为 true，这时不该核对出示的令牌
     */
    synchronized boolean locked() {
        roll();
        return failures >= maxFailures;
    }

    /**
     * 记一次失败
     * @return 这一次正好让计数满了（之后开始暂停受理）时为 true，用来只打一次日志
     */
    synchronized boolean failed() {
        roll();
        if (failures == 0) {
            windowStart = clock.getAsLong();
        }
        failures++;
        return failures == maxFailures;
    }

    /**
     * 暂停受理还要多久
     * @return 秒，至少 1
     */
    synchronized long retryAfterSeconds() {
        long left = windowStart + windowNanos - clock.getAsLong();
        return Math.max(1, TimeUnit.NANOSECONDS.toSeconds(left + TimeUnit.SECONDS.toNanos(1) - 1));
    }

    /** 窗口过了就重新数 */
    private void roll() {
        if (failures > 0 && clock.getAsLong() - windowStart >= windowNanos) {
            failures = 0;
        }
    }
}
