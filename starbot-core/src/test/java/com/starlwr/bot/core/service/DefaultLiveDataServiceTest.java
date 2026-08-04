package com.starlwr.bot.core.service;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
