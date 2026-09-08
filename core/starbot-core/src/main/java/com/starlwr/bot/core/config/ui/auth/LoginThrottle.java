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
 *   <li><b>全局速率限制</b>防的是<b>换着 IP 来</b>的分布式猜口令。按 IP 锁定对它无效：
 *       每个地址只试三两次就换下一个，一次锁定也触发不了。见下</li>
 * </ol>
 * <p>
 * 🔴 <b>全局速率限制不是全局锁定，这两件事必须分清。</b>
 * 全局锁定是<b>状态</b>：触发之后即使攻击停了，主播也照样被关在门外若干分钟——
 * 那等于给了攻击者一个「一键把主播锁出去」的按钮，所以这里从来不做，现在也不做。
 * 速率限制是<b>流量</b>：桶随时间自动回满，攻击一停，下一次尝试等几秒就能过，<b>不留惩罚</b>。
 * 这条区别有判据钉着（{@code LoginThrottleTest} 里那条「洪水停了立刻能进」）。
 * <p>
 * 攻击进行期间，主播确实要和攻击者抢同一个桶——但<b>这不是新增的形态</b>：
 * 并发闸门早就有同样的性质（名额被占满时正常登录同样会被拒），
 * 区别只在于闸门给的上限取决于这台机器的 CPU 有多快，而速率限制给的是一个<b>写死的、已知的</b>上限。
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
     * 全局每分钟允许发起的口令校验次数
     * <p>
     * 定这个数看的是两头：
     * <ul>
     *   <li><b>人这头</b>：一个人登录一次算一次，输错重来算第二次。一分钟 20 次，
     *       正常使用永远碰不到</li>
     *   <li><b>攻击那头</b>：没有这道限制时，上限由「并发闸门 ÷ 单次校验耗时」决定，
     *       开发机上实测约 570 毫秒一次，即 2 ÷ 0.57 ≈ 每分钟 210 次。
     *       <b>而这个数会随机器变快而变大</b>——换台好点的 VPS，防线就自己松了。
     *       写死 20 是把上限从「取决于 CPU」改成「取决于配置」</li>
     * </ul>
     */
    private static final int GLOBAL_ATTEMPTS_PER_MINUTE = 20;

    /**
     * 全局桶的容量，即能容忍的瞬时突发
     * <p>
     * 与每分钟额度取同一个数：一分钟内的额度可以一次用完，之后按速率慢慢回。
     * 取得比额度小的话，「同时有几个人在登录」这种正常情形也会被拒。
     */
    private static final int GLOBAL_BURST = GLOBAL_ATTEMPTS_PER_MINUTE;

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

    /**
     * 全局桶里剩余的令牌，取小数是为了让补充按真实时间比例来，而不是攒够一秒才补一个
     */
    private double globalTokens = GLOBAL_BURST;

    /**
     * 上次补充全局桶的时刻，尚未用过时为 null
     */
    private Instant globalRefilledAt;

    /**
     * 全局桶当前是否已空
     * <p>
     * 只用来决定「这一次要不要打日志」：被限速的请求可能一秒钟几百个，
     * 每个都打一行的话，真正有用的那行会被自己冲掉
     */
    private boolean globalExhausted;

    private final Object globalLock = new Object();

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
     * 申请一次全局校验额度
     * <p>
     * 🔴 <b>要在真正开始校验之前问</b>：这道限制拦的是「一共可以猜多少次」，
     * 而猜的动作与它的开销都发生在校验里。放到校验之后再问，拦下的只是回包。
     * <p>
     * 拿不到时应答复<b>「服务器忙，稍后重试」这一类瞬时状态</b>，不要答复「被锁定」——
     * 桶是随时间自动回满的，几秒钟后就能再试，说成锁定会让人去重置一个没问题的口令。
     * @param now 当前时刻
     * @return 是否获得额度
     */
    public boolean tryAcquireGlobal(Instant now) {
        synchronized (globalLock) {
            if (globalRefilledAt == null) {
                globalRefilledAt = now;
            }

            double elapsedSeconds = Duration.between(globalRefilledAt, now).toNanos() / 1_000_000_000.0;
            if (elapsedSeconds > 0) {
                globalTokens = Math.min(GLOBAL_BURST,
                        globalTokens + elapsedSeconds * GLOBAL_ATTEMPTS_PER_MINUTE / 60.0);
                globalRefilledAt = now;
            }

            if (globalTokens < 1.0) {
                if (!globalExhausted) {
                    globalExhausted = true;
                    log.warn("配置界面的口令校验已达全局速率上限（每分钟 {} 次）, 疑似正在遭受来自大量地址的爆破",
                            GLOBAL_ATTEMPTS_PER_MINUTE);
                }
                return false;
            }

            globalTokens -= 1.0;
            globalExhausted = false;
            return true;
        }
    }

    /**
     * 退还一次全局额度
     * <p>
     * 校验通过时调用。<b>正常使用不该消耗这份预算</b>——
     * 否则一个人反复进出面板也会把额度耗掉，而这道限制本来是冲着猜口令去的。
     */
    public void refundGlobal() {
        synchronized (globalLock) {
            globalTokens = Math.min(GLOBAL_BURST, globalTokens + 1.0);
        }
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
