package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import org.frostnova.nova.core.analytics.LiveMetricCatalog;
import org.frostnova.nova.core.analytics.LiveSessionAnalytics;
import org.frostnova.nova.core.analytics.StatisticsDay;
import org.frostnova.nova.core.analytics.StreamerInsights;
import org.frostnova.nova.core.analytics.StreamerMonitoring;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.LiveSessionArchive;
import org.frostnova.nova.core.service.StreamerCalendarService;
import org.frostnova.nova.core.service.StreamerDirectory;
import org.frostnova.nova.core.service.StreamerSnapshotArchive;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 主播页与智能助手共用的区间统计。 */
@NovaComponent
public class StreamerStatistics {
    private final AbstractDataSource dataSource;
    private final StreamerDirectory directory;
    private final LiveDataService liveDataService;
    private final LiveSessionArchive archive;
    private final StreamerSnapshotArchive snapshots;
    private final ObjectProvider<LiveMetricCatalog> catalogs;
    private final Clock clock;
    private final StreamerCalendarService calendars;
    private final LiveSessionAnalytics analytics = new LiveSessionAnalytics();

    @Autowired
    public StreamerStatistics(AbstractDataSource dataSource, StreamerDirectory directory, LiveDataService liveDataService,
                              LiveSessionArchive archive, StreamerSnapshotArchive snapshots,
                              ObjectProvider<LiveMetricCatalog> catalogs) {
        this(dataSource, directory, liveDataService, archive, snapshots, catalogs, Clock.systemUTC());
    }

    StreamerStatistics(AbstractDataSource dataSource, StreamerDirectory directory, LiveDataService liveDataService,
                       LiveSessionArchive archive, StreamerSnapshotArchive snapshots,
                       ObjectProvider<LiveMetricCatalog> catalogs, Clock clock) {
        this.dataSource = dataSource;
        this.directory = directory;
        this.liveDataService = liveDataService;
        this.archive = archive;
        this.snapshots = snapshots;
        this.catalogs = catalogs;
        this.clock = clock;
        this.calendars = new StreamerCalendarService(liveDataService);
    }

    public ResponseEntity<JSONObject> insights(String platform, Long uid, String days) {
        if (!StreamerController.validDays(days)) return StreamerController.invalidRange();
        var sessions = sessionsOf(platform, uid);
        var entry = configuredStreamer(platform, uid);
        if (entry.isEmpty() && sessions.isEmpty()) return StreamerController.missingStreamer();
        var rules = catalogs.orderedStream().filter(c -> platform.equals(c.platform()))
                .map(LiveMetricCatalog::insights).filter(Objects::nonNull).findFirst().orElse(null);
        var range = StreamerInsights.recent(Integer.parseInt(days), clock);
        var sampled = snapshots.find(0, range.to().toEpochMilli()).stream()
                .filter(s -> platform.equals(s.platform()) && uid.equals(s.uid())).toList();
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("days", Integer.parseInt(days));
        result.put("living", entry.map(StreamerDirectory.Entry::enabled).orElse(false)
                && liveDataService.getLiveStatus(platform, uid).orElse(false));
        var comparison = new StreamerInsights().compareRecent(sessions, sampled, Integer.parseInt(days),
                Clock.fixed(range.to(), clock.getZone()), rules);
        JSONObject insights = JSON.parseObject(JSON.toJSONString(comparison.current(), JSONWriter.Feature.WriteNulls));
        long now = range.to().toEpochMilli();
        var watched = liveDataService.monitoringIntervals(platform, uid, now);
        var monitoring = new StreamerMonitoring(liveDataService.isMonitoringRecording(), watched, now);
        Long proof = calendars.calculate(platform, uid, calendarEnabled(platform, uid), sessions, watched,
                StatisticsDay.dateOf(range.to()), Integer.parseInt(days), Clock.fixed(range.to(), clock.getZone())).provableFrom();
        insights.put("monitoring", monitoring.period(comparison.current().from(), comparison.current().to(), proof, Integer.parseInt(days)));
        JSONObject previous = JSON.parseObject(JSON.toJSONString(comparison.previous(), JSONWriter.Feature.WriteNulls));
        previous.put("monitoring", monitoring.period(comparison.previous().from(), comparison.previous().to(), proof, Integer.parseInt(days)));
        insights.put("previous", previous);
        insights.put("comparisons", comparison.comparisons());
        insights.put("comparisonNote", comparison.comparisonNote());
        result.put("insights", insights);
        return ResponseEntity.ok(result);
    }

    public ResponseEntity<JSONObject> fans(String platform, Long uid, String days) {
        if (!StreamerController.validDays(days)) return StreamerController.invalidRange();
        if (!knownStreamer(platform, uid, sessionsOf(platform, uid))) return StreamerController.missingStreamer();
        var range = StreamerInsights.recent(Integer.parseInt(days), clock);
        var rules = catalogs.orderedStream().filter(c -> platform.equals(c.platform()))
                .map(LiveMetricCatalog::insights).filter(Objects::nonNull).findFirst().orElse(null);
        var sampled = snapshots.find(0, range.to().toEpochMilli()).stream()
                .filter(s -> platform.equals(s.platform()) && uid.equals(s.uid())).toList();
        JSONArray points = new JSONArray();
        if (rules != null && rules.fansKey() != null) {
            sampled.stream().filter(s -> s.at() >= range.from().toEpochMilli() && s.has(rules.fansKey()))
                    .forEach(s -> points.add(JSONObject.of("at", s.at(), "value", s.metric(rules.fansKey()))));
        }
        var change = StreamerInsights.fans(sampled, range.from().toEpochMilli(), range.to().toEpochMilli(), rules);
        return ResponseEntity.ok(JSONObject.of("success", true, "days", Integer.parseInt(days), "fans",
                JSONObject.of("points", points, "change", change == null ? null : change.change(),
                        "partial", change == null || change.partial())));
    }

    public ResponseEntity<JSONObject> rangeTrend(String platform, Long uid, String period, String days) {
        if (!StreamerController.validDays(days)) return StreamerController.invalidRange();
        if (!List.of("day", "week", "month").contains(period)) {
            return ResponseEntity.badRequest().body(JSONObject.of("success", false, "message", "请选择 day、week 或 month"));
        }
        var sessions = sessionsOf(platform, uid);
        if (!knownStreamer(platform, uid, sessions)) return StreamerController.missingStreamer();
        var metrics = catalogs.orderedStream().filter(c -> platform.equals(c.platform())).findFirst()
                .map(LiveMetricCatalog::metrics).orElse(List.of());
        Set<String> keys = new LinkedHashSet<>();
        metrics.forEach(m -> keys.add(m.key()));
        var range = StreamerInsights.recent(Integer.parseInt(days), clock);
        var buckets = analytics.aggregate(sessions,
                LiveSessionAnalytics.Period.valueOf(period.toUpperCase(java.util.Locale.ROOT)), keys, range.from(), range.to());
        var rules = catalogs.orderedStream().filter(c -> platform.equals(c.platform()))
                .map(LiveMetricCatalog::insights).filter(Objects::nonNull).findFirst().orElse(null);
        var summaries = new StreamerInsights().trend(sessions, range.from(), range.to(),
                LiveSessionAnalytics.Period.valueOf(period.toUpperCase(java.util.Locale.ROOT)), rules);
        var items = LiveAnalyticsJson.toJson(buckets);
        for (int i = 0; i < items.size(); i++) {
            items.getJSONObject(i).putAll(JSON.parseObject(JSON.toJSONString(summaries.get(i), JSONWriter.Feature.WriteNulls)));
        }
        if ("day".equals(period)) {
            long now = range.to().toEpochMilli();
            var watched = liveDataService.monitoringIntervals(platform, uid, now);
            var monitoring = new StreamerMonitoring(liveDataService.isMonitoringRecording(), watched, now);
            var calendar = calendars.calculate(platform, uid, calendarEnabled(platform, uid), sessions, watched,
                    StatisticsDay.dateOf(range.to()), Integer.parseInt(days), Clock.fixed(range.to(), clock.getZone()));
            var statuses = new LinkedHashMap<String, String>();
            calendar.items().forEach(day -> statuses.put(day.date(), day.status()));
            for (int i = 0; i < items.size(); i++) {
                var item = items.getJSONObject(i);
                item.put("monitoring", monitoring.evidence(item.getLongValue("start"), item.getLongValue("end")));
                item.put("status", statuses.get(StatisticsDay.dateOf(Instant.ofEpochMilli(item.getLongValue("start"))).toString()));
            }
        }
        return ResponseEntity.ok(JSONObject.of("success", true, "days", Integer.parseInt(days), "trend",
                JSONObject.of("period", period, "buckets", items,
                        "metrics", LiveAnalyticsJson.describe(metrics), "metricsKnown", !metrics.isEmpty())));
    }

    private List<LiveSession> sessionsOf(String platform, Long uid) {
        return archive.find(0, Long.MAX_VALUE).stream()
                .filter(session -> platform.equals(session.platform()) && uid.equals(session.uid())).toList();
    }

    private Optional<StreamerDirectory.Entry> configuredStreamer(String platform, Long uid) {
        return StreamerDirectory.roster(directory.entries(), dataSource.getAllUsers()).stream()
                .filter(entry -> platform.equals(entry.platform()) && uid.equals(entry.uid())).findFirst();
    }

    private boolean knownStreamer(String platform, Long uid, List<LiveSession> sessions) {
        return !sessions.isEmpty() || configuredStreamer(platform, uid).isPresent();
    }

    private boolean calendarEnabled(String platform, Long uid) {
        return configuredStreamer(platform, uid).map(StreamerDirectory.Entry::enabled).orElse(false);
    }
}
