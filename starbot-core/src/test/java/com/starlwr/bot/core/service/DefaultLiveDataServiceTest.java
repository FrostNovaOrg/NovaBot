package com.starlwr.bot.core.service;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.model.UserScore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
}
