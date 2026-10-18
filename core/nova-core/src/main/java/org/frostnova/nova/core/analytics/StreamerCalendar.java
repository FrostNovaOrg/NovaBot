package org.frostnova.nova.core.analytics;

import org.frostnova.nova.core.model.LiveGap;
import org.frostnova.nova.core.model.LiveSession;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** 统计日开播日历。没有连续监看证据的空白不能推断为未开播。 */
public final class StreamerCalendar {
    public record Day(String date, int weekday, long start, long end, boolean current, String status,
                      long durationSeconds, long effectiveSeconds, long gapSeconds, List<Long> starts) {}
    public record Result(Long provableFrom, List<Day> items) {}

    public Result calculate(Collection<LiveSession> sessions, List<LiveGap> watched,
                            List<LiveGap> downtimes, List<LiveGap> outages, int days, Clock clock) {
        return calculate(sessions, watched, downtimes, outages, StatisticsDay.today(clock), days, clock);
    }

    /** 从指定的最后一个统计日往前取 days 天，包含最后一天；证据只使用到 clock 的时刻。 */
    public Result calculate(Collection<LiveSession> sessions, List<LiveGap> watched,
                            List<LiveGap> downtimes, List<LiveGap> outages, LocalDate lastDay, int days, Clock clock) {
        if (days < 1) throw new IllegalArgumentException("days must be positive");
        long now = clock.millis();
        Long proof = watched.stream().mapToLong(LiveGap::from).min().stream().boxed().findFirst().orElse(null);
        // 旧区间可能已被裁剪；不能把已删除的部分当成从未发生过中断。
        if (proof != null) {
            for (var table : List.of(downtimes, outages)) {
                if (!table.isEmpty()) proof = Math.max(proof, table.stream().mapToLong(LiveGap::from).min().orElseThrow());
            }
        }
        List<LiveGap> gaps = LiveGap.merge(List.of(downtimes, outages));
        var orderedWatched = watched.stream().sorted(java.util.Comparator.comparingLong(LiveGap::from)).toList();
        List<Day> items = new ArrayList<>();
        for (var date = lastDay.minusDays(days - 1L); !date.isAfter(lastDay); date = date.plusDays(1)) {
            long start = StatisticsDay.startOf(date).toEpochMilli();
            long end = StatisticsDay.startOf(date.plusDays(1)).toEpochMilli();
            long until = Math.min(end, now);
            var intersecting = sessions.stream().filter(s -> start < until && s.startTime() < until && s.endTime() > start).toList();
            long duration = 0;
            List<LiveGap> sessionParts = new ArrayList<>();
            for (var session : intersecting) {
                long from = Math.max(start, session.startTime());
                long to = Math.min(until, session.endTime());
                // 与概况共用归档时长；按时间位置分配，分日取整后仍保持整场总秒数。
                duration += allocated(session, to) - allocated(session, from);
                sessionParts.add(new LiveGap(from, to, LiveGap.Reason.UNKNOWN));
            }
            long gapMillis = 0;
            for (var part : LiveGap.merge(List.of(sessionParts))) {
                for (var gap : gaps) gapMillis += gap.overlap(part.from(), part.to()).map(LiveGap::durationMillis).orElse(0L);
            }
            boolean covered = proof != null && start >= proof && until >= start;
            long cursor = start;
            for (var segment : orderedWatched) {
                if (segment.to() <= cursor) continue;
                if (segment.from() > cursor) break;
                cursor = Math.max(cursor, segment.to());
                if (cursor >= until) break;
            }
            covered &= cursor >= until;
            // 连接恢复不保证识别期间开播，因此任何长度的中断都不能宽限。
            covered &= gaps.stream().noneMatch(g -> g.from() < until && g.to() > start);
            String status = !intersecting.isEmpty() ? "recorded" : covered ? "off" : "unknown";
            long gapSeconds = gapMillis / 1000;
            items.add(new Day(date.toString(), date.getDayOfWeek().getValue(), start, end, start <= now && now < end,
                    status, duration, Math.max(0, duration - gapSeconds), gapSeconds,
                    intersecting.stream().map(LiveSession::startTime).distinct().sorted().toList()));
        }
        return new Result(proof, List.copyOf(items));
    }

    private static long allocated(LiveSession session, long at) {
        if (session.endTime() <= session.startTime()) return 0;
        return java.math.BigInteger.valueOf(session.durationSeconds())
                .multiply(java.math.BigInteger.valueOf(at - session.startTime()))
                .divide(java.math.BigInteger.valueOf(session.endTime() - session.startTime())).longValue();
    }
}
