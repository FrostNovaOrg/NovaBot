package org.frostnova.nova.core.analytics;

import org.frostnova.nova.core.enums.LiveEndReason;
import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.model.RoomInfoSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("直播分区对比")
class StreamerStartAreasTest {
    static final LiveMetricCatalog.Insights RULES = new LiveMetricCatalog.Insights(
            List.of("gift", "paid"), "chat", List.of(), List.of(), null);
    static final Instant FROM = Instant.parse("2026-04-01T00:00:00Z");
    static final Instant TO = Instant.parse("2026-10-21T00:00:00Z");

    /** areaTrail 是本场的分区轨迹，首条为开播时的分区；一个不给就是没有轨迹。 */
    LiveSession session(String local, long seconds, Map<String, Double> metrics, String... areaTrail) {
        return session(local, seconds, 0, 0, metrics, areaTrail);
    }

    LiveSession session(String local, long seconds, long gap, long outage,
                        Map<String, Double> metrics, String... areaTrail) {
        long start = LocalDateTime.parse(local).atZone(StatisticsDay.ZONE).toInstant().toEpochMilli();
        var titles = new ArrayList<RoomInfoSnapshot>();
        for (int i = 0; i < areaTrail.length; i++) {
            titles.add(new RoomInfoSnapshot(start + i * 60_000L, "标题" + i, areaTrail[i]));
        }
        return new LiveSession("sample", 1L, "sample", 2L, start, start + seconds * 1000,
                seconds, metrics, Map.of(), LiveEndReason.NORMAL, List.copyOf(titles), gap, Map.of(), outage);
    }

    List<StreamerStartAreas.Area> calculate(List<LiveSession> sessions) {
        return new StreamerStartAreas().calculate(sessions, FROM, TO, RULES);
    }

    StreamerStartAreas.Area area(List<StreamerStartAreas.Area> rows, String name) {
        return rows.stream().filter(row -> name.equals(row.area())).findFirst().orElseThrow();
    }

    Set<Long> startSet(StreamerStartAreas.Area row) {
        return Set.copyOf(row.starts());
    }

    long at(String local) {
        return LocalDateTime.parse(local).atZone(StatisticsDay.ZONE).toInstant().toEpochMilli();
    }

    @Test @DisplayName("整场归开播分区，中途换区不拆分，没轨迹的归未记录分区")
    void openingArea() {
        var rows = calculate(List.of(
                session("2026-10-05T10:00", 3600, Map.of("gift", 50d), "娱乐 · 视频聊天", "音乐 · 翻唱"),
                session("2026-10-06T10:00", 3600, Map.of("gift", 30d), "娱乐 · 视频聊天"),
                session("2026-10-07T10:00", 3600, Map.of("gift", 20d)),
                session("2026-10-08T10:00", 3600, Map.of("gift", 10d), "", "音乐 · 翻唱")));
        assertEquals(2, rows.size(), "只该有开播分区与未记录分区两行");
        var opened = area(rows, "娱乐 · 视频聊天");
        assertEquals(2, opened.sessions(), "中途换区那场整场算开播分区");
        assertEquals(Set.of(at("2026-10-05T10:00"), at("2026-10-06T10:00")), startSet(opened),
                "换到别区的那场不得改归到新分区");
        assertEquals(80d, opened.revenue(), "整场流水跟开播分区走");
        var none = area(rows, "未记录分区");
        assertEquals(2, none.sessions(), "没有轨迹与首条分区为空的场次都要收进未记录分区");
        assertEquals(Set.of(at("2026-10-07T10:00"), at("2026-10-08T10:00")), startSet(none));
        assertEquals(30d, none.revenue());
        assertFalse(rows.stream().anyMatch(row -> "音乐 · 翻唱".equals(row.area())),
                "被换过去的分区不该单独成行");
    }

    @Test @DisplayName("全是没收入记录的场次时流水与每小时流水未知，不写零")
    void unknownRevenue() {
        var rows = calculate(List.of(
                session("2026-10-05T10:00", 3600, Map.of("chat", 100d), "音乐 · 翻唱"),
                session("2026-10-06T10:00", 3600, Map.of("chat", 80d), "音乐 · 翻唱")));
        assertEquals(1, rows.size());
        assertNull(rows.get(0).revenue(), "一个收入键都没记到的场次不得当零流水");
        assertNull(rows.get(0).revenuePerHour(), "流水未知时每小时流水也是未知");
        var zero = calculate(List.of(
                session("2026-10-05T10:00", 3600, Map.of("gift", 0d), "音乐 · 翻唱")));
        assertEquals(0d, zero.get(0).revenue(), "记到的零元仍是数据");
        assertEquals(0d, zero.get(0).revenuePerHour());
        var noHours = calculate(List.of(
                session("2026-10-05T10:00", 1800, 900, 900, Map.of("gift", 50d), "音乐 · 翻唱")));
        assertEquals(50d, area(noHours, "音乐 · 翻唱").revenue());
        assertNull(area(noHours, "音乐 · 翻唱").revenuePerHour(), "有效时长为零时不得给每小时流水");
    }

    @Test @DisplayName("与开播时段热图数的是同一组场次")
    void sameSessionsAsHeatmap() {
        var items = new ArrayList<>(List.of(
                session("2026-10-05T10:00", 3600, Map.of("gift", 10d), "娱乐 · 视频聊天"),
                session("2026-10-06T20:00", 3600, Map.of("gift", 20d), "音乐 · 翻唱"),
                session("2026-10-07T01:00", 3600, Map.of("gift", 30d))));
        items.add(session("2026-03-31T10:00", 3600, Map.of("gift", 40d), "娱乐 · 视频聊天"));
        items.add(session("2026-10-21T10:00", 3600, Map.of("gift", 50d), "娱乐 · 视频聊天"));
        var rows = calculate(items);
        var slots = new StreamerStartSlots().calculate(items, FROM, TO, RULES);
        assertEquals(slots.cells().stream().mapToInt(StreamerStartSlots.Cell::sessions).sum(),
                rows.stream().mapToInt(StreamerStartAreas.Area::sessions).sum(),
                "两张卡数的场次必须是同一组");
        assertEquals(3, rows.stream().mapToInt(StreamerStartAreas.Area::sessions).sum(),
                "区间含起点不含终点，只按开播时刻取场次");
    }

    @Test @DisplayName("每一行的 starts 正是这个分区那几场")
    void drillDownStarts() {
        var rows = calculate(List.of(
                session("2026-10-05T10:00", 3600, Map.of("gift", 10d), "娱乐 · 视频聊天", "音乐 · 翻唱"),
                session("2026-10-06T10:00", 3600, Map.of("gift", 20d), "音乐 · 翻唱"),
                session("2026-10-07T10:00", 3600, Map.of("gift", 30d), "娱乐 · 视频聊天"),
                session("2026-10-08T10:00", 3600, Map.of("gift", 40d))));
        assertEquals(Set.of(at("2026-10-05T10:00"), at("2026-10-07T10:00")),
                startSet(area(rows, "娱乐 · 视频聊天")), "下钻带的须是开播分区的那几场");
        assertEquals(Set.of(at("2026-10-06T10:00")), startSet(area(rows, "音乐 · 翻唱")));
        assertEquals(Set.of(at("2026-10-08T10:00")), startSet(area(rows, "未记录分区")));
        assertEquals(rows.stream().mapToInt(StreamerStartAreas.Area::sessions).sum(),
                rows.stream().mapToInt(row -> row.starts().size()).sum(),
                "starts 与场次同数，点进去就是这些场");
    }

    @Test @DisplayName("每小时流水排在前面，未知的沉底")
    void orderByRevenuePerHour() {
        var rows = calculate(List.of(
                session("2026-10-05T10:00", 3600, Map.of("gift", 10d), "低效"),
                session("2026-10-06T10:00", 3600, Map.of("gift", 100d), "高效"),
                session("2026-10-07T10:00", 3600, Map.of("chat", 5d), "无流水")));
        assertEquals(List.of("高效", "低效", "无流水"),
                rows.stream().map(StreamerStartAreas.Area::area).collect(Collectors.toList()),
                "照每小时流水降序，流水未知的排最后");
    }
}
