package org.frostnova.nova.core.analytics;

import org.frostnova.nova.core.model.LiveGap;
import org.frostnova.nova.core.model.LiveSession;
import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class StreamerCalendarTest {
    final Clock clock = Clock.fixed(Instant.parse("2026-10-16T04:00:00Z"), ZoneOffset.UTC);
    final long today = StatisticsDay.startOf(LocalDate.parse("2026-10-16")).toEpochMilli();
    final long day = 86400000L;
    LiveGap interval(long from, long to) { return new LiveGap(from,to,LiveGap.Reason.UNKNOWN); }
    List<LiveGap> watched() { return List.of(interval(today-30*day,clock.millis())); }
    StreamerCalendar.Result calendar(List<LiveSession> sessions, List<LiveGap> watched, List<LiveGap> down, List<LiveGap> room) {
        return new StreamerCalendar().calculate(sessions,watched,down,room,7,clock);
    }
    LiveSession session(long from,long to) {
        return new LiveSession("sample",1L,"sample",2L,from,to,(to-from)/1000,Map.of(),Map.of());
    }
    @Test @DisplayName("恢复不补认开播时三秒停机也不能确认没播")
    void anyDowntimeIsUnknown() {
        for (long seconds : List.of(3L,660L)) {
            var result = calendar(List.of(),watched(),List.of(interval(today-30*day,today-30*day+1), interval(today+1000,today+1000+seconds*1000)),List.of());
            assertEquals("unknown",result.items().get(6).status(),"停机秒数="+seconds);
        }
    }
    @Test @DisplayName("跨06点场次在两个统计日都有一小时且缺口去重")
    void crossesStatisticsDay() {
        var session = session(today-3600000,today+3600000);
        var result = calendar(List.of(session),watched(),List.of(),List.of());
        for (var item : result.items().subList(5,7)) {
            assertEquals("recorded",item.status()); assertEquals(3600,item.effectiveSeconds());
            assertEquals(List.of(session.startTime()),item.starts());
        }
        var gaps = calendar(List.of(session),watched(),List.of(interval(today,today+3000)),List.of(interval(today+1000,today+4000)));
        assertEquals(4,gaps.items().get(6).gapSeconds(),"重叠缺口只计一次");
        assertEquals(7200,gaps.items().stream().mapToLong(StreamerCalendar.Day::effectiveSeconds).sum());
    }
    @Test @DisplayName("今天只检查截至此刻的监看且有场次优先记为有直播")
    void currentDay() {
        var current = calendar(List.of(),watched(),List.of(),List.of()).items().get(6);
        assertEquals("off",current.status()); assertTrue(current.current());
        assertEquals(today+day,current.end()); assertEquals(5,current.weekday());
        assertEquals("recorded",calendar(List.of(session(today+1000,today+2000)),List.of(),List.of(),List.of()).items().get(6).status());
    }
    @Test @DisplayName("最早现存停机或本房断线之前的日期不能确认没播")
    void retainedBoundary() {
        for (boolean room : List.of(false,true)) {
            var rows = List.of(interval(today-day,today-day+3000));
            var result = calendar(List.of(),watched(),room?List.of():rows,room?rows:List.of());
            assertEquals(today-day,result.provableFrom());
            assertEquals("unknown",result.items().get(4).status());
        }
    }
    @Test @DisplayName("加入和实际开始监看以前以及空记录均为未知")
    void beforeMonitoring() {
        var result = calendar(List.of(),List.of(interval(today,clock.millis())),List.of(),List.of());
        assertEquals("unknown",result.items().get(5).status()); assertEquals("off",result.items().get(6).status());
        var absent = calendar(List.of(),List.of(),List.of(),List.of());
        assertNull(absent.provableFrom()); assertTrue(absent.items().stream().allMatch(d -> d.status().equals("unknown")));
    }
    @Test @DisplayName("未闭合断线和中途移出监看名单不能确认没播")
    void missingWatchSegment() {
        var result = calendar(List.of(),List.of(interval(today-30*day,today+1000)),List.of(),List.of());
        assertEquals("unknown",result.items().get(6).status());
    }
}
