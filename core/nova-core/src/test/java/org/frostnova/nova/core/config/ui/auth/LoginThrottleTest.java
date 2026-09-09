package org.frostnova.nova.core.config.ui.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录限流测试
 * <p>
 * 这里防的是两件事：把口令猜出来，以及借登录接口把机器人拖垮。
 */
@DisplayName("登录限流")
class LoginThrottleTest {
    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");

    private static final int MAX_FAILURES = 3;

    private static final Duration LOCKOUT = Duration.ofMinutes(10);

    private LoginThrottle throttle;

    @BeforeEach
    void setUp() {
        throttle = new LoginThrottle(MAX_FAILURES, LOCKOUT);
    }

    private void fail(int times, Instant at) {
        for (int i = 0; i < times; i++) {
            throttle.recordFailure("1.2.3.4", at);
        }
    }

    @Test
    @DisplayName("失败次数未达阈值时不锁定")
    void allowsAttemptsBelowThreshold() {
        fail(MAX_FAILURES - 1, NOW);

        assertTrue(throttle.remainingLockout("1.2.3.4", NOW).isZero());
    }

    @Test
    @DisplayName("连续失败达到阈值后锁定，到期自动解锁")
    void locksOutAfterThreshold() {
        fail(MAX_FAILURES, NOW);

        assertEquals(LOCKOUT, throttle.remainingLockout("1.2.3.4", NOW));
        assertFalse(throttle.remainingLockout("1.2.3.4", NOW.plus(LOCKOUT).minusSeconds(1)).isZero());
        assertTrue(throttle.remainingLockout("1.2.3.4", NOW.plus(LOCKOUT)).isZero());
    }

    @Test
    @DisplayName("反复触发锁定时时长翻倍")
    void lockoutDoubles() {
        fail(MAX_FAILURES, NOW);

        Instant after = NOW.plus(LOCKOUT);
        fail(MAX_FAILURES, after);

        assertEquals(LOCKOUT.multipliedBy(2), throttle.remainingLockout("1.2.3.4", after),
                "锁定时长不翻倍的话，攻击者每隔一个锁定期就能再试满一轮");
    }

    @Test
    @DisplayName("只锁定失败的那个来源")
    void lockoutIsPerSource() {
        fail(MAX_FAILURES, NOW);

        assertTrue(throttle.remainingLockout("5.6.7.8", NOW).isZero(),
                "全局锁定等于让攻击者可以一键把主播关在门外");
    }

    @Test
    @DisplayName("登录成功后清空该来源的失败记录")
    void successResetsFailures() {
        fail(MAX_FAILURES - 1, NOW);
        throttle.recordSuccess("1.2.3.4");
        fail(MAX_FAILURES - 1, NOW);

        assertTrue(throttle.remainingLockout("1.2.3.4", NOW).isZero(),
                "输错几次后成功登录，之前的失败不该继续累计");
    }

    @Test
    @DisplayName("隔得足够久的失败不算连续")
    void staleFailuresDoNotAccumulate() {
        fail(MAX_FAILURES - 1, NOW);

        // 几个月里零星输错几次不该攒够次数把自己锁掉
        Instant later = NOW.plus(Duration.ofDays(30));
        fail(MAX_FAILURES - 1, later);

        assertTrue(throttle.remainingLockout("1.2.3.4", later).isZero());
    }

    /**
     * 全局桶的容量。与实现里的常量对齐，改一处这里要跟着改——
     * 判据里写死这个数是有意的：它是「一分钟最多能猜多少次」的承诺，不该被静悄悄调大
     */
    private static final int GLOBAL_BURST = 20;

    /**
     * 把全局桶抽干
     * @param at 时刻
     * @return 抽干前成功取到的次数
     */
    private int drainGlobal(Instant at) {
        int taken = 0;
        while (throttle.tryAcquireGlobal(at)) {
            taken++;
            if (taken > GLOBAL_BURST * 10) {
                throw new IllegalStateException("桶取不完, 说明全局速率限制根本没生效");
            }
        }
        return taken;
    }

    /**
     * 🔴 M3 的核心判据：按 IP 锁定拦不住换着 IP 来的爆破，全局桶要拦得住
     * <p>
     * 注意这里<b>一次 recordFailure 都没调</b>——那正是分布式爆破的形态：
     * 每个地址只试两三次就换下一个，按 IP 的计数永远攒不够。
     */
    @Test
    @DisplayName("换着 IP 来也绕不过全局速率限制")
    void globalLimitStopsAttackersRotatingIps() {
        assertEquals(GLOBAL_BURST, drainGlobal(NOW), "一分钟的额度应当正好是一桶");

        assertFalse(throttle.tryAcquireGlobal(NOW), "额度用完后同一时刻不该再放行");
    }

    /**
     * 🔴 这条判据划的是「速率限制」与「全局锁定」的界
     * <p>
     * 全局锁定是<b>状态</b>：攻击停了主播照样被关在门外若干分钟，
     * 于是攻击者有了一个「一键把主播锁出去」的按钮，本类的类文档写明不做那种东西。
     * 速率限制是<b>流量</b>：桶随时间自己回，攻击一停，等几秒就能进，<b>不留任何惩罚</b>。
     * <p>
     * 少了这条，把全局限制实现成「一旦触发就锁 15 分钟」也一样能让上一条判据变绿。
     */
    @Test
    @DisplayName("洪水停下之后，等桶回一个令牌就能进，不留惩罚")
    void globalLimitIsNotALockout() {
        drainGlobal(NOW);
        assertFalse(throttle.tryAcquireGlobal(NOW));

        // 每分钟 20 个，即 3 秒回一个
        Instant afterOneToken = NOW.plusSeconds(3);
        assertTrue(throttle.tryAcquireGlobal(afterOneToken), "桶该回一个令牌了, 这时候还拒就是锁定不是限速");

        // 再过一分钟应当整桶回满，而不是留着「刚才被限过」的账
        Instant afterAMinute = afterOneToken.plusSeconds(60);
        assertEquals(GLOBAL_BURST, drainGlobal(afterAMinute), "限过之后额度应当完整回来, 不该打折");
    }

    /**
     * 反面：正常速率下永远碰不到这道限制
     * <p>
     * 少了这条，把桶容量设成 0（谁也别想登录）同样能让上面两条变绿。
     */
    @Test
    @DisplayName("正常速率的登录永远碰不到全局限制")
    void normalPaceIsNeverLimited() {
        Instant at = NOW;
        for (int i = 0; i < 100; i++) {
            // 3 秒一次正好等于补充速率，比任何真人的手速都快得多
            at = at.plusSeconds(3);
            assertTrue(throttle.tryAcquireGlobal(at), "第 " + (i + 1) + " 次正常速率的尝试被拒了");
        }
    }

    /**
     * 校验通过的那一次不算进猜口令的预算
     */
    @Test
    @DisplayName("校验通过后额度退还")
    void successfulCheckRefundsItsToken() {
        drainGlobal(NOW);
        assertFalse(throttle.tryAcquireGlobal(NOW));

        throttle.refundGlobal();

        assertTrue(throttle.tryAcquireGlobal(NOW), "退还之后应当立刻能再取一次");
    }

    /**
     * 桶不能越攒越多
     * <p>
     * 不封顶的话，攻击者先静置一小时就攒出 1200 次额度，
     * 「每分钟 20 次」就成了「平均每分钟 20 次」——而爆破在乎的正是瞬时那一波。
     */
    @Test
    @DisplayName("长期空闲不会攒出超过一桶的额度")
    void idleTimeDoesNotAccumulateBeyondTheBurst() {
        assertTrue(throttle.tryAcquireGlobal(NOW));

        Instant anHourLater = NOW.plus(Duration.ofHours(1));

        assertEquals(GLOBAL_BURST, drainGlobal(anHourLater), "空闲一小时后仍应只有一桶");
    }

    @Test
    @DisplayName("并发校验名额有限，超出的应拿不到")
    void limitsConcurrentVerifications() {
        // 校验一次口令要跑满一个核心数百毫秒，不设上限的话未登录的请求就能把 CPU 占满
        assertTrue(throttle.tryAcquireSlot());
        assertTrue(throttle.tryAcquireSlot());
        assertFalse(throttle.tryAcquireSlot(), "名额应当用尽");

        throttle.releaseSlot();
        assertTrue(throttle.tryAcquireSlot(), "归还后应能再次取得");
    }
}
