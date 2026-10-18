package org.frostnova.nova.core.analytics;

import org.frostnova.nova.core.model.LiveGap;
import org.frostnova.nova.core.model.LiveSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StreamerCalendarRangeTest {
    @Test @DisplayName("指定日历终点时，只把包含生成时刻的统计日标为当前，未来不推断没播")
    void currentDayAndEvidenceAreBoundedByNow() {
        var today = LocalDate.parse("2026-10-14");
        var at = StatisticsDay.startOf(today).plusSeconds(3600);
        var clock = Clock.fixed(at, StatisticsDay.ZONE);
        var start = StatisticsDay.startOf(today.minusDays(2));
        var end = StatisticsDay.startOf(today.plusDays(3));
        var watched = List.of(new LiveGap(start.toEpochMilli(), end.toEpochMilli(), LiveGap.Reason.UNKNOWN));
        var sessions = List.of(new LiveSession("sample", 1L, "示例", 2L,
                StatisticsDay.startOf(today).toEpochMilli(), end.toEpochMilli(), 3 * 86400, Map.of(), Map.of()));
        var result = new StreamerCalendar().calculate(sessions, watched, List.of(), List.of(), today.plusDays(1), 4, clock);
        assertEquals(List.of(false, false, true, false), result.items().stream().map(StreamerCalendar.Day::current).toList());
        assertEquals(List.of("off", "off", "recorded", "unknown"), result.items().stream().map(StreamerCalendar.Day::status).toList());
        assertEquals(3600, result.items().get(2).durationSeconds(), "未来的场次部分不能提前计入");
        assertEquals(0, result.items().get(3).durationSeconds());
        assertEquals(new StreamerCalendar().calculate(sessions, watched, List.of(), List.of(), 3, clock),
                new StreamerCalendar().calculate(sessions, watched, List.of(), List.of(), today, 3, clock));
    }

    @Test @DisplayName("06点边界只属于新统计日，历史终点不被标成当前日")
    void statisticsBoundary() {
        var sunday = LocalDate.parse("2026-10-11");
        var at = StatisticsDay.startOf(sunday.plusDays(1));
        var result = new StreamerCalendar().calculate(List.of(), List.of(), List.of(), List.of(), sunday, 35,
                Clock.fixed(at, StatisticsDay.ZONE));
        assertEquals(35, result.items().size());
        assertTrue(result.items().stream().noneMatch(StreamerCalendar.Day::current));
        assertEquals(at.toEpochMilli(), result.items().get(34).end());
    }
}
