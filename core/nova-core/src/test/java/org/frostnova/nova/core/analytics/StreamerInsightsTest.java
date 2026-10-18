package org.frostnova.nova.core.analytics;

import org.frostnova.nova.core.enums.LiveEndReason;
import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.model.StreamerSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("主播指定范围概况")
class StreamerInsightsTest {
    static final Instant FROM = Instant.parse("2026-10-14T22:00:00Z");
    static final Instant TO = Instant.parse("2026-10-21T00:00:00Z");
    static final LiveMetricCatalog.Insights RULES = new LiveMetricCatalog.Insights(
            List.of("gift", "messageValue", "guard"), "messages",
            List.of("enter", "chat"), List.of("chat"), "fans");

    @Test @DisplayName("显式区间与最近周期比较使用同一算法并保持半开边界")
    void explicitComparisonMatchesRecent() {
        var clock = Clock.fixed(TO, ZoneOffset.UTC);
        var range = StreamerInsights.recent(7, clock);
        var sessions = List.of(session(range.from(), Map.of("chat", List.of(1L)), Map.of()),
                session(TO.minusSeconds(1), Map.of("chat", List.of(2L)), Map.of()),
                session(range.from().minusSeconds(7 * 86400), Map.of("chat", List.of(1L)), Map.of()),
                session(TO.minusSeconds(7 * 86400 + 1), Map.of("chat", List.of(2L)), Map.of()),
                session(TO, Map.of("chat", List.of(3L)), Map.of()));
        var insights = new StreamerInsights();
        var explicit = insights.comparePeriods(sessions, List.of(), range.from(), TO,
                range.from().minusSeconds(7 * 86400), TO.minusSeconds(7 * 86400), RULES);
        assertEquals(insights.compareRecent(sessions, List.of(), 7, clock, RULES), explicit);
        assertEquals(2, explicit.current().sessions());
        assertEquals(2, explicit.previous().sessions());
    }

    LiveSession session(Instant start, Map<String, List<Long>> sets, Map<String, Long> anonymous) {
        return new LiveSession("sample", 1L, "sample", 2L, start.toEpochMilli(),
                start.plusSeconds(600).toEpochMilli(), 600, Map.of("gift", 10d,
                "messageValue", 30d, "guard", 198d, "messages", 4d), Map.of(),
                LiveEndReason.NORMAL, List.of(), 0, sets, 0, Map.of(), anonymous);
    }
    StreamerInsights.Result calculate(List<LiveSession> sessions) {
        return new StreamerInsights().calculate(sessions, List.of(), FROM, TO, RULES);
    }
    StreamerSnapshot snapshot(Instant at, double fans) {
        return new StreamerSnapshot("sample", 1L, "sample", at.toEpochMilli(), Map.of("fans", fans));
    }

    @Test @DisplayName("跨场与跨名单的同一观众只计一人")
    void audienceUnion() {
        var r = calculate(List.of(session(FROM, Map.of("enter", List.of(99L)), Map.of()),
                session(FROM.plusSeconds(3600), Map.of("chat", List.of(99L)), Map.of())));
        assertEquals(1, r.audience(), "同一观众跨场不得重复计数");
        assertEquals(1, r.daily().get(0).audience());
    }
    @Test @DisplayName("只进房的观众不计入互动")
    void entryIsNotInteraction() {
        var r = calculate(List.of(session(FROM, Map.of("enter", List.of(99L)), Map.of())));
        assertEquals(0, r.interactionAudience(), "进房不得算互动");
    }
    @Test @DisplayName("范围前出现的观众算回访，范围内新出现的算首次记录")
    void returningAudience() {
        var r = calculate(List.of(session(FROM.minusSeconds(600), Map.of("chat", List.of(99L)), Map.of()),
                session(FROM, Map.of("enter", List.of(99L, 100L)), Map.of())));
        assertEquals(new StreamerInsights.Composition(1, 1), r.composition(), "老观众不得算首次");
    }
    @Test @DisplayName("没有历史名单时构成未知，注明最早记录时刻")
    void noHistory() {
        var r = calculate(List.of(session(FROM.minusSeconds(600), Map.of(), null),
                session(FROM, Map.of("chat", List.of(100L)), Map.of())));
        assertNull(r.composition(), "没有历史名单不得全部算首次");
        assertEquals(FROM.toEpochMilli(), r.firstRecordedAt());
    }
    @Test @DisplayName("七天六点边界与凌晨场次的统计日正确")
    void statisticsDayBoundary() {
        var clock = Clock.fixed(Instant.parse("2026-10-20T23:00:00Z"), ZoneOffset.UTC);
        var range = StreamerInsights.recent(7, clock);
        assertEquals(FROM, range.from());
        var r = new StreamerInsights().calculate(List.of(
                session(FROM.minusSeconds(60), Map.of(), null), session(FROM, Map.of(), null),
                session(Instant.parse("2026-10-20T19:00:00Z"), Map.of(), null)), List.of(),
                range.from(), range.to(), RULES);
        assertEquals(2, r.sessions(), "六点之前一场应排除");
        assertEquals(7, r.daily().size());
        assertEquals(1, r.daily().get(5).sessions(), "凌晨场次属于昨天");
        assertEquals(0, r.daily().get(6).sessions());
    }
    @Test @DisplayName("全无名单时人数未知，混合名单时保留缺失说明")
    void missingLists() {
        var r = calculate(List.of(session(FROM, Map.of(), null)));
        assertNull(r.audience());
        assertNull(r.interactionAudience());
        assertNull(r.composition());
        assertEquals(1, r.coverage().missing(), "老场次不得当成零观众");
        r = calculate(List.of(session(FROM, Map.of(), null),
                session(FROM.plusSeconds(600), Map.of("chat", List.of(99L)), Map.of())));
        assertEquals(1, r.audience());
        assertEquals(1, r.coverage().missing());
    }
    @Test @DisplayName("完整、匿名、未知、无名单四种分别计数")
    void coverage() {
        var r = calculate(List.of(session(FROM, Map.of("chat", List.of()), Map.of()),
                session(FROM, Map.of("chat", List.of()), Map.of("chat", 1L)),
                session(FROM, Map.of("chat", List.of()), null), session(FROM, Map.of(), null)));
        assertEquals(new StreamerInsights.Coverage(1, 1, 1, 1), r.coverage(), "完整性不得混淆");
    }
    @Test @DisplayName("粉丝使用范围之前最近一次与结束之前最新一次")
    void fansBaseline() {
        var r = new StreamerInsights().calculate(List.of(), List.of(
                snapshot(FROM.minusSeconds(7200), 1000), snapshot(FROM.plusSeconds(60), 1010),
                snapshot(TO.minusSeconds(60), 1050), snapshot(TO, 9999)), FROM, TO, RULES);
        assertNotNull(r.fans(), "必须算出快照差值");
        assertEquals(50, r.fans().change());
        assertFalse(r.fans().partial());
    }
    @Test @DisplayName("没有起点前快照时注明范围内首次采样起算")
    void fansPartial() {
        var r = new StreamerInsights().calculate(List.of(), List.of(
                snapshot(FROM.plusSeconds(60), 1010), snapshot(TO.minusSeconds(60), 1050)), FROM, TO, RULES);
        assertNotNull(r.fans(), "范围内两次快照可计算");
        assertTrue(r.fans().partial());
        assertEquals(FROM.plusSeconds(60).toEpochMilli(), r.fans().fromAt());
        assertEquals(40, r.fans().change());
    }
    @Test @DisplayName("不足两次有效粉丝快照时无数据")
    void fansInsufficient() {
        var r = new StreamerInsights().calculate(List.of(), List.of(snapshot(FROM, 1000),
                new StreamerSnapshot("sample", 1L, "sample", TO.minusSeconds(60).toEpochMilli(), Map.of())),
                FROM, TO, RULES);
        assertNull(r.fans());
    }
    @Test @DisplayName("流水等于礼物、醒目留言与大航海价值之和")
    void revenue() {
        var r = calculate(List.of(session(FROM, Map.of(), null)));
        assertEquals(238d, r.revenue(), "流水必须与报告相同");
        assertEquals(4L, r.messages());
    }
    @Test @DisplayName("完整一周的任意起止可复用统计且不计终点场次")
    void arbitraryRangeAndUnknownPlatform() {
        var to = FROM.plusSeconds(7 * 86400);
        var r = new StreamerInsights().calculate(List.of(session(FROM, Map.of(), null),
                session(to, Map.of(), null)), List.of(), FROM, to, null);
        assertEquals(1, r.sessions());
        assertEquals(600, r.durationSeconds());
        assertEquals(7, r.daily().size());
        assertFalse(r.metricsKnown());
        assertNull(r.revenue());
    }
    // 插件可以采用任意关注计数键；没有声明时不能把未知显示成零。
    @Test @DisplayName("新增关注只使用插件声明的键，缺少规则保留未知")
    void followsUsesDeclaredKey() {
        var item = new LiveSession("sample", 1L, "sample", 2L, FROM.toEpochMilli(),
                FROM.plusSeconds(7200).toEpochMilli(), 7200,
                Map.of("subscriptions", 6d, "anotherCount", 99d), Map.of());
        var rules = new LiveMetricCatalog.Insights(List.of("gift"), "messages", List.of("enter"),
                List.of("chat"), "fans", "subscriptions");
        var engine = new StreamerInsights();
        var result = engine.calculate(List.of(item), List.of(), FROM, TO, rules);
        assertEquals(6L, result.follows(), "必须读取插件指定的新增关注项");
        assertEquals(6L, result.daily().get(0).follows());
        for (var period : LiveSessionAnalytics.Period.values()) {
            var buckets = engine.trend(List.of(item), FROM, TO, period, rules);
            assertEquals(6, buckets.stream().mapToLong(b -> b.follows()).sum());
            assertTrue(engine.trend(List.of(item), FROM, TO, period, RULES).stream().allMatch(b -> b.follows() == null));
        }
        assertNull(engine.calculate(List.of(item), List.of(), FROM, TO, RULES).follows());
        assertEquals(0L, engine.calculate(List.of(), List.of(), FROM, TO, rules).follows());
    }

    // 同周多场及跨周的重复名单都要去重；首尾周期只能计入所选范围的场次。
    @Test @DisplayName("趋势按周期去重名单且首尾桶不混入范围外场次")
    void trendAudienceAndBoundaries() {
        var engine = new StreamerInsights();
        var items = List.of(session(FROM.minusSeconds(60), Map.of("enter", List.of(200L)), Map.of()),
                session(FROM, Map.of("enter", List.of(99L)), Map.of()),
                session(FROM.plusSeconds(3600), Map.of("chat", List.of(99L, 100L)), Map.of()),
                session(TO.minusSeconds(3600), Map.of("enter", List.of(99L)), Map.of()),
                session(TO, Map.of("enter", List.of(201L)), Map.of()));
        var weeks = engine.trend(items, FROM, TO, LiveSessionAnalytics.Period.WEEK, RULES);
        assertEquals(List.of(2, 1), weeks.stream().map(StreamerInsights.TrendBucket::audience).toList());
        assertEquals(2, engine.calculate(items, List.of(), FROM, TO, RULES).audience());
        assertEquals(List.of(FROM.toEpochMilli(), FROM.plusSeconds(3600).toEpochMilli()), weeks.get(0).starts());
        assertEquals(3 * 238d, weeks.stream().mapToDouble(b -> b.revenue()).sum());
        var months = engine.trend(items, FROM, TO, LiveSessionAnalytics.Period.MONTH, RULES);
        assertEquals(2, months.get(0).audience());
        assertEquals(3, months.get(0).sessions());
    }

}
