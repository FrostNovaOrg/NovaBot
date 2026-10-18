package org.frostnova.nova.core.service;

import org.frostnova.nova.core.analytics.LiveMetricCatalog;
import org.frostnova.nova.core.analytics.StatisticsDay;
import org.frostnova.nova.core.analytics.WeeklyReport;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;

/** 预览、定时发送与试发共用的周报取数和绘图入口。 */
@Service
public class WeeklyReportContentService {
    private final AbstractDataSource source;
    private final LiveDataService live;
    private final StreamerDirectory directory;
    private final LiveSessionArchive archive;
    private final StreamerSnapshotArchive snapshots;
    private final ObjectProvider<LiveMetricCatalog> catalogs;
    private final ObjectProvider<WeeklyReportRenderer> renderers;

    public WeeklyReportContentService(AbstractDataSource source, StreamerDirectory directory,
            LiveSessionArchive archive, StreamerSnapshotArchive snapshots, LiveDataService live,
            ObjectProvider<LiveMetricCatalog> catalogs, ObjectProvider<WeeklyReportRenderer> renderers) {
        this.source = source;
        this.live = live;
        this.directory = directory;
        this.archive = archive;
        this.snapshots = snapshots;
        this.catalogs = catalogs;
        this.renderers = renderers;
    }

    public Optional<WeeklyReport.Preview> preview(String platform, Long uid, Instant now, boolean showRevenue) {
        var sessions = archive.find(0, Long.MAX_VALUE).stream()
                .filter(s -> platform.equals(s.platform()) && uid.equals(s.uid()))
                .sorted(Comparator.comparingLong(org.frostnova.nova.core.model.LiveSession::startTime)).toList();
        var users = source.getAllUsers();
        var loaded = users.stream()
                .filter(u -> platform.equals(u.getPlatform()) && uid.equals(u.getUid())).findFirst().orElse(null);
        if (loaded == null && sessions.isEmpty() && directory.entries().stream()
                .noneMatch(e -> platform.equals(e.platform()) && uid.equals(e.uid()))) return Optional.empty();
        var rules = catalogs.orderedStream().filter(c -> platform.equals(c.platform()))
                .map(LiveMetricCatalog::insights).filter(java.util.Objects::nonNull).findFirst().orElse(null);
        var range = WeeklyReport.previousWeek(now);
        var sampled = snapshots.find(0, range.to().toEpochMilli()).stream()
                .filter(s -> platform.equals(s.platform()) && uid.equals(s.uid())).toList();
        String uname = loaded == null ? null : loaded.getUname();
        Long roomId = loaded == null ? null : loaded.getRoomId();
        if ((uname == null || uname.isBlank()) && !sessions.isEmpty()) {
            var latest = sessions.get(sessions.size() - 1);
            uname = latest.uname();
            if (roomId == null) roomId = latest.roomId();
        }
        var identity = new LiveStreamerInfo(uid, uname, roomId, loaded == null ? null : loaded.getFace());
        var report = WeeklyReport.calculate(uname, sessions, sampled, now, rules, showRevenue);
        boolean enabled = StreamerDirectory.roster(directory.entries(), users).stream()
                .anyMatch(entry -> platform.equals(entry.platform()) && uid.equals(entry.uid()) && entry.enabled());
        var calendar = new StreamerCalendarService(live).calculate(platform, uid, enabled, sessions,
                live.monitoringIntervals(platform, uid, now.toEpochMilli()), report.lastDay(), 35,
                Clock.fixed(now, StatisticsDay.ZONE));
        var data = report.withCalendar(calendar);
        return Optional.of(WeeklyReport.preview(data, () -> renderers.orderedStream()
                .filter(r -> platform.equals(r.platform())).findFirst()
                .map(r -> r.render(identity, data)).orElse(null)));
    }
}
