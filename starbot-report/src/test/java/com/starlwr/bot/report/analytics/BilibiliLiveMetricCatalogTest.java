package com.starlwr.bot.report.analytics;

import com.starlwr.bot.bilibili.model.BilibiliLiveMetric;
import com.starlwr.bot.bilibili.model.BilibiliStreamerMetric;
import com.starlwr.bot.core.analytics.LiveMetricCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 周月目录只收可累加的指标。在线人数与高能用户都是每场瞬时峰值，
 * 相加会把各场峰之和当成人数。
 * <p>
 * 另一张表是基础数据快照那几项存量：它们只有名字、不参与相加，
 * 因此两张表的键一项都不许重合——同一个键既算得又算不得，看的人无从分辨。
 */
@DisplayName("哔哩哔哩指标目录")
class BilibiliLiveMetricCatalogTest {
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
}
