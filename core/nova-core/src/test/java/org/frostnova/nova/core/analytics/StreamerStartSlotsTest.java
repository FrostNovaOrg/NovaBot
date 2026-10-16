package org.frostnova.nova.core.analytics;

import org.frostnova.nova.core.enums.LiveEndReason;
import org.frostnova.nova.core.model.LiveSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("开播时段表现")
class StreamerStartSlotsTest {
    static final LiveMetricCatalog.Insights RULES = new LiveMetricCatalog.Insights(
            List.of("gift", "paid"), "chat", List.of(), List.of(), null);
    static final Instant FROM = Instant.parse("2026-04-01T00:00:00Z");
    static final Instant TO = Instant.parse("2026-10-21T00:00:00Z");

    LiveSession session(String local, long duration, double revenue, long gap, long outage) {
        long start = LocalDateTime.parse(local).atZone(StatisticsDay.ZONE).toInstant().toEpochMilli();
        return new LiveSession("sample", 1L, "sample", 2L, start, start + duration * 1000,
                duration, Map.of("gift", revenue / 2, "paid", revenue / 2, "chat", revenue * 2),
                Map.of(), LiveEndReason.NORMAL, List.of(), gap, Map.of(), outage);
    }
    StreamerStartSlots.Result calculate(List<LiveSession> sessions) {
        return new StreamerStartSlots().calculate(sessions, FROM, TO, RULES);
    }
    StreamerStartSlots.Cell cell(StreamerStartSlots.Result r, int weekday, int hour) {
        return r.cells().stream().filter(c -> c.weekday() == weekday && c.startHour() == hour).findFirst().orElseThrow();
    }
    List<LiveSession> group(String local, int count, double amount) {
        var items = new ArrayList<LiveSession>();
        for (int i = 0; i < count; i++) {
            var start = LocalDateTime.parse(local).plusWeeks(i);
            items.add(session(start.toString(), 3600, amount, 0, 0));
        }
        return items;
    }

    @Test @DisplayName("深夜场次按东八区实际星期和钟点归格")
    void localClock() {
        var original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            var r = calculate(List.of(session("2026-10-19T23:30", 3600, 10, 0, 0),
                    session("2026-10-18T01:00", 3600, 10, 0, 0)));
            assertEquals(84, r.cells().size(), "必须保留全部84格");
            assertEquals(1, cell(r, 1, 22).sessions(), "周一23:30应在周一22–24时");
            assertEquals(1, cell(r, 7, 0).sessions(), "周日01:00应在周日0–2时");
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            assertEquals(r, calculate(List.of(session("2026-10-19T23:30", 3600, 10, 0, 0),
                    session("2026-10-18T01:00", 3600, 10, 0, 0))), "JVM时区不得影响格子");
        } finally { TimeZone.setDefault(original); }
    }

    @Test @DisplayName("少于三场不给值也不进前三")
    void insufficient() {
        var r = calculate(group("2026-09-07T10:00", 2, 100));
        assertNull(cell(r, 1, 10).revenuePerHour(), "两场不得显示低效数值");
        assertNull(cell(r, 1, 10).messagesPerHour(), "两场弹幕也不得显示数值");
        assertTrue(r.topRevenue().isEmpty(), "样本不足不得进入前三");
        assertTrue(r.topMessages().isEmpty());
    }

    @Test @DisplayName("前三按值场数星期钟点排序且不补位")
    void ranking() {
        var items = new ArrayList<>(group("2026-09-07T10:00", 3, 50));
        items.addAll(group("2026-09-08T10:00", 4, 50));
        var r = calculate(items);
        assertEquals(List.of(2, 1), r.topRevenue().stream().map(StreamerStartSlots.Top::weekday).toList(), "同值优先场数多且不补位");
        items.addAll(group("2026-09-09T10:00", 3, 100));
        items.addAll(group("2026-09-07T08:00", 3, 50));
        items.addAll(group("2026-09-10T10:00", 2, 999));
        r = calculate(items);
        assertEquals(List.of(3, 2, 1), r.topRevenue().stream().map(StreamerStartSlots.Top::weekday).toList(), "值高优先且样本不足不补位");
        assertEquals(8, r.topRevenue().get(2).startHour(), "同值同场数按钟点先后");
        assertEquals(r.topRevenue(), r.topMessages().stream().map(t -> new StreamerStartSlots.Top(t.weekday(), t.startHour(), t.sessions(), t.value() / 2)).toList());
    }

    @Test @DisplayName("每小时总量按合计时长加权而非每场平均")
    void weightedRate() {
        var items = new ArrayList<>(List.of(session("2026-09-07T10:00", 3600, 100, 0, 0),
                session("2026-09-14T10:00", 10800, 100, 0, 0)));
        var c = cell(calculate(items), 1, 10);
        assertEquals(50d, c.revenue() * 3600 / c.effectiveSeconds(), "两场总量200除以4小时应为50");
        assertNull(c.revenuePerHour(), "两场仍须遵守样本门槛");
        items.add(session("2026-09-21T10:00", 0, 0, 0, 0));
        c = cell(calculate(items), 1, 10);
        assertEquals(50d, c.revenuePerHour(), "应为50元/时而非场均流水");
        assertEquals(100d, c.messagesPerHour());
        assertEquals(3, c.sessionItems().size());
        assertEquals(100d, c.sessionItems().get(1).revenue());
    }

    @Test @DisplayName("有效时长扣除两种缺口的大者并截到零")
    void effectiveDuration() {
        var items = group("2026-09-07T10:00", 2, 100);
        items.add(session("2026-09-21T10:00", 7200, 100, 1800, 1800));
        var c = cell(calculate(items), 1, 10);
        assertEquals(12600, c.effectiveSeconds(), "缺口与断线重叠时只扣大者");
        assertEquals(300d * 3600 / 12600, c.revenuePerHour());
        c = cell(calculate(Collections.nCopies(3, session("2026-09-07T10:00", 60, 0, 100, 100))), 1, 10);
        assertEquals(0, c.effectiveSeconds(), "有效时长不得为负");
        assertNull(c.revenuePerHour(), "零有效时长不得显示零效率");
    }

    // 抓住全重叠缺口被扣两遍、有效时长归零而丢失每小时读数的情况。
    @Test @DisplayName("两种缺口全重叠时有效时长仍保留且热图与分区表一致")
    void overlappingGaps() {
        var items = Collections.nCopies(3, session("2026-09-07T10:00", 3600, 100, 1800, 1800));
        var c = cell(calculate(items), 1, 10);
        assertEquals(5400, c.effectiveSeconds(), "全重叠的缺口不得扣两遍");
        assertEquals(200d, c.revenuePerHour());
        var area = new StreamerStartAreas().calculate(items, FROM, TO, RULES).get(0);
        assertEquals(c.effectiveSeconds(), area.effectiveSeconds());
        assertEquals(c.revenuePerHour(), area.revenuePerHour());
    }

    @Test @DisplayName("九十和一百八十天分别按统计日筛选且排除终点")
    void ranges() {
        var clock = Clock.fixed(TO, ZoneOffset.UTC);
        var shortRange = StreamerInsights.recent(90, clock);
        var longRange = StreamerInsights.recent(180, clock);
        var items = List.of(session("2026-06-01T10:00", 3600, 100, 0, 0));
        var shortResult = new StreamerStartSlots().calculate(items, shortRange.from(), shortRange.to(), RULES);
        var longResult = new StreamerStartSlots().calculate(items, longRange.from(), longRange.to(), RULES);
        assertEquals(0, shortResult.cells().stream().mapToInt(StreamerStartSlots.Cell::sessions).sum(), "90天不含六月场次");
        assertEquals(1, longResult.cells().stream().mapToInt(StreamerStartSlots.Cell::sessions).sum(), "180天应包含六月场次");
        var boundary = LocalDateTime.ofInstant(shortRange.from(), StatisticsDay.ZONE);
        var outside = LocalDateTime.ofInstant(shortRange.to(), StatisticsDay.ZONE);
        var r = new StreamerStartSlots().calculate(List.of(session(boundary.toString(), 1, 0, 0, 0),
                session(boundary.minusSeconds(1).toString(), 1, 0, 0, 0), session(outside.toString(), 1, 0, 0, 0)),
                shortRange.from(), shortRange.to(), RULES);
        assertEquals(1, r.cells().stream().mapToInt(StreamerStartSlots.Cell::sessions).sum(), "统计日范围含起点不含终点");
    }

    @Test @DisplayName("缺少单项口径与零值分开显示")
    void unknownMetrics() {
        var items = group("2026-09-07T10:00", 3, 0);
        assertEquals(0d, cell(calculate(items), 1, 10).revenuePerHour(), "已知零值仍是数据");
        var r = new StreamerStartSlots().calculate(items, FROM, TO, null);
        assertNull(cell(r, 1, 10).revenue(), "无口径不得冒充零流水");
        assertTrue(r.topRevenue().isEmpty());
        var partial = new LiveMetricCatalog.Insights(List.of(), "chat", List.of(), List.of(), null);
        r = new StreamerStartSlots().calculate(items, FROM, TO, partial);
        assertNull(cell(r, 1, 10).revenuePerHour(), "缺少流水口径应无数据");
        assertEquals(0d, cell(r, 1, 10).messagesPerHour());
        partial = new LiveMetricCatalog.Insights(List.of("gift"), null, List.of(), List.of(), null);
        r = new StreamerStartSlots().calculate(items, FROM, TO, partial);
        assertNull(cell(r, 1, 10).messagesPerHour(), "缺少弹幕口径应无数据");
    }
}
