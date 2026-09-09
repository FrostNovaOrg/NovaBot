package org.frostnova.nova.bilibili.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分阶段退避测试
 * <p>
 * 全部用固定时刻推进，没有一处 sleep：靠 sleep 撞出来的时序测试必然时好时坏，
 * 那种测试会训练人去重跑而不是去看。
 */
@DisplayName("分阶段退避")
class BilibiliStagedBackoffTest {
    private static final Instant T0 = Instant.parse("2026-08-11T07:00:00Z");
    private static final Duration BASE = Duration.ofSeconds(600);
    private static final Duration CAP = Duration.ofHours(1);

    private BilibiliStagedBackoff backoff() {
        return new BilibiliStagedBackoff(BASE, CAP);
    }

    @Test
    @DisplayName("刚建好就可以尝试，不用先等一个周期")
    void dueImmediately() {
        assertTrue(backoff().due(T0), "启动那一刻本来就该查一次，没有理由先空等 10 分钟");
    }

    @Test
    @DisplayName("成功之后回到基准周期")
    void successReturnsToBase() {
        BilibiliStagedBackoff b = backoff();
        b.succeeded(T0);

        assertFalse(b.due(T0.plusSeconds(599)));
        assertTrue(b.due(T0.plusSeconds(600)));
        assertEquals(0, b.getConsecutiveFailures());
    }

    @Test
    @DisplayName("连续失败逐级加倍")
    void failuresDoubleTheDelay() {
        BilibiliStagedBackoff b = backoff();

        assertEquals(Duration.ofSeconds(600), b.failed(T0), "第 1 次失败：一个基准");
        assertEquals(Duration.ofSeconds(1200), b.failed(T0), "第 2 次：两个");
        assertEquals(Duration.ofSeconds(2400), b.failed(T0), "第 3 次：四个");
        assertEquals(3, b.getConsecutiveFailures());
    }

    @Test
    @DisplayName("到上限就停在上限，不会一路涨下去")
    void delayStopsAtCap() {
        BilibiliStagedBackoff b = backoff();

        for (int i = 0; i < 20; i++) {
            b.failed(T0);
        }

        assertEquals(CAP, b.failed(T0), "退避是为了别在故障期间空转，不是为了放弃");
    }

    @Test
    @DisplayName("⚠️ 失败上千次也不能把间隔算成负数")
    void hugeFailureCountDoesNotOverflow() {
        // 1L << 31 起就溢出。溢出后间隔为负，due() 恒为真，退避静默失效——
        // 而这恰好会在「跑了很久 + 长时间故障」这个最需要退避的场景下发生
        BilibiliStagedBackoff b = backoff();

        for (int i = 0; i < 5000; i++) {
            b.failed(T0);
        }

        Duration delay = b.failed(T0);
        assertFalse(delay.isNegative(), "间隔不能是负数");
        assertEquals(CAP, delay);
        assertFalse(b.due(T0.plus(CAP).minusSeconds(1)), "仍然要真的挡住");
    }

    @Test
    @DisplayName("一次成功清掉此前攒的全部失败")
    void successResetsTheLadder() {
        BilibiliStagedBackoff b = backoff();
        b.failed(T0);
        b.failed(T0);
        b.failed(T0);

        b.succeeded(T0);

        assertEquals(0, b.getConsecutiveFailures());
        assertTrue(b.due(T0.plus(BASE)), "回到基准周期，而不是继续按第 4 级等");
    }

    @Test
    @DisplayName("上限小于基准时按基准处理，等于关掉退避")
    void capBelowBaseDisablesBackoff() {
        BilibiliStagedBackoff b = new BilibiliStagedBackoff(BASE, Duration.ofSeconds(1));

        assertEquals(BASE, b.failed(T0));
        assertEquals(BASE, b.failed(T0), "不能因为配错了上限就把间隔缩到 1 秒去打接口");
    }

    @Test
    @DisplayName("基准配成 0 或负数时按 1 秒处理")
    void nonPositiveBaseIsClamped() {
        BilibiliStagedBackoff b = new BilibiliStagedBackoff(Duration.ZERO, CAP);

        assertEquals(Duration.ofSeconds(1), b.failed(T0), "0 间隔会变成忙等，配错也不能让它发生");
    }

    @Test
    @DisplayName("remaining 报的是还差多久，到点后为零")
    void remainingCountsDown() {
        BilibiliStagedBackoff b = backoff();
        b.succeeded(T0);

        assertEquals(Duration.ofSeconds(600), b.remaining(T0));
        assertEquals(Duration.ofSeconds(100), b.remaining(T0.plusSeconds(500)));
        assertEquals(Duration.ZERO, b.remaining(T0.plusSeconds(600)));
        assertEquals(Duration.ZERO, b.remaining(T0.plusSeconds(9000)), "过了点不该变成负数");
    }
}
