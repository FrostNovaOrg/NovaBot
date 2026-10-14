package org.frostnova.nova.core.analytics;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 直播统计日。主播多播到凌晨，运营把同一晚看作一天，因此固定在东八区早上 6 点切日。
 * 场次整场归入开播时刻的统计日；这条规则不随服务器时区或设置变化。
 */
public final class StatisticsDay {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public static final LocalTime START_TIME = LocalTime.of(6, 0);

    private StatisticsDay() {
    }

    /** 某个时刻所属的统计日。 */
    public static LocalDate dateOf(Instant instant) {
        var local = instant.atZone(ZONE);
        LocalDate date = local.toLocalDate();
        return local.toLocalTime().isBefore(START_TIME) ? date.minusDays(1) : date;
    }

    /** 某个统计日的起点，含；终点为下一统计日的起点，不含。 */
    public static Instant startOf(LocalDate date) {
        return date.atTime(START_TIME).atZone(ZONE).toInstant();
    }

    /** 时钟给出的当前统计日，时钟自身的时区不影响结果。 */
    public static LocalDate today(Clock clock) {
        return dateOf(clock.instant());
    }
}
