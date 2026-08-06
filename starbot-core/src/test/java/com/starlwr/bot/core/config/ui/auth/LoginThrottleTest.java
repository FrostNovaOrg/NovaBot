package com.starlwr.bot.core.config.ui.auth;

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
