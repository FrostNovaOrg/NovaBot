package org.frostnova.nova.core.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("直播统计日")
class StatisticsDayTest {
    @Test
    @DisplayName("凌晨与昨晚属于同一统计日，六点切日且跨月跨年不受时钟时区影响")
    void earlyMorningBelongsToPreviousDay() {
        assertEquals(LocalDate.of(2026, 8, 9), StatisticsDay.dateOf(Instant.parse("2026-08-09T21:59:59Z")));
        assertEquals(LocalDate.of(2026, 8, 10), StatisticsDay.dateOf(Instant.parse("2026-08-09T22:00:00Z")));
        assertEquals(LocalDate.of(2026, 10, 31), StatisticsDay.dateOf(Instant.parse("2026-10-31T21:59:00Z")));
        assertEquals(LocalDate.of(2025, 12, 31), StatisticsDay.dateOf(Instant.parse("2025-12-31T21:59:00Z")));
        assertEquals(Instant.parse("2026-08-09T22:00:00Z"), StatisticsDay.startOf(LocalDate.of(2026, 8, 10)));
        for (String zone : new String[]{"UTC", "America/Los_Angeles", "Asia/Shanghai"}) {
            assertEquals(LocalDate.of(2026, 8, 9), StatisticsDay.today(
                    Clock.fixed(Instant.parse("2026-08-09T21:00:00Z"), ZoneId.of(zone))));
            assertEquals(LocalDate.of(2026, 8, 10), StatisticsDay.today(
                    Clock.fixed(Instant.parse("2026-08-09T23:00:00Z"), ZoneId.of(zone))));
        }
    }
}
