package org.frostnova.nova.core.analytics;

import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.service.LiveDetailArchive;

import java.time.Instant;
import java.util.*;

/** 开播后按五分钟段比较各场入场速率，缓存每场的派生值，不保留全量明细。 */
public final class StreamerEntryRhythm {
    private static final long MINUTE = 60_000;
    private static final int SEGMENT_MINUTES = 5;
    private static final int MAX_CACHED_SESSIONS = 2048;
    private final LiveDetailArchive archive;
    private final Map<Key, Cached> cache = new LinkedHashMap<>(64, .75f, true);

    public record Point(int minute, Double medianEntries, int entriesSamples,
                        Double medianOnline, int onlineSamples) {}
    public record Result(int sessions, int entrySessions, List<Point> points) {}
    private record Key(String platform, Long uid, long start) {}
    private record Segment(Double entries, Double online) {}
    private record Summary(boolean hasEntries, List<Segment> segments) {}
    private record Cached(LiveDetailArchive.FileStamp stamp, Summary summary) {}
    private static final Summary MISSING = new Summary(false, List.of());

    public StreamerEntryRhythm(LiveDetailArchive archive) { this.archive = archive; }

    /** 范围与热图一致：按开播时刻筛选，含起点，不含终点。 */
    public Result calculate(Collection<LiveSession> sessions, Instant from, Instant to) {
        if (!from.isBefore(to)) throw new IllegalArgumentException("统计起点必须早于终点");
        var summaries = new ArrayList<Summary>();
        int total = 0;
        for (var session : sessions) {
            if (session.startTime() < from.toEpochMilli() || session.startTime() >= to.toEpochMilli()) continue;
            total++;
            var summary = summary(new Key(session.platform(), session.uid(), session.startTime()));
            if (summary.hasEntries()) summaries.add(summary);
        }
        var points = new ArrayList<Point>();
        for (int index = 0; ; index++) {
            var entries = new ArrayList<Double>();
            var online = new ArrayList<Double>();
            for (var summary : summaries) {
                if (index >= summary.segments().size()) continue;
                var segment = summary.segments().get(index);
                if (segment.entries() != null) entries.add(segment.entries());
                if (segment.online() != null) online.add(segment.online());
            }
            if (entries.size() < StreamerStartSlots.MIN_SESSIONS) break;
            points.add(new Point(index * SEGMENT_MINUTES, median(entries), entries.size(), median(online), online.size()));
        }
        return new Result(total, summaries.size(), List.copyOf(points));
    }

    private synchronized Summary summary(Key key) {
        var stamp = archive.detailStamp(key.platform(), key.uid(), key.start());
        if (stamp.isEmpty()) {
            cache.remove(key);
            return MISSING;
        }
        var cached = cache.get(key);
        if (cached != null && cached.stamp().equals(stamp.get())) return cached.summary();
        var detail = archive.read(key.platform(), key.uid(), key.start());
        if (detail.isEmpty()) {
            cache.remove(key);
            return MISSING;
        }
        var result = summarize(detail.get());
        // 读取期间被续场封存替换的文件不可将旧派生值记作新版本。
        if (stamp.equals(archive.detailStamp(key.platform(), key.uid(), key.start()))) {
            cache.put(key, new Cached(stamp.get(), result));
            if (cache.size() > MAX_CACHED_SESSIONS) cache.remove(cache.keySet().iterator().next());
        }
        return result;
    }

    private Summary summarize(LiveDetail detail) {
        if (detail.series() == null || !detail.series().containsKey("enter_count")) return MISSING;
        var entries = detail.series("enter_count");
        var online = detail.series("online_count");
        long base = Math.floorDiv(detail.startTime(), MINUTE) * MINUTE;
        var segments = new ArrayList<Segment>();
        if (detail.endTime() <= detail.startTime()) return new Summary(true, List.of());
        for (long start = base; start < detail.endTime(); start += SEGMENT_MINUTES * MINUTE) {
            double sum = 0;
            int count = 0;
            Double maximum = null;
            for (long at = start; at < Math.min(start + SEGMENT_MINUTES * MINUTE, detail.endTime()); at += MINUTE) {
                var entry = entries.get(at);
                boolean missing = false;
                if (entry == null && detail.gaps() != null) {
                    long from = Math.max(at, detail.startTime()), to = Math.min(at + MINUTE, detail.endTime());
                    for (var gap : detail.gaps()) {
                        if (gap.from() < to && gap.to() > from) { missing = true; break; }
                    }
                }
                if (!missing && (entry == null || Double.isFinite(entry))) {
                    sum += entry == null ? 0 : entry;
                    count++;
                }
                var value = online.get(at);
                if (value != null && Double.isFinite(value)) maximum = maximum == null ? value : Math.max(maximum, value);
            }
            segments.add(new Segment(count == 0 ? null : sum / count, maximum));
        }
        return new Summary(true, List.copyOf(segments));
    }

    private static Double median(List<Double> values) {
        if (values.isEmpty()) return null;
        values.sort(Double::compareTo);
        int middle = values.size() / 2;
        return values.size() % 2 == 0 ? (values.get(middle - 1) + values.get(middle)) / 2 : values.get(middle);
    }
}
