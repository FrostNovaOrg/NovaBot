package com.starlwr.bot.core.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网络日志去重抑制测试
 * <p>
 * 这个东西唯一的风险是「抑制过头」：把要查的那一条也吞掉。所以用例的重点全在
 * <b>什么必须放行</b>——不同接口、失败、窗口过后、以及被抑制的条数不许无声消失。
 */
@DisplayName("网络日志去重抑制")
class NetworkLogThrottleTest {
    private static final int WINDOW = 60;

    private NetworkLogThrottle throttle;

    @BeforeEach
    void setUp() {
        throttle = new NetworkLogThrottle();
    }

    @Test
    @DisplayName("窗口内同类只放行第一条")
    void suppressesWithinWindow() {
        long t = 1_000_000L;

        assertTrue(throttle.decide("GET", "https://a.example/x?room=1", WINDOW, t).log());
        assertFalse(throttle.decide("GET", "https://a.example/x?room=2", WINDOW, t + 1000).log());
        assertFalse(throttle.decide("GET", "https://a.example/x?room=3", WINDOW, t + 2000).log());
    }

    @Test
    @DisplayName("查询串不参与归类：装的正是每次都不同的房间号与 uid")
    void classifiesByPathNotQuery() {
        long t = 1_000_000L;

        assertTrue(throttle.decide("GET", "https://a.example/x?roomid=111", WINDOW, t).log());
        // 带上查询串归类的话，没有两条请求是同类的，抑制永远不会生效
        assertFalse(throttle.decide("GET", "https://a.example/x?roomid=222", WINDOW, t + 1).log());
    }

    @Test
    @DisplayName("不同接口互不影响，不会被别人的窗口连坐")
    void differentEndpointsAreIndependent() {
        long t = 1_000_000L;

        assertTrue(throttle.decide("GET", "https://a.example/x", WINDOW, t).log());
        assertTrue(throttle.decide("GET", "https://a.example/y", WINDOW, t).log());
        assertTrue(throttle.decide("POST", "https://a.example/x", WINDOW, t).log());
    }

    @Test
    @DisplayName("窗口过后重新放行，并把期间抑制掉的条数带出来")
    void reportsSuppressedCountAfterWindow() {
        long t = 1_000_000L;

        assertTrue(throttle.decide("GET", "https://a.example/x", WINDOW, t).log());
        for (int i = 1; i <= 5; i++) {
            assertFalse(throttle.decide("GET", "https://a.example/x", WINDOW, t + i * 1000L).log());
        }

        NetworkLogThrottle.Decision next = throttle.decide("GET", "https://a.example/x", WINDOW, t + WINDOW * 1000L);

        assertTrue(next.log());
        assertEquals(5, next.suppressedCount(), "抑制掉的条数不许无声消失");
        assertEquals("（期间同类 5 次已抑制）", next.suffix());
    }

    @Test
    @DisplayName("放行后计数归零，不会把上一轮的数字重复报一遍")
    void countResetsAfterReporting() {
        long t = 1_000_000L;
        throttle.decide("GET", "https://a.example/x", WINDOW, t);
        throttle.decide("GET", "https://a.example/x", WINDOW, t + 1000);
        throttle.decide("GET", "https://a.example/x", WINDOW, t + WINDOW * 1000L);

        NetworkLogThrottle.Decision third = throttle.decide("GET", "https://a.example/x", WINDOW, t + 2L * WINDOW * 1000L);

        assertTrue(third.log());
        assertEquals(0, third.suppressedCount());
        assertEquals("", third.suffix(), "没有被抑制的条数时不该多出一句话");
    }

    @Test
    @DisplayName("窗口设为 0 或负数时一律放行，等于关掉抑制")
    void windowZeroDisablesSuppression() {
        long t = 1_000_000L;

        for (int i = 0; i < 5; i++) {
            assertTrue(throttle.decide("GET", "https://a.example/x", 0, t + i).log());
            assertTrue(throttle.decide("GET", "https://a.example/x", -1, t + i).log());
        }
    }

    @Test
    @DisplayName("地址为 null 不抛异常")
    void toleratesNullUrl() {
        assertTrue(throttle.decide("GET", null, WINDOW, 1_000_000L).log());
        assertFalse(throttle.decide("GET", null, WINDOW, 1_000_001L).log());
    }

    @Test
    @DisplayName("片段与查询串都被剥掉")
    void stripsFragmentToo() {
        long t = 1_000_000L;

        assertTrue(throttle.decide("GET", "https://a.example/x#frag", WINDOW, t).log());
        assertFalse(throttle.decide("GET", "https://a.example/x", WINDOW, t + 1).log());
    }
}
