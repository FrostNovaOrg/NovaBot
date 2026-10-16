package org.frostnova.nova.core.analytics;

import org.frostnova.nova.core.model.LiveGap;

import java.util.Comparator;
import java.util.List;

/** 监看证据按查询范围裁剪；秒数保留毫秒精度，重叠区间只计一次。 */
public final class StreamerMonitoring {
    public record Evidence(boolean recording, double watchedSeconds, double elapsedSeconds) {}
    public record Period(boolean recording, double watchedSeconds, double elapsedSeconds,
                         Double observableDays, int rangeDays) {}

    private final boolean recording;
    private final List<LiveGap> watched;
    private final long now;

    public StreamerMonitoring(boolean recording, List<LiveGap> watched, long now) {
        this.recording = recording;
        this.watched = watched.stream().sorted(Comparator.comparingLong(LiveGap::from)).toList();
        this.now = now;
    }

    public Evidence evidence(long from, long to) {
        long until = Math.max(from, Math.min(to, now));
        long cursor = from;
        long millis = 0;
        if (recording) for (var interval : watched) {
            long start = Math.max(cursor, interval.from());
            long end = Math.min(until, interval.to());
            if (end > start) {
                millis += end - start;
                cursor = end;
            }
            if (cursor >= until) break;
        }
        return new Evidence(recording, millis / 1000d, (until - from) / 1000d);
    }

    public Period period(long from, long to, Long provableFrom, int days) {
        var evidence = evidence(from, to);
        Double observable = provableFrom == null ? null : Math.round(Math.max(0,
                Math.min(to, now) - Math.max(from, provableFrom)) / 8640000d) / 10d;
        return new Period(recording, evidence.watchedSeconds(), evidence.elapsedSeconds(), observable, days);
    }
}
