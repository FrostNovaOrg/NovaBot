package com.starlwr.bot.core.service;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.model.UserScore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 默认直播数据服务测试
 * <p>
 * 覆盖本场直播统计指标的累计、取最大、独立人数与重置语义。
 * 持久化路径依赖文件系统与调度器，不在本测试内。
 */
@DisplayName("直播数据服务")
class DefaultLiveDataServiceTest {
    private static final String PLATFORM = "bilibili";

    private static final Long UID = 10001L;

    private DefaultLiveDataService service;

    @BeforeEach
    void setUp() {
        service = new DefaultLiveDataService(new StarBotCoreProperties());
    }

    @Test
    @DisplayName("未记录过的指标应为 0")
    void unknownMetricIsZero() {
        assertEquals(0.0, service.getLiveMetric(PLATFORM, UID, "danmu_count"));
        assertEquals(0, service.getLiveMetricUserCount(PLATFORM, UID, "danmu_users"));
    }

    @Test
    @DisplayName("累加指标应逐次叠加")
    void incrementAccumulates() {
        service.incrementLiveMetric(PLATFORM, UID, "gift_value", 5.2);
        service.incrementLiveMetric(PLATFORM, UID, "gift_value", 4.8);

        assertEquals(10.0, service.getLiveMetric(PLATFORM, UID, "gift_value"));
    }

    @Test
    @DisplayName("取最大指标应只保留最大值")
    void maxKeepsLargest() {
        service.maxLiveMetric(PLATFORM, UID, "like_total", 100);
        service.maxLiveMetric(PLATFORM, UID, "like_total", 80);
        service.maxLiveMetric(PLATFORM, UID, "like_total", 120);

        assertEquals(120.0, service.getLiveMetric(PLATFORM, UID, "like_total"));
    }

    @Test
    @DisplayName("直接设定指标应覆盖而非叠加，用于记录快照类的值")
    void setOverwrites() {
        service.incrementLiveMetric(PLATFORM, UID, "fans_at_start", 100);
        service.setLiveMetric(PLATFORM, UID, "fans_at_start", 243);

        assertEquals(243.0, service.getLiveMetric(PLATFORM, UID, "fans_at_start"));
    }

    @Test
    @DisplayName("独立人数应对同一用户去重")
    void userCountDeduplicates() {
        service.recordLiveMetricUser(PLATFORM, UID, "danmu_users", 1L);
        service.recordLiveMetricUser(PLATFORM, UID, "danmu_users", 2L);
        service.recordLiveMetricUser(PLATFORM, UID, "danmu_users", 1L);

        assertEquals(2, service.getLiveMetricUserCount(PLATFORM, UID, "danmu_users"));
    }

    @Test
    @DisplayName("不同主播的指标应相互隔离")
    void metricsIsolatedPerStreamer() {
        service.incrementLiveMetric(PLATFORM, UID, "danmu_count", 3);
        service.incrementLiveMetric(PLATFORM, 20002L, "danmu_count", 7);

        assertEquals(3.0, service.getLiveMetric(PLATFORM, UID, "danmu_count"));
        assertEquals(7.0, service.getLiveMetric(PLATFORM, 20002L, "danmu_count"));
    }

    @Test
    @DisplayName("重置直播数据应清空指标与独立人数，且不影响其他主播")
    void resetClearsMetricsForSingleStreamer() {
        service.incrementLiveMetric(PLATFORM, UID, "danmu_count", 5);
        service.recordLiveMetricUser(PLATFORM, UID, "danmu_users", 1L);
        service.incrementLiveMetric(PLATFORM, 20002L, "danmu_count", 9);

        service.resetLiveData(PLATFORM, UID);

        assertEquals(0.0, service.getLiveMetric(PLATFORM, UID, "danmu_count"));
        assertEquals(0, service.getLiveMetricUserCount(PLATFORM, UID, "danmu_users"));
        assertEquals(9.0, service.getLiveMetric(PLATFORM, 20002L, "danmu_count"));
    }

    @Test
    @DisplayName("重置不影响开播时间等非指标数据")
    void resetKeepsNonMetricData() {
        service.setLiveStartTime(PLATFORM, UID, 123456L);
        service.incrementLiveMetric(PLATFORM, UID, "danmu_count", 5);

        service.resetLiveData(PLATFORM, UID);

        assertEquals(123456L, service.getLiveStartTime(PLATFORM, UID).orElseThrow());
    }

    // ============ 按用户计分（排行榜与个人数据） ============

    @Test
    @DisplayName("按用户计分应逐次累加")
    void userMetricAccumulates() {
        service.incrementLiveUserMetric(PLATFORM, UID, "gift_value", 1L, 5.2);
        service.incrementLiveUserMetric(PLATFORM, UID, "gift_value", 1L, 4.8);
        service.incrementLiveUserMetric(PLATFORM, UID, "gift_value", 2L, 3.0);

        assertEquals(10.0, service.getLiveUserMetric(PLATFORM, UID, "gift_value", 1L));
        assertEquals(3.0, service.getLiveUserMetric(PLATFORM, UID, "gift_value", 2L));
    }

    @Test
    @DisplayName("排行榜应按得分降序并截断到指定名次")
    void rankingSortsDescendingAndLimits() {
        service.incrementLiveUserMetric(PLATFORM, UID, "danmu_users", 1L, 3);
        service.incrementLiveUserMetric(PLATFORM, UID, "danmu_users", 2L, 9);
        service.incrementLiveUserMetric(PLATFORM, UID, "danmu_users", 3L, 5);

        List<UserScore> top2 = service.getLiveUserRanking(PLATFORM, UID, "danmu_users", 2);

        assertEquals(2, top2.size());
        assertEquals(2L, top2.get(0).userUid());
        assertEquals(9.0, top2.get(0).score());
        assertEquals(3L, top2.get(1).userUid());
    }

    @Test
    @DisplayName("排行榜取前 N 名时不足 N 人应返回实际人数")
    void rankingReturnsFewerWhenNotEnoughUsers() {
        service.incrementLiveUserMetric(PLATFORM, UID, "danmu_users", 1L, 1);

        assertEquals(1, service.getLiveUserRanking(PLATFORM, UID, "danmu_users", 10).size());
    }

    @Test
    @DisplayName("取前 0 名应返回空表而非全部")
    void rankingWithZeroLimitIsEmpty() {
        service.incrementLiveUserMetric(PLATFORM, UID, "danmu_users", 1L, 1);

        assertEquals(0, service.getLiveUserRanking(PLATFORM, UID, "danmu_users", 0).size());
    }

    @Test
    @DisplayName("记录参与用户等价于以增量 1 计分，且仍能正确统计人数")
    void recordUserIsIncrementByOne() {
        service.recordLiveMetricUser(PLATFORM, UID, "enter_users", 1L);
        service.recordLiveMetricUser(PLATFORM, UID, "enter_users", 1L);
        service.recordLiveMetricUser(PLATFORM, UID, "enter_users", 2L);

        assertEquals(2, service.getLiveMetricUserCount(PLATFORM, UID, "enter_users"));
        assertEquals(2.0, service.getLiveUserMetric(PLATFORM, UID, "enter_users", 1L));
    }

    @Test
    @DisplayName("名次应从 1 开始，未参与者为 0")
    void rankStartsAtOne() {
        service.incrementLiveUserMetric(PLATFORM, UID, "gift_value", 1L, 3);
        service.incrementLiveUserMetric(PLATFORM, UID, "gift_value", 2L, 9);
        service.incrementLiveUserMetric(PLATFORM, UID, "gift_value", 3L, 5);

        assertEquals(1, service.getLiveUserRank(PLATFORM, UID, "gift_value", 2L));
        assertEquals(2, service.getLiveUserRank(PLATFORM, UID, "gift_value", 3L));
        assertEquals(3, service.getLiveUserRank(PLATFORM, UID, "gift_value", 1L));
        assertEquals(0, service.getLiveUserRank(PLATFORM, UID, "gift_value", 99L));
    }

    @Test
    @DisplayName("同分应并列取较优名次")
    void rankTiesShareBestPosition() {
        service.incrementLiveUserMetric(PLATFORM, UID, "danmu_users", 1L, 5);
        service.incrementLiveUserMetric(PLATFORM, UID, "danmu_users", 2L, 5);
        service.incrementLiveUserMetric(PLATFORM, UID, "danmu_users", 3L, 1);

        // 两人同为 5 分时都算第 1，而不是一个第 1 一个第 2
        assertEquals(1, service.getLiveUserRank(PLATFORM, UID, "danmu_users", 1L));
        assertEquals(1, service.getLiveUserRank(PLATFORM, UID, "danmu_users", 2L));
        assertEquals(3, service.getLiveUserRank(PLATFORM, UID, "danmu_users", 3L));
    }

    @Test
    @DisplayName("未记录过的指标查名次应为 0 而非报错")
    void rankOfUnknownMetricIsZero() {
        assertEquals(0, service.getLiveUserRank(PLATFORM, UID, "gift_value", 1L));
    }

    @Test
    @DisplayName("重置直播数据应一并清空计分表")
    void resetClearsUserScores() {
        service.incrementLiveUserMetric(PLATFORM, UID, "gift_value", 1L, 5);

        service.resetLiveData(PLATFORM, UID);

        assertEquals(0.0, service.getLiveUserMetric(PLATFORM, UID, "gift_value", 1L));
        assertEquals(0, service.getLiveUserRanking(PLATFORM, UID, "gift_value", 10).size());
    }

    @Test
    @DisplayName("默认实现不支持累计数据，且累计查询一律返回 0")
    void defaultImplementationHasNoTotalData() {
        service.incrementLiveUserMetric(PLATFORM, UID, "gift_value", 1L, 5);
        service.mergeLiveDataIntoTotal(PLATFORM, UID);

        // 调用方据此提示「需配置 Redis」，而不是把 0 当成真实数据展示
        assertFalse(service.supportsTotalData());
        assertEquals(0.0, service.getTotalMetric(PLATFORM, UID, "gift_value"));
        assertEquals(0.0, service.getTotalUserMetric(PLATFORM, UID, "gift_value", 1L));
        assertEquals(0, service.getTotalUserRanking(PLATFORM, UID, "gift_value", 10).size());
    }

    // ============ 时间序列（互动曲线） ============

    @Test
    @DisplayName("同一分钟内的互动应合并到同一个时间格")
    void seriesBucketsByMinute() {
        long minute = 1_700_000_040_000L;

        service.incrementLiveSeries(PLATFORM, UID, "danmu_count", minute, 3);
        service.incrementLiveSeries(PLATFORM, UID, "danmu_count", minute + 19_000, 5);

        java.util.Map<Long, Double> series = service.getLiveSeries(PLATFORM, UID, "danmu_count");
        assertEquals(1, series.size());
        assertEquals(8.0, series.values().iterator().next());
    }

    @Test
    @DisplayName("跨分钟的互动应落在不同时间格，且键为该格的起始时刻")
    void seriesSplitsAcrossMinutes() {
        long base = 1_700_000_040_000L;

        service.incrementLiveSeries(PLATFORM, UID, "danmu_count", base, 3);
        service.incrementLiveSeries(PLATFORM, UID, "danmu_count", base + 60_000, 5);

        java.util.Map<Long, Double> series = service.getLiveSeries(PLATFORM, UID, "danmu_count");
        assertEquals(2, series.size());
        // 键应是分钟的起点，而不是事件到达的那一刻
        assertEquals(3.0, series.get(1_700_000_040_000L));
        assertEquals(5.0, series.get(1_700_000_100_000L));
    }

    @Test
    @DisplayName("时间序列应按时间递增返回，绘图直接照单画即可")
    void seriesIsOrdered() {
        long base = 1_700_000_040_000L;
        service.incrementLiveSeries(PLATFORM, UID, "danmu_count", base + 180_000, 1);
        service.incrementLiveSeries(PLATFORM, UID, "danmu_count", base, 1);
        service.incrementLiveSeries(PLATFORM, UID, "danmu_count", base + 60_000, 1);

        List<Long> keys = new java.util.ArrayList<>(service.getLiveSeries(PLATFORM, UID, "danmu_count").keySet());
        assertEquals(List.of(base, base + 60_000, base + 180_000), keys);
    }

    @Test
    @DisplayName("重置直播数据应一并清空时间序列")
    void resetClearsSeries() {
        service.incrementLiveSeries(PLATFORM, UID, "danmu_count", 1_700_000_040_000L, 3);

        service.resetLiveData(PLATFORM, UID);

        assertTrue(service.getLiveSeries(PLATFORM, UID, "danmu_count").isEmpty());
    }

    @Test
    @DisplayName("词频应逐次累计")
    void wordFrequencyAccumulates() {
        service.incrementLiveWordFrequency(PLATFORM, UID, "唱歌");
        service.incrementLiveWordFrequency(PLATFORM, UID, "唱歌");
        service.incrementLiveWordFrequency(PLATFORM, UID, "好听");

        assertEquals(2, service.getLiveWordFrequencies(PLATFORM, UID).get("唱歌"));
        assertEquals(1, service.getLiveWordFrequencies(PLATFORM, UID).get("好听"));
    }

    @Test
    @DisplayName("重置直播数据应一并清空词频")
    void resetClearsWordFrequencies() {
        service.incrementLiveWordFrequency(PLATFORM, UID, "唱歌");

        service.resetLiveData(PLATFORM, UID);

        assertEquals(0, service.getLiveWordFrequencies(PLATFORM, UID).size());
    }

    @Test
    @DisplayName("指标快照应含全部本场指标，供并入累计")
    void snapshotsLiveMetrics() {
        service.incrementLiveMetric(PLATFORM, UID, "danmu_count", 144);
        service.incrementLiveMetric(PLATFORM, UID, "gift_value", 0.4);

        java.util.Map<String, Double> snapshot = service.liveMetrics(PLATFORM, UID);

        assertEquals(2, snapshot.size());
        assertEquals(144.0, snapshot.get("danmu_count"));
        assertEquals(0.4, snapshot.get("gift_value"));
    }

    @Test
    @DisplayName("用户计分快照应按指标分组保留每个用户的得分")
    void snapshotsLiveUserMetrics() {
        service.incrementLiveUserMetric(PLATFORM, UID, "danmu_users", 1L, 3);
        service.incrementLiveUserMetric(PLATFORM, UID, "danmu_users", 2L, 5);
        service.incrementLiveUserMetric(PLATFORM, UID, "gift_users", 1L, 52.5);

        java.util.Map<String, java.util.Map<Long, Double>> snapshot = service.liveUserMetrics(PLATFORM, UID);

        assertEquals(2, snapshot.size());
        assertEquals(3.0, snapshot.get("danmu_users").get(1L));
        assertEquals(5.0, snapshot.get("danmu_users").get(2L));
        assertEquals(52.5, snapshot.get("gift_users").get(1L));
    }

    @Test
    @DisplayName("昵称快照应可用于累计存储的展示")
    void snapshotsLiveUserNames() {
        service.recordLiveUserName(PLATFORM, UID, 1L, "甲乙丙");

        assertEquals("甲乙丙", service.liveUserNames(PLATFORM, UID).get(1L));
    }

    @Test
    @DisplayName("无数据时快照应为空表而非报错")
    void snapshotsEmptyWhenNoData() {
        assertTrue(service.liveMetrics(PLATFORM, UID).isEmpty());
        assertTrue(service.liveUserMetrics(PLATFORM, UID).isEmpty());
        assertTrue(service.liveUserNames(PLATFORM, UID).isEmpty());
    }
}
