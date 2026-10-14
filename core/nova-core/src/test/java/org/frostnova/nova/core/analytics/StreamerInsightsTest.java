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
}
