package org.frostnova.nova.core.analytics;

import org.frostnova.nova.core.enums.LiveEndReason;
import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.model.RoomInfoSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.frostnova.nova.core.analytics.WeeklyReportTest.*;
import static org.junit.jupiter.api.Assertions.*;

class WeeklyReportComparisonTest {
    // Prevent a report from publishing a different change than the streamer page for the same archives.
    @Test void weeklyChangesUseBothWeeks() {
        var sessions = List.of(session(FROM.minusSeconds(7 * 86400), List.of(1L)),
                session(FROM.minusSeconds(1), List.of(2L)),
                session(FROM, List.of(1L)), session(FROM.plusSeconds(1), List.of(2L)),
                session(TO.minusSeconds(1), List.of(3L)), session(TO, List.of(4L)));
        var d = WeeklyReport.calculate("示例", sessions, List.of(), NOW, RULES, true);
        var direct = new StreamerInsights().comparePeriods(sessions, List.of(), FROM, TO,
                FROM.minusSeconds(7 * 86400), FROM, RULES);
        assertEquals(direct.comparisons(), d.comparisons());
        assertEquals(direct.comparisonNote(), d.comparisonNote());
        for (int i = 0; i < 5; i++) assertEquals("较上周 +50%", WeeklyReport.cards(d).get(i).note());
        assertTrue(WeeklyReport.summary(d).contains("比前一周：时长 +50%，流水 +50%，弹幕 +50%"));
        assertTrue(WeeklyReport.text(d).contains("（较上周 +50%）"));
    }

    // Prevent unsupported metrics, zero denominators, or missing lists from looking comparable.
    @Test void unsupportedZeroAndMissingLists() {
        var complete = List.of(session(FROM.minusSeconds(2), List.of(1L)), session(FROM.minusSeconds(1), List.of(2L)),
                session(FROM, List.of(1L)), session(FROM.plusSeconds(1), List.of(2L)));
        var d = WeeklyReport.calculate("示例", complete, List.of(), NOW, null, true);
        assertEquals("平台没有这项口径", d.comparisons().get("messages").reason());
        assertTrue(WeeklyReport.text(d).contains("平台没有这项口径"));
        var sessions = new ArrayList<>(complete);
        sessions.set(0, withMetrics(complete.get(0), Map.of("messages", 0d), Map.of("chat", List.of(1L))));
        sessions.set(1, withMetrics(complete.get(1), Map.of("messages", 0d), Map.of("chat", List.of(2L))));
        d = WeeklyReport.calculate("示例", sessions, List.of(), NOW, RULES, true);
        assertEquals("较上周 —", WeeklyReport.cards(d).get(1).note());
        assertEquals("较上周 —", WeeklyReport.cards(d).get(2).note());
        assertTrue(WeeklyReport.text(d).contains("上周为 0"));
        assertFalse(WeeklyReport.summary(d).contains("流水" + " +"));
        sessions = new ArrayList<>(complete);
        sessions.set(3, withMetrics(complete.get(3), complete.get(3).metrics(), Map.of()));
        d = WeeklyReport.calculate("示例", sessions, List.of(), NOW, RULES, true);
        assertEquals("有场次没有观众名单", d.comparisons().get("audience").reason());
        assertEquals("较上周 —", WeeklyReport.cards(d).get(3).note());
        assertTrue(WeeklyReport.text(d).contains("有场次没有观众名单"));
    }

    LiveSession withMetrics(LiveSession s, Map<String, Double> metrics, Map<String, List<Long>> sets) {
        return new LiveSession(s.platform(), s.uid(), s.uname(), s.roomId(), s.startTime(), s.endTime(), s.durationSeconds(),
                metrics, s.userCounts(), s.endReason(), s.titles(), 0, sets, 0, Map.of(), Map.of());
    }

    // Prevent console rounding and the week wording from drifting, including small declines.
    @Test void roundingNotesAndMoneyPrivacy() {
        assertEquals("持平", WeeklyReport.change(.00499));
        assertEquals("持平", WeeklyReport.change(-.00499));
        assertEquals("+1%", WeeklyReport.change(.005));
        assertEquals("−1%", WeeklyReport.change(-.005));
        assertEquals("+13%", WeeklyReport.change(.125));
        var sessions = List.of(session(FROM.minusSeconds(3), List.of(1L)), session(FROM.minusSeconds(2), List.of(2L)),
                withMetrics(session(FROM, List.of(1L)), Map.of("gift", 300d, "messages", 1000d), Map.of("chat", List.of(1L))),
                withMetrics(session(FROM.plusSeconds(1), List.of(2L)), Map.of("gift", 300d, "messages", 1000d), Map.of("chat", List.of(2L))));
        var d = WeeklyReport.calculate("示例", sessions, List.of(), NOW, RULES, false);
        String raw = com.alibaba.fastjson2.JSON.toJSONString(d) + WeeklyReport.summary(d) + WeeklyReport.text(d);
        for (String token : List.of("流水", "元", "revenue", "300.00")) assertFalse(raw.contains(token), token);
        assertEquals("", WeeklyReport.cards(d).get(1).note());
        assertEquals("较上周持平", WeeklyReport.cards(d).get(0).note());
        assertTrue(WeeklyReport.cards(d).get(2).note().startsWith("较上周 −"));
        var s = sessions.get(0);
        var unknown = new LiveSession(s.platform(), s.uid(), s.uname(), s.roomId(), s.startTime(), s.endTime(),
                s.durationSeconds(), s.metrics(), s.userCounts(), s.endReason(), s.titles(), 0, s.userSets(), 0, Map.of(), null);
        var all = new ArrayList<>(sessions); all.set(0, unknown);
        d = WeeklyReport.calculate("示例", all, List.of(), NOW, RULES, true);
        assertTrue(WeeklyReport.comparisonExplanation(d).contains("名单完整性未知，观众数对比仅供参考"));
        assertFalse(WeeklyReport.comparisonExplanation(d).contains("不比较"));
        assertEquals("", WeeklyReport.comparisonExplanation(WeeklyReport.calculate("示例", sessions, List.of(), NOW, RULES, true)));
    }

    // Prevent a fully covered fans interval from being presented as a partial measurement or a comparison.
    @Test void fansBoundaryNote() {
        var snapshots = List.of(new org.frostnova.nova.core.model.StreamerSnapshot("sample", 1L, "示例",
                        FROM.toEpochMilli(), Map.of("fans", 100d)),
                new org.frostnova.nova.core.model.StreamerSnapshot("sample", 1L, "示例",
                        TO.minusSeconds(1).toEpochMilli(), Map.of("fans", 120d)));
        var d = WeeklyReport.calculate("示例", sessions(), snapshots, NOW, RULES, true);
        assertEquals("按区间边界快照计算", WeeklyReport.cards(d).get(5).note());
        assertNull(WeeklyReport.cards(d).get(5).changeRatio());
    }

    // Prevent percentages for an absent baseline or a week with too few sessions.
    @Test void unavailableChangesExplainWhy() {
        var d = WeeklyReport.calculate("示例", List.of(session(FROM, List.of(1L)),
                session(FROM.plusSeconds(1), List.of(2L))), List.of(), NOW, RULES, true);
        for (int i = 0; i < 5; i++) assertEquals("较上周 —", WeeklyReport.cards(d).get(i).note());
        assertTrue(WeeklyReport.text(d).contains("较上周：上周没有开播，不比较。"));
        assertFalse(WeeklyReport.summary(d).contains("比前一周"));
        d = WeeklyReport.calculate("示例", List.of(session(FROM.minusSeconds(1), List.of(1L)),
                session(FROM, List.of(2L))), List.of(), NOW, RULES, true);
        assertTrue(WeeklyReport.text(d).contains("本周或上周少于 2 场"));
    }

    // Prevent unknown snapshots becoming zero, interrupted sessions appearing normal, or overflow disappearing.
    @Test void sessionRowsKeepUnknownZeroReasonAndOverflow() {
        var sessions = new ArrayList<LiveSession>();
        for (int i = 0; i < 8; i++) sessions.add(session(FROM.plusSeconds(i), List.of(1L)));
        sessions.add(new LiveSession("sample", 1L, "示例", 2L, FROM.plusSeconds(10).toEpochMilli(),
                FROM.plusSeconds(70).toEpochMilli(), 60, Map.of(), Map.of(), LiveEndReason.CUT_OFF,
                List.of(new RoomInfoSnapshot(0, "旧标题", ""), new RoomInfoSnapshot(1, "最后标题", "")),
                66, Map.of(), 40));
        sessions.add(new LiveSession("sample", 1L, "示例", 2L, FROM.plusSeconds(9).toEpochMilli(),
                FROM.plusSeconds(69).toEpochMilli(), 60, Map.of("messages", 0d), Map.of()));
        var text = WeeklyReport.text(WeeklyReport.calculate("示例", sessions, List.of(), NOW, RULES, true));
        assertTrue(text.contains("本周场次 · 10 场"));
        assertTrue(text.contains("流水 —"));
        assertTrue(text.contains("流水 0.00 元"));
        assertTrue(text.contains("被平台切断"));
        assertTrue(text.contains("缺 1 分 6 秒"));
        assertTrue(text.contains("最后标题"));
        assertFalse(text.contains("旧标题"));
        assertTrue(text.contains("另有 3 场未列出"));
        assertTrue(text.indexOf("流水 —") < text.indexOf("流水 0.00 元"));
    }
}
