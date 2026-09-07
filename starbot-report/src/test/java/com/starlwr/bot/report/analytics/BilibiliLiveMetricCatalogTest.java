package com.starlwr.bot.report.analytics;

import com.starlwr.bot.bilibili.model.BilibiliLiveMetric;
import com.starlwr.bot.core.analytics.LiveMetricCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 周月目录只收可累加的指标。在线人数与高能用户都是每场瞬时峰值，
 * 相加会把各场峰之和当成人数。
 */
@DisplayName("哔哩哔哩周月指标目录")
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
}
