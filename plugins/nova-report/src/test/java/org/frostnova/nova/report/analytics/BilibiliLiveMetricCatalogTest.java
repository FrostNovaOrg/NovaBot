package org.frostnova.nova.report.analytics;

import org.frostnova.nova.bilibili.model.BilibiliLiveMetric;
import org.frostnova.nova.bilibili.model.BilibiliStreamerMetric;
import org.frostnova.nova.core.analytics.LiveMetricCatalog;
import org.frostnova.nova.core.analytics.LiveSessionAnalytics;
import org.frostnova.nova.core.enums.LiveEndReason;
import org.frostnova.nova.core.model.LiveSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 周月目录只收可累加的指标。在线人数与高能用户都是每场瞬时峰值，
 * 相加会把各场峰之和当成人数。
 * <p>
 * 另一张表是基础数据快照那几项存量：它们只有名字、不参与相加，
 * 因此两张表的键一项都不许重合——同一个键既算得又算不得，看的人无从分辨。
 */
@DisplayName("哔哩哔哩指标目录")
class BilibiliLiveMetricCatalogTest {
    private static final List<LiveMetricCatalog.Metric> LIVE_ONLY = List.of(
            LiveMetricCatalog.Metric.count(BilibiliLiveMetric.GUARD_OPEN_COUNT, "大航海新开", "人次"),
            LiveMetricCatalog.Metric.count(BilibiliLiveMetric.GUARD_RENEW_COUNT, "大航海续费", "人次"),
            LiveMetricCatalog.Metric.count(BilibiliLiveMetric.IMAGE_DEGRADED_COUNT, "开播通知缺图", "次"),
            LiveMetricCatalog.Metric.count(BilibiliLiveMetric.ENTER_COUNT, "进房人次", "人次"),
            LiveMetricCatalog.Metric.count(BilibiliLiveMetric.FANS_AT_START, "开播时粉丝", "人"),
            LiveMetricCatalog.Metric.count(BilibiliLiveMetric.FANS_MEDAL_AT_START, "开播时粉丝团", "人"),
            LiveMetricCatalog.Metric.count(BilibiliLiveMetric.GUARD_AT_START, "开播时大航海", "人")
    );

    @Test
    @DisplayName("正在直播卡的专用指标显示中文名和单位")
    void liveMetricsNameEntriesAndStartSnapshots() {
        var metrics = new BilibiliLiveMetricCatalog().liveMetrics();
        assertAll(LIVE_ONLY.stream().map(expected -> () -> assertEquals(expected,
                metrics.stream().filter(metric -> metric.key().equals(expected.key())).findFirst().orElse(null),
                expected.key() + " 在正在直播卡缺少中文名或单位")));
    }

    @Test
    @DisplayName("本场专用指标不进入可累加表")
    void liveOnlyMetricsStayOutOfPeriodCatalog() {
        var keys = new BilibiliLiveMetricCatalog().metrics().stream()
                .map(LiveMetricCatalog.Metric::key).collect(Collectors.toSet());
        assertAll(LIVE_ONLY.stream().map(metric -> () -> assertFalse(keys.contains(metric.key()),
                metric.key() + " 不应进入可累加表")));
    }

    @Test
    @DisplayName("周期统计不会把本场专用指标加入汇总结果")
    void liveOnlyMetricsStayOutOfPeriodTotals() {
        var keys = new BilibiliLiveMetricCatalog().metrics().stream()
                .map(LiveMetricCatalog.Metric::key).collect(Collectors.toSet());
        var session = new LiveSession("bilibili", 1L, "sample", 2L, 1_760_000_000_000L,
                1_760_003_600_000L, 3600, Map.of(BilibiliLiveMetric.DANMU_COUNT, 10d,
                BilibiliLiveMetric.ENTER_COUNT, 123d, BilibiliLiveMetric.FANS_AT_START, 1000d,
                BilibiliLiveMetric.FANS_MEDAL_AT_START, 100d, BilibiliLiveMetric.GUARD_AT_START, 5d),
                Map.of(), LiveEndReason.NORMAL, List.of());
        for (var period : LiveSessionAnalytics.Period.values()) {
            var totals = new LiveSessionAnalytics().aggregate(List.of(session, session), period, keys)
                    .get(0).metrics();
            assertEquals(20d, totals.get(BilibiliLiveMetric.DANMU_COUNT));
            assertAll(LIVE_ONLY.stream().map(metric -> () -> assertFalse(totals.containsKey(metric.key()),
                    period + " 汇总不应包含 " + metric.key())));
        }
    }

    @Test
    @DisplayName("在线人数与高能用户是每场峰值，不进周月目录")
    void peakAudienceMetricsStayOutOfPeriodCatalog() {
        Set<String> keys = new BilibiliLiveMetricCatalog().metrics().stream()
                .map(LiveMetricCatalog.Metric::key)
                .collect(Collectors.toSet());

        assertFalse(keys.contains(BilibiliLiveMetric.ONLINE_COUNT),
                "在线人数是每场瞬时峰值，相加会把各场峰之和当人数");
        assertFalse(keys.contains(BilibiliLiveMetric.ONLINE_RANK_COUNT),
                "高能用户是每场瞬时峰值，相加会把各场峰之和当人数");
    }

    @Test
    @DisplayName("快照三项说得出人话，且与周月目录的键一项都不重合")
    void snapshotMetricsCoverTheSampledKeysAndStayOutOfThePeriodCatalog() {
        BilibiliLiveMetricCatalog catalog = new BilibiliLiveMetricCatalog();
        List<String> snapshotKeys = catalog.snapshotMetrics().stream()
                .map(LiveMetricCatalog.Metric::key)
                .toList();

        // 采样服务采哪几项，目录就得说得出哪几项：漏一项，那一行在界面上显示的是裸键
        assertEquals(List.of(BilibiliStreamerMetric.FANS, BilibiliStreamerMetric.FANS_MEDAL,
                        BilibiliStreamerMetric.GUARD), snapshotKeys,
                "顺序即界面展示顺序");
        assertEquals(snapshotKeys.size(), Set.copyOf(snapshotKeys).size(), "键不许重复");
        catalog.snapshotMetrics().forEach(metric ->
                assertFalse(metric.name().isBlank(), "每一项都得有人话名，否则界面上还是裸键"));

        // 🔴 两边的键不许重合。快照记的是存量，周月统计做的就是相加——同一个键既算得又算不得时，
        // 看的人无从分辨，而两张表各自看起来都对
        Set<String> periodKeys = catalog.metrics().stream()
                .map(LiveMetricCatalog.Metric::key)
                .collect(Collectors.toSet());
        snapshotKeys.forEach(key -> assertFalse(periodKeys.contains(key),
                "快照指标 " + key + " 同时进了可累加集，存量相加是个无中生有的数"));
    }
    @Test
    @DisplayName("概况口径包含三项流水、九份观众名单且进房不算互动")
    void insightsRules() {
        var rules = new BilibiliLiveMetricCatalog().insights();
        org.junit.jupiter.api.Assertions.assertNotNull(rules, "平台必须说明概况口径");
        assertEquals(List.of(BilibiliLiveMetric.GIFT_VALUE, BilibiliLiveMetric.SUPER_CHAT_VALUE,
                BilibiliLiveMetric.GUARD_VALUE), rules.revenueKeys());
        assertEquals(BilibiliLiveMetric.DANMU_COUNT, rules.messageKey());
        assertEquals(Set.of(BilibiliLiveMetric.ENTER_USERS, BilibiliLiveMetric.DANMU_USERS,
                BilibiliLiveMetric.LIKE_USERS, BilibiliLiveMetric.GIFT_USERS,
                BilibiliLiveMetric.SUPER_CHAT_USERS, BilibiliLiveMetric.BOX_USERS,
                BilibiliLiveMetric.BOX_PROFIT_USERS, BilibiliLiveMetric.GUARD_USERS,
                BilibiliLiveMetric.REVENUE_USERS), Set.copyOf(rules.audienceSets()));
        assertEquals(8, rules.interactionSets().size());
        assertFalse(rules.interactionSets().contains(BilibiliLiveMetric.ENTER_USERS));
        assertEquals(BilibiliStreamerMetric.FANS, rules.fansKey());
    }
}
