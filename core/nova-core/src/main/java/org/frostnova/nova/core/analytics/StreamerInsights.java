package org.frostnova.nova.core.analytics;

import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.model.StreamerSnapshot;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                      Long messages, Integer audience, Long follows, List<Long> starts) {}
    public record Coverage(int complete, int anonymous, int unknown, int missing) {}
    public record Composition(int firstRecorded, int returning) {}
    public record Fans(double change, long fromAt, double fromValue, long toAt,
                       double toValue, boolean partial) {}
    public record Result(long from, long to, boolean metricsKnown, int sessions, long durationSeconds,
                         Double revenue, Long messages, Integer audience, Integer interactionAudience,
                         Composition composition, Long firstRecordedAt, Coverage coverage,
                         Fans fans, List<Day> daily, Long follows) {}

    /** changeRatio 为变化比例，例如 0.12 表示上涨 12%；不能比较时仅给原因。 */
    public record Comparison(Double changeRatio, String reasonCode, String reason) {}
    public record PeriodComparison(Result current, Result previous, Map<String, Comparison> comparisons,
                                   String comparisonNote) {}

    /** 上期整体前移指定天数，保留尚未结束的统计日进度。 */
    public PeriodComparison compareRecent(Collection<LiveSession> sessions, Collection<StreamerSnapshot> snapshots,
                                          int days, Clock clock, LiveMetricCatalog.Insights rules) {
        Range range = recent(days, clock);
        Result current = calculate(sessions, snapshots, range.from(), range.to(), rules);
        Result previous = calculate(sessions, snapshots, range.from().minus(days, ChronoUnit.DAYS),
                range.to().minus(days, ChronoUnit.DAYS), rules);
        Map<String, Comparison> comparisons = new LinkedHashMap<>();
        comparisons.put("durationSeconds", compare(current, previous, current.durationSeconds(),
                previous.durationSeconds(), false, false));
        comparisons.put("revenue", compare(current, previous, current.revenue(), previous.revenue(), true, false));
        comparisons.put("messages", compare(current, previous, current.messages(), previous.messages(), true, false));
        comparisons.put("audience", compare(current, previous, current.audience(), previous.audience(), true, true));
        comparisons.put("interactionAudience", compare(current, previous, current.interactionAudience(),
                previous.interactionAudience(), true, true));
        String note = current.coverage().unknown() + previous.coverage().unknown() > 0
                ? "6.0 之前开播的场次名单完整性未知，观众数对比仅供参考" : null;
        return new PeriodComparison(current, previous, Map.copyOf(comparisons), note);
    }

    private static Comparison compare(Result current, Result previous, Number value, Number baseline,
                                      boolean platformMetric, boolean audienceMetric) {
        if (previous.sessions() == 0) return unavailable("NO_PREVIOUS_SESSIONS", "上期没有开播");
        if (current.sessions() < 2 || previous.sessions() < 2)
            return unavailable("TOO_FEW_SESSIONS", "本期或上期少于 2 场");
        if (platformMetric && !current.metricsKnown()) return unavailable("UNSUPPORTED_METRIC", "平台没有这项口径");
        if (baseline != null && baseline.doubleValue() == 0) return unavailable("PREVIOUS_ZERO", "上期为 0");
        if (audienceMetric && current.coverage().missing() + previous.coverage().missing() > 0)
            return unavailable("MISSING_AUDIENCE_LIST", "有场次没有观众名单");
        return new Comparison((value.doubleValue() - baseline.doubleValue()) / baseline.doubleValue(), null, null);
    }

    private static Comparison unavailable(String code, String reason) {
        return new Comparison(null, code, reason);
    }

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
                    rules == null ? null : a.messages, a.audience(rules), a.follows(rules), List.copyOf(a.starts)));
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
                total.unknown, total.missing), fans(snapshots, start, end, rules), List.copyOf(days), total.follows(rules));
    }
    public record TrendBucket(String label, long start, long end, int sessions, long durationSeconds,
                              Double revenue, Long messages, Integer audience, Long follows, List<Long> starts) {}

    /** 概况口径按统计周期归桶，页面范围外的场次不参与；保留完整周期边界供下钻。 */
    public List<TrendBucket> trend(Collection<LiveSession> sessions, Instant from, Instant to,
                                   LiveSessionAnalytics.Period period, LiveMetricCatalog.Insights rules) {
        var grouped = new LinkedHashMap<Long, Accumulator>();
        for (var session : sessions) {
            if (session.startTime() < from.toEpochMilli() || session.startTime() >= to.toEpochMilli()) continue;
            long start = StatisticsDay.startOf(period.startOf(StatisticsDay.dateOf(
                    Instant.ofEpochMilli(session.startTime())))).toEpochMilli();
            grouped.computeIfAbsent(start, ignored -> new Accumulator()).add(session, rules);
        }
        return new LiveSessionAnalytics().aggregate(sessions, period, Set.of(), from, to).stream().map(bucket -> {
            var a = grouped.getOrDefault(bucket.start(), new Accumulator());
            return new TrendBucket(bucket.label(), bucket.start(), bucket.end(), a.sessions, a.duration,
                    rules == null ? null : a.revenue, rules == null ? null : a.messages, a.audience(rules),
                    a.follows(rules), List.copyOf(a.starts));
        }).toList();
    }

    private static void addUsers(Set<Long> into, LiveSession session, List<String> keys) {
        for (String key : keys) into.addAll(session.userSet(key));
    }

    public static Fans fans(Collection<StreamerSnapshot> snapshots, long from, long to,
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
        long duration, messages, follows;
        double revenue;
        final List<Long> starts = new ArrayList<>();
        final Set<Long> audience = new HashSet<>();
        final Set<Long> interaction = new HashSet<>();

        void add(LiveSession session, LiveMetricCatalog.Insights rules) {
            sessions++;
            starts.add(session.startTime());
            duration += session.durationSeconds();
            if (rules != null) {
                revenue += rules.revenue(session.metrics());
                if (rules.messageKey() != null) messages += (long) session.metric(rules.messageKey());
                if (rules.followsKey() != null) follows += (long) session.metric(rules.followsKey());
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
        Long follows(LiveMetricCatalog.Insights rules) {
            return rules == null || rules.followsKey() == null ? null : follows;
        }
        Integer audience(LiveMetricCatalog.Insights rules) {
            return rules == null || listed == 0 ? null : audience.size();
        }
    }
}
