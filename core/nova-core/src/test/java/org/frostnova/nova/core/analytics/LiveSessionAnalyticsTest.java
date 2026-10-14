package org.frostnova.nova.core.analytics;

import org.frostnova.nova.core.model.LiveSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 直播场次周期聚合测试
 * <p>
 * 运营会照着这些数字做判断，**一个看似合理的错数比没有数更糟**。
 * 时区、跨零点、空周期这三处最容易悄悄算错，逐个钉死。
 */
@DisplayName("场次周期聚合")
class LiveSessionAnalyticsTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private static final Set<String> METRICS = Set.of("danmu_count", "gift_value");

    private LiveSessionAnalytics analytics;

    @BeforeEach
    void setUp() {
        analytics = new LiveSessionAnalytics();
    }

    @Test
    @DisplayName("没有场次时应返回空表而非一格空周期")
    void emptyWhenNoSession() {
        assertTrue(analytics.aggregate(List.of(), LiveSessionAnalytics.Period.WEEK, METRICS).isEmpty());
    }

    @Test
    @DisplayName("同一周的多场应合并，指标与时长相加")
    void mergesSessionsInSameWeek() {
        // 2026-08-05 是周三，08-07 是周五
        List<LiveSession> sessions = List.of(
                session("2026-08-05T20:00", 3600, 100, 10),
                session("2026-08-07T20:00", 1800, 50, 5));

        List<LiveSessionAnalytics.Bucket> buckets =
                analytics.aggregate(sessions, LiveSessionAnalytics.Period.WEEK, METRICS);

        assertEquals(1, buckets.size());
        assertEquals(2, buckets.get(0).sessions());
        assertEquals(5400, buckets.get(0).durationSeconds());
        assertEquals(150.0, buckets.get(0).metrics().get("danmu_count"));
        assertEquals(15.0, buckets.get(0).metrics().get("gift_value"));
    }

    @Test
    @DisplayName("周以周一为界，周日与次日的周一应分属两周")
    void weekStartsOnMonday() {
        // 2026-08-09 周日、2026-08-10 周一
        List<LiveSessionAnalytics.Bucket> buckets = analytics.aggregate(
                List.of(session("2026-08-09T20:00", 60, 1, 1), session("2026-08-10T20:00", 60, 1, 1)),
                LiveSessionAnalytics.Period.WEEK, METRICS);

        assertEquals(2, buckets.size());
        assertEquals("2026-08-03 ~ 08-09", buckets.get(0).label());
        assertEquals("2026-08-10 ~ 08-16", buckets.get(1).label());
    }

    @Test
    @DisplayName("按东八区统计日分周，周一凌晨开播仍属于上一周")
    void bucketsByShanghaiStatisticsDay() {
        // 东八区周一 00:30 还在周日的统计日，整场归上一周
        List<LiveSessionAnalytics.Bucket> buckets = analytics.aggregate(
                List.of(session("2026-08-10T00:30", 3600, 10, 1)),
                LiveSessionAnalytics.Period.WEEK, METRICS);

        assertEquals(1, buckets.size());
        assertEquals("2026-08-03 ~ 08-09", buckets.get(0).label());
    }

    @Test
    @DisplayName("跨零点的直播整场算在开播那一天所属的周期")
    void sessionSpanningMidnightBelongsToStartPeriod() {
        // 周日 23:30 开播，播到周一 01:30
        List<LiveSessionAnalytics.Bucket> buckets = analytics.aggregate(
                List.of(session("2026-08-09T23:30", 7200, 10, 1)),
                LiveSessionAnalytics.Period.WEEK, METRICS);

        assertEquals(1, buckets.size(), "不该被拆成两周各算半截");
        assertEquals("2026-08-03 ~ 08-09", buckets.get(0).label());
        assertEquals(7200, buckets.get(0).durationSeconds());
    }

    @Test
    @DisplayName("跨月的直播整场算在开播那个月")
    void sessionSpanningMonthBelongsToStartMonth() {
        List<LiveSessionAnalytics.Bucket> buckets = analytics.aggregate(
                List.of(session("2026-07-31T23:00", 7200, 10, 1)),
                LiveSessionAnalytics.Period.MONTH, METRICS);

        assertEquals(List.of("2026-07"), buckets.stream().map(LiveSessionAnalytics.Bucket::label).toList());
    }

    @Test
    @DisplayName("中间没播的周期也要出现，且各项为零")
    void fillsEmptyPeriods() {
        List<LiveSessionAnalytics.Bucket> buckets = analytics.aggregate(
                List.of(session("2026-08-05T20:00", 3600, 100, 10),
                        session("2026-08-26T20:00", 3600, 100, 10)),
                LiveSessionAnalytics.Period.WEEK, METRICS);

        // 8/3、8/10、8/17、8/24 四周，中间两周没播
        assertEquals(4, buckets.size(), "跳过空周会让趋势看起来比实际连贯");
        assertEquals(0, buckets.get(1).sessions());
        assertEquals(0, buckets.get(1).durationSeconds());
        assertTrue(buckets.get(1).metrics().isEmpty());
        assertEquals(1, buckets.get(3).sessions());
    }

    @Test
    @DisplayName("按月聚合应逐月排列，跨年不断档")
    void fillsEmptyMonthsAcrossYear() {
        List<LiveSessionAnalytics.Bucket> buckets = analytics.aggregate(
                List.of(session("2025-11-15T20:00", 60, 1, 1), session("2026-02-15T20:00", 60, 1, 1)),
                LiveSessionAnalytics.Period.MONTH, METRICS);

        assertEquals(List.of("2025-11", "2025-12", "2026-01", "2026-02"),
                buckets.stream().map(LiveSessionAnalytics.Bucket::label).toList());
    }

    @Test
    @DisplayName("跨年的那一周，标签两端都要写全年份")
    void weekLabelSpellsOutYearWhenSpanning() {
        // 2025-12-29 周一 ~ 2026-01-04 周日
        List<LiveSessionAnalytics.Bucket> buckets = analytics.aggregate(
                List.of(session("2025-12-30T20:00", 60, 1, 1)),
                LiveSessionAnalytics.Period.WEEK, METRICS);

        assertEquals("2025-12-29 ~ 2026-01-04", buckets.get(0).label());
    }

    @Test
    @DisplayName("未声明为可累加的指标不应出现在统计里")
    void skipsMetricsNotDeclaredSummable() {
        LiveSession session = new LiveSession("bilibili", 1L, "测试主播", 2L,
                millis("2026-08-05T20:00"), millis("2026-08-05T21:00"), 3600,
                Map.of("danmu_count", 100.0, "fans_at_start", 12345.0), Map.of());

        Map<String, Double> metrics = analytics
                .aggregate(List.of(session), LiveSessionAnalytics.Period.WEEK, METRICS)
                .get(0).metrics();

        assertEquals(100.0, metrics.get("danmu_count"));
        // 把「开播时的粉丝数」在周期里累加，得到的既不是期初也不是期末粉丝数
        assertFalse(metrics.containsKey("fans_at_start"), "开播快照类指标相加没有意义，不该出现");
    }

    @Test
    @DisplayName("计分表累加出来的是人次，同一人跨场会被重复计入")
    void userCountsAccumulateAsPersonTimes() {
        // 两场各 10 人，其中可能是同一批人——归档里只有人数没有人的集合，
        // 因此周期内的独立人数根本算不出来，界面必须写「人次」
        List<LiveSessionAnalytics.Bucket> buckets = analytics.aggregate(
                List.of(session("2026-08-05T20:00", 60, 1, 1), session("2026-08-06T20:00", 60, 1, 1)),
                LiveSessionAnalytics.Period.WEEK, METRICS);

        assertEquals(20L, buckets.get(0).userTimes().get("danmu_users"));
    }

    @Test
    @DisplayName("场次顺序打乱不影响结果")
    void orderIndependent() {
        List<LiveSessionAnalytics.Bucket> forward = analytics.aggregate(
                List.of(session("2026-08-05T20:00", 60, 1, 1), session("2026-08-26T20:00", 60, 2, 2)),
                LiveSessionAnalytics.Period.WEEK, METRICS);
        List<LiveSessionAnalytics.Bucket> backward = analytics.aggregate(
                List.of(session("2026-08-26T20:00", 60, 2, 2), session("2026-08-05T20:00", 60, 1, 1)),
                LiveSessionAnalytics.Period.WEEK, METRICS);

        assertEquals(forward, backward);
    }

    @Test
    @DisplayName("周期起止时刻应是东八区早上六点，且左闭右开")
    void bucketRangeIsShanghaiSixOClock() {
        LiveSessionAnalytics.Bucket bucket = analytics.aggregate(
                List.of(session("2026-08-05T20:00", 60, 1, 1)),
                LiveSessionAnalytics.Period.WEEK, METRICS).get(0);

        assertEquals(millis("2026-08-03T06:00"), bucket.start());
        assertEquals(millis("2026-08-10T06:00"), bucket.end());
    }

    @Test
    @DisplayName("周一与月初凌晨开播归上一周期，周月边界均为东八区六点")
    void earlyMorningUsesPreviousPeriodAndSixOClockBounds() {
        LiveSessionAnalytics.Bucket week = analytics.aggregate(
                List.of(session("2026-08-10T02:00", 3600, 10, 1)),
                LiveSessionAnalytics.Period.WEEK, METRICS).get(0);
        LiveSessionAnalytics.Bucket month = analytics.aggregate(
                List.of(session("2026-11-01T03:00", 3600, 10, 1)),
                LiveSessionAnalytics.Period.MONTH, METRICS).get(0);
        assertAll(
                () -> assertEquals("2026-08-03 ~ 08-09", week.label()),
                () -> assertEquals(millis("2026-08-03T06:00"), week.start()),
                () -> assertEquals(millis("2026-08-10T06:00"), week.end()),
                () -> assertEquals("2026-10", month.label()),
                () -> assertEquals(millis("2026-10-01T06:00"), month.start()),
                () -> assertEquals(millis("2026-11-01T06:00"), month.end()));
    }

    private LiveSession session(String localDateTime, long durationSeconds, double danmu, double gift) {
        long start = millis(localDateTime);
        return new LiveSession("bilibili", 19142561034510L, "测试主播", 47121903112096L,
                start, start + durationSeconds * 1000, durationSeconds,
                Map.of("danmu_count", danmu, "gift_value", gift),
                Map.of("danmu_users", 10));
    }

    private long millis(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(SHANGHAI).toInstant().toEpochMilli();
    }
}
