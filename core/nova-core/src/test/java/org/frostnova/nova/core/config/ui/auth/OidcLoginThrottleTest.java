package org.frostnova.nova.core.config.ui.auth;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class OidcLoginThrottleTest {
    // 一个人不能耗尽全局额度，窗口过去后正常用户应能再发起登录。
    @Test
    void perIpWindowLeavesCapacityForOtherBrowsers() {
        var throttle = new OidcLoginThrottle();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 8; i++) {
            assertTrue(throttle.tryAcquire("127.0.0.1", now));
            throttle.releaseSlot();
        }
        assertFalse(throttle.tryAcquire("127.0.0.1", now));
        assertTrue(throttle.tryAcquire("127.0.0.2", now));
        throttle.releaseSlot();
        assertTrue(throttle.tryAcquire("127.0.0.1", now.plusSeconds(60)));
        throttle.releaseSlot();
    }

    // 并发名额已满的请求不能吃掉全局额度，否则短暂拥堵会把后来的人持续挡在门外。
    @Test
    void rejectedConcurrentRequestsRefundGlobalBudget() {
        var global = new LoginThrottle(1, Duration.ofMinutes(1));
        var throttle = new OidcLoginThrottle(global);
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        assertTrue(throttle.tryAcquire("127.0.0.1", now));
        assertTrue(throttle.tryAcquire("127.0.0.2", now));
        for (int i = 0; i < 30; i++) assertFalse(throttle.tryAcquire("ip-" + i, now));
        throttle.releaseSlot();
        throttle.releaseSlot();
        for (int i = 0; i < 18; i++) {
            assertTrue(throttle.tryAcquire("other-" + i, now), "并发拒绝必须归还全局额度");
            throttle.releaseSlot();
        }
        assertFalse(throttle.tryAcquire("exhausted", now));
    }
}
