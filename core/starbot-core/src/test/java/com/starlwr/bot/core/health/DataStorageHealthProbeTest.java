package com.starlwr.bot.core.health;

import com.starlwr.bot.core.service.TotalDataStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 数据存储健康探针测试
 * <p>
 * 三档各一条：没配、配了连得上、配了连不上。<b>后两档必须分得开</b>——
 * 它们在「累计查询答不上来」这个现象上一模一样，而一个是正常部署形态，一个是真出了事。
 */
@DisplayName("数据存储健康探针")
class DataStorageHealthProbeTest {
    /**
     * 假连接工厂：{@code up} 为假时连不上
     * <p>
     * 判据要量的是本类怎么读累计存储的状态，与 Redis 本身无关，因此不装 Redis，
     * 也不引嵌入式实现——那会把一个跑不跑得起来取决于机器的东西塞进判据里。
     */
    private static RedisConnectionFactory factory(AtomicBoolean up) {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(factory.getConnection()).thenAnswer(invocation -> {
            if (!up.get()) {
                throw new IllegalStateException("连不上");
            }
            return connection;
        });
        return factory;
    }

    private static TotalDataStorage storage(String host, AtomicBoolean up) {
        return new TotalDataStorage(new TotalDataStorage.Settings(host, 6379, null, 0),
                settings -> factory(up), System::currentTimeMillis);
    }

    @Test
    @DisplayName("没配累计存储时报 OK，并说清去哪儿配、配完不用重启")
    void reportsLiveOnly() {
        HealthStatus status = new DataStorageHealthProbe(storage(null, new AtomicBoolean(true))).check();

        // 没配外部存储是正常的部署形态，不是故障——标红只会让人对告警麻木
        assertEquals(HealthStatus.Level.OK, status.level());
        assertTrue(status.summary().contains("只有本场数据"), status.summary());
        // 键名要出现在这句话里：使用者拿着它才搜得到设置页上那一项
        assertTrue(status.summary().contains("spring.data.redis.host"), status.summary());
        assertTrue(status.summary().contains("不用重启"), status.summary());
    }

    @Test
    @DisplayName("配了且连得上：说明两份数据都在，并写出连的是哪儿")
    void reportsTotalAvailable() {
        HealthStatus status = new DataStorageHealthProbe(storage("127.0.0.1", new AtomicBoolean(true))).check();

        assertEquals(HealthStatus.Level.OK, status.level());
        assertTrue(status.summary().contains("累计数据"), status.summary());
        assertTrue(status.summary().contains("127.0.0.1:6379"), status.summary());
    }

    @Test
    @DisplayName("⚠️ 配了却连不上：报降级，而不是跟没配时说同一句话")
    void reportsDegradedWhenUnreachable() {
        HealthStatus status = new DataStorageHealthProbe(storage("127.0.0.1", new AtomicBoolean(false))).check();

        assertEquals(HealthStatus.Level.DEGRADED, status.level());
        assertTrue(status.summary().contains("只有本场数据（累计存储连不上）"), status.summary());
        assertTrue(status.advice().contains("会自己恢复"), status.advice());
    }
}
