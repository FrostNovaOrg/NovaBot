package org.frostnova.nova.core.analytics;

import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.model.StreamerSnapshot;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/** 指定主播在任意半开时间区间内的归档概况；名单仅用于计数。 */
public final class StreamerInsights {
    public record Range(Instant from, Instant to) {}
    public static Range recent(int days, Clock clock) {
        Instant now = clock.instant();
        return new Range(StatisticsDay.startOf(StatisticsDay.today(Clock.fixed(now, clock.getZone()))
                .minusDays(days - 1)), now);
    }
    public record Day(String date, int sessions, long durationSeconds, Double revenue,
                      Long messages, Integer audience) {}
    public record Coverage(int complete, int anonymous, int unknown, int missing) {}
    public record Composition(int firstRecorded, int returning) {}
    public record Fans(double change, long fromAt, double fromValue, long toAt,
                       double toValue, boolean partial) {}
    public record Result(long from, long to, boolean metricsKnown, int sessions, long durationSeconds,
                         Double revenue, Long messages, Integer audience, Integer interactionAudience,
                         Composition composition, Long firstRecordedAt, Coverage coverage,
                         Fans fans, List<Day> daily) {}

    /** 输入场次与快照须属于同一位主播；区间含起点、不含终点。 */
    public Result calculate(Collection<LiveSession> sessions, Collection<StreamerSnapshot> snapshots,
                            Instant from, Instant to, LiveMetricCatalog.Insights rules) {
        if (!from.isBefore(to)) throw new IllegalArgumentException("统计起点必须早于终点");
        long start = from.toEpochMilli();
        long end = to.toEpochMilli();
        Accumulator total = new Accumulator();
        var daily = new TreeMap<java.time.LocalDate, Accumulator>();
        Set<Long> previous = new HashSet<>();
        boolean historyKnown = false;
        Long firstRecordedAt = null;
        for (LiveSession session : sessions) {
            if (session.startTime() >= end) continue;
            if (session.hasUserSets()) {
                if (firstRecordedAt == null || session.startTime() < firstRecordedAt) {
                    firstRecordedAt = session.startTime();
                }
                if (session.startTime() < start) {
                    historyKnown = true;
                    if (rules != null) addUsers(previous, session, rules.audienceSets());
                }
            }
            if (session.startTime() < start) continue;
            total.add(session, rules);
            daily.computeIfAbsent(StatisticsDay.dateOf(Instant.ofEpochMilli(session.startTime())),
                    ignored -> new Accumulator()).add(session, rules);
        }
        List<Day> days = new ArrayList<>();
        var last = StatisticsDay.dateOf(to.minusMillis(1));
        for (var day = StatisticsDay.dateOf(from); !day.isAfter(last); day = day.plusDays(1)) {
            var a = daily.getOrDefault(day, new Accumulator());
            days.add(new Day(day.toString(), a.sessions, a.duration, rules == null ? null : a.revenue,
                    rules == null ? null : a.messages, a.audience(rules)));
        }
        Composition composition = null;
        if (rules != null && historyKnown && total.listed > 0) {
            int returning = (int) total.audience.stream().filter(previous::contains).count();
            composition = new Composition(total.audience.size() - returning, returning);
        }
        return new Result(start, end, rules != null, total.sessions, total.duration,
                rules == null ? null : total.revenue, rules == null ? null : total.messages,
                total.audience(rules), rules == null || total.listed == 0 ? null : total.interaction.size(),
                composition, firstRecordedAt, new Coverage(total.complete, total.anonymous,
                total.unknown, total.missing), fans(snapshots, start, end, rules), List.copyOf(days));
    }
    private static void addUsers(Set<Long> into, LiveSession session, List<String> keys) {
        for (String key : keys) into.addAll(session.userSet(key));
    }

    private static Fans fans(Collection<StreamerSnapshot> snapshots, long from, long to,
                             LiveMetricCatalog.Insights rules) {
        if (rules == null || rules.fansKey() == null) return null;
        var usable = snapshots.stream().filter(s -> s.at() < to && s.has(rules.fansKey()))
                .sorted(Comparator.comparingLong(StreamerSnapshot::at)).toList();
        if (usable.size() < 2) return null;
        StreamerSnapshot baseline = null;
        for (var snapshot : usable) {
            if (snapshot.at() <= from) baseline = snapshot;
        }
        boolean partial = baseline == null;
        if (partial) baseline = usable.get(0);
        var latest = usable.get(usable.size() - 1);
        if (baseline.at() == latest.at()) return null;
        double before = baseline.metric(rules.fansKey());
        double after = latest.metric(rules.fansKey());
        return new Fans(after - before, baseline.at(), before, latest.at(), after, partial);
    }

    private static final class Accumulator {
        int sessions, listed, complete, anonymous, unknown, missing;
        long duration, messages;
        double revenue;
        final Set<Long> audience = new HashSet<>();
        final Set<Long> interaction = new HashSet<>();

        void add(LiveSession session, LiveMetricCatalog.Insights rules) {
            sessions++;
            duration += session.durationSeconds();
            if (rules != null) {
                for (String key : rules.revenueKeys()) revenue += session.metric(key);
                messages += (long) session.metric(rules.messageKey());
            }
            if (!session.hasUserSets()) {
                missing++;
                return;
            }
            listed++;
            if (session.anonymousCounts() == null) unknown++;
            else if (session.anonymousCounts().values().stream().anyMatch(v -> v > 0)) anonymous++;
            else complete++;
            if (rules != null) {
                addUsers(audience, session, rules.audienceSets());
                addUsers(interaction, session, rules.interactionSets());
            }
        }
        Integer audience(LiveMetricCatalog.Insights rules) {
            return rules == null || listed == 0 ? null : audience.size();
        }
    }
}
