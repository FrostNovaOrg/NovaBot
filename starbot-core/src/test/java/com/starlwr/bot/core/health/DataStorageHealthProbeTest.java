package com.starlwr.bot.core.health;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.service.DefaultLiveDataService;
import com.starlwr.bot.core.service.LiveDataService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 数据存储健康探针测试
 */
@DisplayName("数据存储健康探针")
class DataStorageHealthProbeTest {
    @Test
    @DisplayName("未配置累计存储时应报 OK 并说明累计查询不可用")
    void reportsLiveOnly() {
        HealthStatus status = new DataStorageHealthProbe(new DefaultLiveDataService(new StarBotCoreProperties())).check();

        // 没配外部存储是正常的部署形态，不是故障——标红只会让人对告警麻木
        assertEquals(HealthStatus.Level.OK, status.level());
        assertTrue(status.summary().contains("仅本场数据"), status.summary());
        assertTrue(status.summary().contains("redis"), status.summary());
    }

    @Test
    @DisplayName("配置了累计存储时应说明两份数据都在")
    void reportsTotalAvailable() {
        LiveDataService withTotal = mock(LiveDataService.class);
        when(withTotal.supportsTotalData()).thenReturn(true);

        HealthStatus status = new DataStorageHealthProbe(withTotal).check();

        assertEquals(HealthStatus.Level.OK, status.level());
        assertTrue(status.summary().contains("累计数据"), status.summary());
    }
}
