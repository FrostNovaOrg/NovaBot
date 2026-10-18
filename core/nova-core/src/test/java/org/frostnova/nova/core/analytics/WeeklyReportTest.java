package org.frostnova.nova.core.analytics;

import com.alibaba.fastjson2.JSON;
import org.frostnova.nova.core.enums.LiveEndReason;
import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.model.StreamerSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("运营周报内容")
class WeeklyReportTest {
    static final Instant NOW = Instant.parse("2026-10-12T02:00:00Z");
    static final Instant FROM = Instant.parse("2026-10-04T22:00:00Z");
    static final Instant TO = Instant.parse("2026-10-11T22:00:00Z");
    static final LiveMetricCatalog.Insights RULES = new LiveMetricCatalog.Insights(
            List.of("gift"), "messages", List.of("enter", "chat"), List.of("chat"), "fans");
    static LiveSession session(Instant at, List<Long> users) {
        return new LiveSession("sample", 1L, "主播示例", 2L, at.toEpochMilli(), at.plusSeconds(6600).toEpochMilli(),
                6600, Map.of("gift", 246.9, "messages", 1780d), Map.of(), LiveEndReason.NORMAL,
                List.of(), 0, Map.of("chat", users), 0, Map.of(), Map.of());
    }
    static List<LiveSession> sessions() {
        return List.of(session(FROM.minusSeconds(86400), List.of(9200000001L)),
                session(FROM, List.of(9200000001L, 9200000002L)),
                session(FROM.plusSeconds(86400), List.of(9200000002L)),
                session(FROM.plusSeconds(3 * 86400), List.of(9200000003L)),
                session(FROM.plusSeconds(4 * 86400), List.of(9200000001L)),
                session(TO.minusSeconds(10800), List.of(9200000002L)),
                session(TO, List.of(9200000004L)));
    }
    static List<StreamerSnapshot> snapshots() {
        return List.of(new StreamerSnapshot("sample", 1L, "主播示例", FROM.plusSeconds(7200).toEpochMilli(), Map.of("fans", 100d)),
                new StreamerSnapshot("sample", 1L, "主播示例", TO.minusSeconds(60).toEpochMilli(), Map.of("fans", 125d)));
    }
    WeeklyReport.Data report(boolean money) { return WeeklyReport.calculate("主播示例", sessions(), snapshots(), NOW, RULES, money); }

    @Test @DisplayName("周一凌晨场次属于上一周，六点开播排除")
    void mondayBoundary() {
        var r = report(true);
        assertEquals(5, r.metrics().get("sessions"));
        assertEquals(1, r.daily().get(6).sessions(), "凌晨场次应归入周日");
        assertEquals(new StreamerInsights.Range(FROM, TO), WeeklyReport.previousWeek(NOW));
        assertEquals(new StreamerInsights.Range(FROM.minusSeconds(7 * 86400), FROM),
                WeeklyReport.previousWeek(TO.minusSeconds(3600)), "周一五点应再退一周");
    }
    @Test @DisplayName("六个指标与首次回访和控制台统计一致")
    void matchesInsights() {
        var direct = new StreamerInsights().calculate(sessions(), snapshots(), FROM, TO, RULES);
        var r = report(true);
        assertEquals(direct.sessions(), r.metrics().get("sessions"));
        assertEquals(direct.durationSeconds(), r.metrics().get("durationSeconds"));
        assertEquals(direct.revenue(), r.metrics().get("revenue"));
        assertEquals(direct.messages(), r.metrics().get("messages"));
        assertEquals(direct.audience(), r.metrics().get("audience"));
        assertEquals(direct.interactionAudience(), r.metrics().get("interactionAudience"));
        assertEquals(direct.fans(), r.fans());
        assertEquals(direct.composition(), r.composition());
        assertEquals(direct.coverage(), r.coverage());
        for (int i = 0; i < 7; i++) {
            assertEquals(direct.daily().get(i).revenue(), r.daily().get(i).metrics().get("revenue"));
            assertEquals(direct.daily().get(i).messages(), r.daily().get(i).metrics().get("messages"));
        }
        assertTrue(WeeklyReport.cards(r).get(5).note().contains("10 月 5 日 08:00"));
    }
    @Test @DisplayName("隐藏金额的数据摘要文字版与日图都没有金额")
    void hidesMoney() {
        var r = report(false);
        String raw = JSON.toJSONString(r) + WeeklyReport.summary(r) + WeeklyReport.text(r);
        for (String token : List.of("revenue", "流水", "元", "¥", "246.9", "1234.5")) assertFalse(raw.contains(token), token);
        assertEquals("messages", r.chartMetric());
        assertEquals("场次", WeeklyReport.cards(r).get(1).label());
        assertEquals(1780L, r.daily().get(0).metrics().get("messages"));
    }
    @Test @DisplayName("无归档只输出没有记录到开播，不调用绘图")
    void emptyWeek() {
        var r = WeeklyReport.calculate("主播示例", List.of(), snapshots(), NOW, RULES, true);
        var painted = new AtomicBoolean();
        var p = WeeklyReport.preview(r, () -> { painted.set(true); return new byte[]{1}; });
        assertFalse(painted.get(), "无归档不应出图");
        assertNull(p.image());
        assertEquals("主播示例 上周（10 月 5 日至 11 日）没有记录到开播。", p.summary());
        assertEquals(p.summary(), p.text());
    }
    @Test @DisplayName("画图抛错仍输出六项文字统计")
    void failedImage() {
        var p = WeeklyReport.preview(report(true), () -> { throw new IllegalStateException("image unavailable"); });
        assertNull(p.image());
        for (String label : List.of("直播时长", "流水", "弹幕", "观众", "互动观众", "粉丝净增"))
            assertTrue(p.text().contains(label + "："), label);
        assertTrue(p.text().contains("1,234.50 元"));

    }
    @Test @DisplayName("观众编号不进入周报数据摘要文字版")
    void audiencePrivacy() {
        for (boolean money : List.of(true, false)) {
            var r = report(money);
            String raw = JSON.toJSONString(r) + WeeklyReport.summary(r) + WeeklyReport.text(r);
            for (String token : List.of("9200000001", "9200000002", "9200000003", "9200000004", "userSets"))
                assertFalse(raw.contains(token), token);
        }
    }
    @Test @DisplayName("无统计口径的摘要只展示场次与时长")
    void noCatalogSummary() {
        for (boolean visible : List.of(true, false)) {
            var r = WeeklyReport.calculate("主播示例", sessions(), snapshots(), NOW, null, visible);
            assertEquals("主播示例 上周（10 月 5 日至 11 日）共直播 5 场、9 小时 10 分。", WeeklyReport.summary(r));
            assertFalse(r.metrics().containsKey("revenue"));
        }
    }
    @Test @DisplayName("无统计口径的日图数据使用同一统计服务的时长")
    void noCatalogDaily() {
        var direct = new StreamerInsights().calculate(sessions(), snapshots(), FROM, TO, null);
        for (boolean visible : List.of(true, false)) {
            var r = WeeklyReport.calculate("主播示例", sessions(), snapshots(), NOW, null, visible);
            assertEquals("durationSeconds", r.chartMetric());
            for (int i = 0; i < 7; i++) {
                assertEquals(Map.of("durationSeconds", direct.daily().get(i).durationSeconds()), r.daily().get(i).metrics());
            }
        }
    }
    @Test @DisplayName("未知数写暂无数据，已记录的零保留为零")
    void unavailableAndZero() {
        var r = WeeklyReport.calculate("主播示例", List.of(session(FROM, List.of())), List.of(), NOW, null, true);
        assertEquals("1 场", WeeklyReport.cards(r).get(1).value());
        assertEquals("暂无数据", WeeklyReport.cards(r).get(2).value());
        assertEquals("首次记录与回访：暂无数据", WeeklyReport.composition(r));
        r = WeeklyReport.calculate("主播示例", List.of(session(FROM, List.of())), List.of(), NOW, RULES, true);
        assertEquals("0 人", WeeklyReport.cards(r).get(3).value());
    }
    @Test @DisplayName("无统计口径的文字版保留六格并解释未知项")
    void noCatalogText() {
        var text = WeeklyReport.text(WeeklyReport.calculate("主播示例", sessions(), snapshots(), NOW, null, true));
        assertTrue(text.contains("直播时长："));
        assertTrue(text.contains("场次："));
        for (String label : List.of("弹幕：暂无数据", "观众：暂无数据", "粉丝净增：暂无数据", "较上周 —", "本周场次"))
            assertTrue(text.contains(label), label);
    }
    @Test @DisplayName("名单说明与主播页使用相同叫法")
    void coverageNames() {
        var d = report(false);
        var data = new WeeklyReport.Data(d.streamerName(), d.firstDay(), d.lastDay(), d.metrics(), d.fans(), d.composition(),
                new StreamerInsights.Coverage(1, 2, 3, 4), d.daily(), d.chartMetric());
        var text = WeeklyReport.coverage(data);
        assertTrue(text.contains("含无法识别的观众 2 场"));
        assertTrue(text.contains("完整性未知 3 场"));
        assertTrue(text.contains("未记录此项"));
    }

    @Test @DisplayName("首次记录与回访人数在图片用语和文字版中都有千分位")
    void compositionThousands() {
        var d = report(false);
        var data = new WeeklyReport.Data(d.streamerName(), d.firstDay(), d.lastDay(), d.metrics(), d.fans(),
                new StreamerInsights.Composition(12345, 4228), d.coverage(), d.daily(), d.chartMetric());
        String expected = "本周首次记录 12,345 人 · 回访 4,228 人";
        assertEquals(expected, WeeklyReport.composition(data));
        assertTrue(WeeklyReport.text(data).contains(expected));
    }

}
