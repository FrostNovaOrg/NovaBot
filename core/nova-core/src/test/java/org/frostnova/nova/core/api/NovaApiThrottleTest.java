package org.frostnova.nova.core.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 无效令牌限速的计数表
 */
class NovaApiThrottleTest {
    private static final String LIMITED = "198.51.100.7";

    private final NovaApiTokenStoreTest.TestClock clock = new NovaApiTokenStoreTest.TestClock();

    private final NovaApiThrottle throttle = new NovaApiThrottle(3, Duration.ofMinutes(1), clock);

    // 抓的故障：刷一万个假来源就把自己的限速清掉了。
    @Test
    void floodOfOtherSourcesDoesNotReleaseALimitedSource() {
        for (int i = 0; i < 3; i++) {
            assertFalse(throttle.failure(LIMITED).limited());
        }
        assertTrue(throttle.failure(LIMITED).limited());

        for (int i = 0; i < 12_000; i++) {
            throttle.failure("10." + (i >> 16 & 255) + "." + (i >> 8 & 255) + "." + (i & 255));
            if (i % 1000 == 999) {
                assertTrue(throttle.failure(LIMITED).limited(), "涌入 " + (i + 1) + " 个别的来源后就放出来了");
            }
        }
        assertTrue(throttle.failure(LIMITED).limited(), "被限的来源仍该在被限");
        assertTrue(throttle.size() <= 10_000, "计数表不能无限长: " + throttle.size());

        // 窗口过了照常恢复
        clock.now = clock.now.plus(Duration.ofMinutes(1));
        assertFalse(throttle.failure(LIMITED).limited());
    }

    // 抓的故障：同一个 IPv6 /64 里换着地址交无效令牌，每个地址各算各的；IPv4 换成映射写法就另起一份计数。
    @Test
    void oneIpv6Slash64IsOneSource() {
        assertFalse(throttle.failure("2001:db8:1:2::1").limited());
        assertFalse(throttle.failure("2001:db8:1:2:0:0:0:2").limited());
        assertFalse(throttle.failure("2001:DB8:1:2:abcd:ef01:2345:6789").limited());
        assertTrue(throttle.failure("2001:db8:1:2:ffff::%eth0").limited());
        assertFalse(throttle.failure("2001:db8:1:3::1").limited(), "隔壁的 /64 另算");

        for (int i = 0; i < 3; i++) {
            assertFalse(throttle.failure(i % 2 == 0 ? "192.0.2.1" : "::ffff:192.0.2.1").limited());
        }
        assertTrue(throttle.failure("192.0.2.1").limited());
        assertFalse(throttle.failure("192.0.2.2").limited(), "IPv4 仍按单个地址算");

        for (int i = 0; i < 5_000; i++) {
            throttle.failure("2001:db8:9:9:" + Integer.toHexString(i) + "::1");
        }
        assertTrue(throttle.size() < 10, "同一个 /64 换着地址刷，不该把计数表撑大: " + throttle.size());
    }
}
