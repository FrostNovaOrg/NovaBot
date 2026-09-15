package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.analytics.LiveMetricCatalog;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.service.LiveSessionArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 运营分析控制器这一层
 * <p>
 * 周／月怎么划、空周期怎么补，都在 {@code LiveSessionAnalytics} 里测过。这里只钉控制器自己的几件事：
 * 周期串怎么读、按 uid 怎么筛、条数上限怎么夹、截断后有没有把「丢掉了多少」说出来。
 * 起法与 {@link LiveSessionShapeTest} 相同：临时目录上的归档＋指标说明，不经 HTTP。
 */
@DisplayName("运营分析控制器")
class AnalyticsControllerTest {
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private static final String BILIBILI = "bilibili";

    private static final String OTHER = "other";

    private static final long UID_A = 1001L;

    private static final long UID_B = 2002L;

    /**
     * 连续周数须大于周视图缺省 26、也大于月／周上限 120，limit 夹取才不是空转
     */
    private static final int WEEK_COUNT = 130;

    private static final int OTHER_COUNT = 10;

    /** 2026-09-14 是周一，夹具锚定这一天，不跟测试时钟走 */
    private static final LocalDate LATEST_MONDAY = LocalDate.of(2026, 9, 14);

    @TempDir
    Path dir;

    private LiveSessionArchive archive;

    private AnalyticsController controller;

    @BeforeEach
    void setUp() throws IOException {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        properties.getDatasource().setJsonPath(dir.resolve("datasource.json").toString());
        Files.writeString(Path.of(properties.getDatasource().getJsonPath()), "[]");

        archive = new LiveSessionArchive(properties);
        controller = new AnalyticsController(archive, catalogs());

        LocalDate firstMonday = LATEST_MONDAY.minusWeeks(WEEK_COUNT - 1);
        for (int week = 0; week < WEEK_COUNT; week++) {
            archive.append(session(BILIBILI, UID_A, "主播甲", firstMonday.plusWeeks(week), 12));
        }
        // 乙只出现在最晚那几周，不把区间往两端撑开；平台与甲不同，用来钉「指标按所选场次的平台取」
        for (int i = 0; i < OTHER_COUNT; i++) {
            archive.append(session(OTHER, UID_B, "主播乙", LATEST_MONDAY.minusWeeks(i), 13));
        }
    }

    @Test
    @DisplayName("period 缺省得到 week")
    void defaultPeriodIsWeek() {
        assertEquals("week", controller.analytics("week", null, 0).getString("period"));
    }

    @Test
    @DisplayName("month 与 MONTH 都得到 month")
    void monthAndMONTHSelectMonthPeriod() {
        assertEquals("month", controller.analytics("month", null, 0).getString("period"));
        assertEquals("month", controller.analytics("MONTH", null, 0).getString("period"));
    }

    @Test
    @DisplayName("未知周期串按周处理（未知即周）")
    void unknownPeriodFallsBackToWeek() {
        assertEquals("week", controller.analytics("quarter", null, 0).getString("period"));
        assertEquals("week", controller.analytics("YEAR", null, 0).getString("period"));
    }

    @Test
    @DisplayName("按 uid 筛选时 sessionCount 只数该人，streamers 仍列全部")
    void uidFilterCountsOnlyThatUidButListsAllStreamers() {
        JSONObject filtered = controller.analytics("week", UID_A, 0);
        JSONObject all = controller.analytics("week", null, 0);

        assertEquals(WEEK_COUNT, filtered.getIntValue("sessionCount"));
        assertEquals(WEEK_COUNT + OTHER_COUNT, all.getIntValue("sessionCount"));
        assertEquals(2, filtered.getJSONArray("streamers").size(), "筛 uid 不该把另一位从主播清单里拿掉");
        assertEquals(2, all.getJSONArray("streamers").size());

        Set<Long> streamerUids = new HashSet<>();
        JSONArray streamers = filtered.getJSONArray("streamers");
        for (int i = 0; i < streamers.size(); i++) {
            streamerUids.add(streamers.getJSONObject(i).getLongValue("uid"));
        }
        assertTrue(streamerUids.contains(UID_A));
        assertTrue(streamerUids.contains(UID_B));

        Set<String> keys = metricKeys(filtered);
        assertEquals(Set.of("danmu_count"), keys, "只看甲时不该带上乙那个平台的指标");
        assertEquals(Set.of("danmu_count", "gift_value"), metricKeys(all));
        assertTrue(filtered.getBooleanValue("metricsKnown"));
    }

    @Test
    @DisplayName("limit 缺省时桶数不超过 26，多出的记入 droppedPeriods，留下最晚那段")
    void limitZeroCapsAt26DropsOldestAndKeepsLatest() {
        JSONObject result = controller.analytics("week", null, 0);
        JSONArray buckets = result.getJSONArray("buckets");

        assertTrue(buckets.size() <= 26, "limit 缺省时桶数不应超过 26，实际 " + buckets.size());
        assertEquals(26, buckets.size());
        assertEquals(WEEK_COUNT - 26, result.getIntValue("droppedPeriods"));

        JSONObject last = buckets.getJSONObject(buckets.size() - 1);
        long latest = archive.summary().latestStart();
        assertTrue(latest >= last.getLongValue("start") && latest < last.getLongValue("end"),
                "截断后末桶应覆盖最晚一场所在的周期");
    }

    @Test
    @DisplayName("limit=500 时桶数不超过 120，多出的记入 droppedPeriods")
    void limit500CapsAt120() {
        JSONObject result = controller.analytics("week", null, 500);
        JSONArray buckets = result.getJSONArray("buckets");

        assertTrue(buckets.size() <= 120, "limit=500 时桶数不应超过 120，实际 " + buckets.size());
        assertEquals(120, buckets.size());
        assertEquals(WEEK_COUNT - 120, result.getIntValue("droppedPeriods"));

        JSONObject last = buckets.getJSONObject(buckets.size() - 1);
        long latest = archive.summary().latestStart();
        assertTrue(latest >= last.getLongValue("start") && latest < last.getLongValue("end"),
                "截断后末桶应覆盖最晚一场所在的周期");
    }

    @Test
    @DisplayName("无场次时 metricsKnown 为假、桶空、sessionCount 为 0")
    void emptyArchiveReportsUnknownMetricsEmptyBucketsAndZeroSessions() throws IOException {
        Path emptyDir = dir.resolve("empty");
        Files.createDirectories(emptyDir);
        NovaCoreProperties emptyProperties = new NovaCoreProperties();
        emptyProperties.getLive().setLiveDataPath(emptyDir.resolve("data.json").toString());
        LiveSessionArchive emptyArchive = new LiveSessionArchive(emptyProperties);
        AnalyticsController emptyController = new AnalyticsController(emptyArchive, catalogs());

        JSONObject result = emptyController.analytics("week", null, 0);

        assertFalse(result.getBooleanValue("metricsKnown"));
        assertTrue(result.getJSONArray("buckets").isEmpty());
        assertEquals(0, result.getIntValue("sessionCount"));
    }

    @Test
    @DisplayName("archive 摘要与归档 summary 一致")
    void archiveBlockMatchesSummary() {
        JSONObject archived = controller.analytics("week", null, 0).getJSONObject("archive");
        LiveSessionArchive.Summary summary = archive.summary();

        assertEquals(summary.count(), archived.getLongValue("count"));
        assertEquals(summary.earliestStart(), archived.getLongValue("earliestStart"));
        assertEquals(summary.latestStart(), archived.getLongValue("latestStart"));
        assertEquals(WEEK_COUNT + OTHER_COUNT, summary.count());
    }

    @Test
    @DisplayName("逐场 limit 缺省不超过 60")
    void sessionsLimitZeroCapsAt60() {
        JSONObject result = controller.sessions(null, 0);
        assertTrue(result.getJSONArray("sessions").size() <= 60);
        assertEquals(60, result.getJSONArray("sessions").size());
    }

    @Test
    @DisplayName("逐场 limit=9999 不超过 500")
    void sessionsLimit9999CapsAt500() {
        JSONObject result = controller.sessions(null, 9999);
        int returned = result.getJSONArray("sessions").size();
        assertTrue(returned <= 500, "limit=9999 时场次数不应超过 500，实际 " + returned);
        assertEquals(WEEK_COUNT + OTHER_COUNT, returned);
    }

    @Test
    @DisplayName("droppedSessions 等于筛后总数减返回数")
    void droppedSessionsEqualsFilteredTotalMinusReturned() {
        JSONObject page = controller.sessions(null, 0);
        int total = page.getIntValue("total");
        int returned = page.getJSONArray("sessions").size();
        assertEquals(WEEK_COUNT + OTHER_COUNT, total);
        assertEquals(total - returned, page.getIntValue("droppedSessions"));
    }

    @Test
    @DisplayName("逐场按 uid 筛选时 total 只数该人")
    void sessionsUidFilterTotalCountsOnlyThatUid() {
        JSONObject filtered = controller.sessions(UID_A, 0);
        JSONObject all = controller.sessions(null, 0);

        assertEquals(WEEK_COUNT, filtered.getIntValue("total"));
        assertEquals(WEEK_COUNT + OTHER_COUNT, all.getIntValue("total"));
        assertEquals(filtered.getIntValue("total") - filtered.getJSONArray("sessions").size(),
                filtered.getIntValue("droppedSessions"));
    }

    private LiveSession session(String platform, long uid, String uname, LocalDate monday, int hour) {
        long start = monday.atTime(hour, 0).atZone(ZONE).toInstant().toEpochMilli();
        return new LiveSession(platform, uid, uname, uid + 10_000,
                start, start + 3_600_000L, 3600,
                Map.of("danmu_count", 10.0, "gift_value", 1.0), Map.of());
    }

    private Set<String> metricKeys(JSONObject result) {
        Set<String> keys = new HashSet<>();
        JSONArray metrics = result.getJSONArray("metrics");
        for (int i = 0; i < metrics.size(); i++) {
            keys.add(metrics.getJSONObject(i).getString("key"));
        }
        return keys;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<LiveMetricCatalog> catalogs() {
        ObjectProvider<LiveMetricCatalog> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(invocation -> Stream.of(
                catalog(BILIBILI, LiveMetricCatalog.Metric.count("danmu_count", "弹幕", "条")),
                catalog(OTHER, LiveMetricCatalog.Metric.money("gift_value", "礼物"))));
        return provider;
    }

    private LiveMetricCatalog catalog(String platform, LiveMetricCatalog.Metric metric) {
        return new LiveMetricCatalog() {
            @Override
            public String platform() {
                return platform;
            }

            @Override
            public List<Metric> metrics() {
                return List.of(metric);
            }
        };
    }
}
