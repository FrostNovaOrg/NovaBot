package org.frostnova.nova.core.service;

import org.frostnova.nova.core.analytics.StreamerCalendar;
import org.frostnova.nova.core.model.LiveGap;
import org.frostnova.nova.core.model.LiveSession;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 控制台和周报共用的日历证据拼装，包含尚未归档的本场。 */
public final class StreamerCalendarService {
    private final LiveDataService live;

    public StreamerCalendarService(LiveDataService live) { this.live = live; }

    public StreamerCalendar.Result calculate(String platform, Long uid, boolean enabled, List<LiveSession> archived,
                                             List<LiveGap> watched, LocalDate lastDay, int days, Clock clock) {
        var sessions = new ArrayList<>(archived);
        long now = clock.millis();
        boolean living = enabled && live.getLiveStatus(platform, uid).orElse(false);
        var ongoingStart = living ? live.getLiveStartTime(platform, uid)
                .filter(start -> start > 0 && start < now)
                .filter(start -> sessions.stream().noneMatch(session -> session.startTime() == start))
                : Optional.<Long>empty();
        long uncertainFrom = sessions.stream().mapToLong(LiveSession::endTime).max().orElse(0);
        ongoingStart.ifPresent(start -> sessions.add(new LiveSession(platform, uid, null, null, start, now,
                (now - start) / 1000, Map.of(), Map.of())));
        var calendar = new StreamerCalendar().calculate(sessions, watched, live.downtimeIntervals(0, now),
                live.roomOutageIntervals(platform, uid, 0, now), lastDay, days, clock);
        if (living && ongoingStart.isEmpty()) {
            // 在播却无法定位本场时，最近归档之后缺少确认没播的依据。已归档的已播证据照留。
            calendar = new StreamerCalendar.Result(calendar.provableFrom(), calendar.items().stream()
                    .map(day -> day.status().equals("off") && day.end() > uncertainFrom && day.start() < now
                            ? new StreamerCalendar.Day(day.date(), day.weekday(), day.start(), day.end(),
                                    day.current(), "unknown", day.durationSeconds(), day.effectiveSeconds(), day.gapSeconds(), day.starts())
                            : day).toList());
        }
        return calendar;
    }
}
