package com.starlwr.bot.core.config.ui.auth;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 登录尝试的限流与锁定
 * <p>
 * 管两件事，它们防的是两种不同的攻击：
 * <ol>
 *   <li><b>按来源 IP 锁定</b>防的是猜口令。连续失败若干次后该 IP 进入锁定期，
 *       且每再触发一次锁定时间翻倍，把爆破的速率压到无意义的程度</li>
 *   <li><b>全局并发闸门</b>防的是把机器人拖垮。校验一次口令要独占一个核心
 *       （开发机上实测约 570 毫秒，PBKDF2 的迭代次数就是为此而设的），
 *       <b>这意味着未登录的请求可以直接消耗 CPU</b>——几十个并发登录请求就能把弹幕处理线程饿死。
 *       面板被人敲不该让机器人下线，所以同时只允许少量校验在跑，多出来的直接拒绝而不是排队</li>
 * </ol>
 * <p>
 * 按 IP 锁定挡不住换着 IP 来的分布式爆破，那种情况靠的是口令哈希本身的强度与并发闸门。
 * 这里不做全局锁定：那等于让攻击者可以一键把主播关在门外。
 */
@Slf4j
public class LoginThrottle {
    /**
     * 同时允许进行的口令校验数
     * <p>
     * 取 2 是让正常使用（一个人点一次登录）不受影响，同时把攻击者能占用的 CPU 限制在两个核心内。
     */
    private static final int CONCURRENT_VERIFICATIONS = 2;

    /**
     * 锁定时长的上限，避免翻倍到「几十年」这种没有意义的数值
     */
    private static final Duration MAX_LOCKOUT = Duration.ofHours(6);

    /**
     * 连续失败的计数窗口
     * <p>
     * 隔了这么久才再次失败就不算「连续」，计数重新开始。
     * 不这样做的话，几个月里零星输错几次也会攒够次数把自己锁掉。
     */
    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);

    /**
     * 记录数上限，防止被大量来源 IP 撑爆内存
     */
    private static final int MAX_ENTRIES = 1024;

    private final int maxFailures;

    private final Duration baseLockout;

    private final Map<String, Attempts> byIp = new ConcurrentHashMap<>();

    private final Semaphore slots = new Semaphore(CONCURRENT_VERIFICATIONS);

    public LoginThrottle(int maxFailures, Duration baseLockout) {
        this.maxFailures = Math.max(1, maxFailures);
        this.baseLockout = baseLockout;
    }

    /**
     * 查询某个来源还要等多久才能再试
     * @param ip 来源 IP
     * @param now 当前时刻
     * @return 剩余锁定时长，未锁定时为 {@link Duration#ZERO}
     */
    public Duration remainingLockout(String ip, Instant now) {
        Attempts attempts = byIp.get(ip);
        if (attempts == null || attempts.lockedUntil == null || !now.isBefore(attempts.lockedUntil)) {
            return Duration.ZERO;
        }

        return Duration.between(now, attempts.lockedUntil);
    }

    /**
     * 记录一次失败，达到阈值时进入锁定
     * @param ip 来源 IP
     * @param now 当前时刻
     */
    public void recordFailure(String ip, Instant now) {
        evictIfFull();

        Attempts attempts = byIp.computeIfAbsent(ip, k -> new Attempts());
        synchronized (attempts) {
            if (attempts.lastFailureAt != null && now.isAfter(attempts.lastFailureAt.plus(FAILURE_WINDOW))) {
                attempts.failures = 0;
            }

            attempts.lastFailureAt = now;
            attempts.failures++;

            if (attempts.failures >= maxFailures) {
                attempts.failures = 0;
                attempts.lockouts++;

                // 每再锁一次翻倍：第一次几分钟，反复来就变成几小时
                Duration duration = baseLockout.multipliedBy(1L << Math.min(attempts.lockouts - 1, 10));
                if (duration.compareTo(MAX_LOCKOUT) > 0) {
                    duration = MAX_LOCKOUT;
                }

                attempts.lockedUntil = now.plus(duration);
                log.warn("配置界面登录失败次数过多, 已锁定来自 {} 的登录 {} 分钟", ip, duration.toMinutes());
            }
        }
    }

    /**
     * 记录一次成功，清空该来源的失败记录
     * @param ip 来源 IP
     */
    public void recordSuccess(String ip) {
        byIp.remove(ip);
    }

    /**
     * 申请一次校验名额
     * @return 是否获得名额，未获得时应直接拒绝请求而不是等待
     */
    public boolean tryAcquireSlot() {
        return slots.tryAcquire();
    }

    /**
     * 归还校验名额
     */
    public void releaseSlot() {
        slots.release();
    }

    /**
     * 记录数超限时清掉已解锁的条目
     * <p>
     * 只清理不再有约束力的记录，锁定中的一律保留——否则「刷满记录表」就成了解锁手段。
     */
    private void evictIfFull() {
        if (byIp.size() < MAX_ENTRIES) {
            return;
        }

        Instant now = Instant.now();
        byIp.values().removeIf(attempts -> attempts.lockedUntil == null || !now.isBefore(attempts.lockedUntil));

        if (byIp.size() >= MAX_ENTRIES) {
            log.warn("配置界面登录失败记录已达上限且全部处于锁定中, 疑似正在遭受来自大量地址的爆破");
        }
    }

    /**
     * 单个来源的失败记录
     */
    private static final class Attempts {
        /**
         * 本轮连续失败次数，触发锁定后归零
         */
        private int failures;

        /**
         * 累计触发锁定的次数，决定下一次锁多久
         */
        private int lockouts;

        private Instant lastFailureAt;

        private Instant lockedUntil;
    }
}
