package com.starlwr.bot.bilibili.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 大航海去重测试
 * <p>
 * 这里要防的是<b>把一次 ¥198 的开通记成两次</b>。同一件事由两条消息各播报一次，
 * 而重复计费在数据上完全说得通——营收翻倍、榜单翻倍，没有任何地方会报错。
 */
@DisplayName("大航海去重")
class BilibiliGuardDeduplicatorTest {
    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");

    private BilibiliGuardDeduplicator deduplicator;

    @BeforeEach
    void setUp() {
        deduplicator = new BilibiliGuardDeduplicator();
    }

    @Test
    @DisplayName("同一次开通的两条播报只应认第一条")
    void secondReportIsRejected() {
        assertTrue(deduplicator.firstReport(1L, 3, 1, NOW));
        assertFalse(deduplicator.firstReport(1L, 3, 1, NOW.plusSeconds(2)), "另一条消息播报的是同一件事");
    }

    @Test
    @DisplayName("两条消息的时间字段来源不同，差几秒仍应判为同一次")
    void toleratesTimestampSkew() {
        assertTrue(deduplicator.firstReport(1L, 3, 1, NOW));
        assertFalse(deduplicator.firstReport(1L, 3, 1, NOW.plusSeconds(25)));
        assertFalse(deduplicator.firstReport(1L, 3, 1, NOW.minusSeconds(25)), "先后顺序反过来也算同一次");
    }

    @Test
    @DisplayName("隔得足够久的再次开通应当照常计入")
    void laterPurchaseIsCounted() {
        assertTrue(deduplicator.firstReport(1L, 3, 1, NOW));
        assertTrue(deduplicator.firstReport(1L, 3, 1, NOW.plus(Duration.ofMinutes(5))));
    }

    @Test
    @DisplayName("不同的人、不同等级、不同数量都是不同的事")
    void differentPurchasesAreIndependent() {
        assertTrue(deduplicator.firstReport(1L, 3, 1, NOW));
        assertTrue(deduplicator.firstReport(2L, 3, 1, NOW), "另一个人");
        assertTrue(deduplicator.firstReport(1L, 2, 1, NOW), "另一个等级");
        assertTrue(deduplicator.firstReport(1L, 3, 3, NOW), "买了三个月");
    }

    @Test
    @DisplayName("认不出是谁买的时候应放行——漏记也好过整条丢掉")
    void unknownSenderPassesThrough() {
        assertTrue(deduplicator.firstReport(null, 3, 1, NOW));
        assertTrue(deduplicator.firstReport(null, 3, 1, NOW));
        assertTrue(deduplicator.firstReport(1L, null, 1, NOW));
    }

    @Test
    @DisplayName("记录数不应随运行时间无限增长")
    void doesNotGrowUnbounded() {
        for (int i = 0; i < 2000; i++) {
            deduplicator.firstReport((long) i, 3, 1, NOW.plusSeconds(i));
        }

        // 撑爆之后仍要能正确判重，说明清理没有把有效记录一起扔掉
        Instant late = NOW.plusSeconds(3000);
        assertTrue(deduplicator.firstReport(99999L, 3, 1, late));
        assertFalse(deduplicator.firstReport(99999L, 3, 1, late.plusSeconds(1)));
    }
}
