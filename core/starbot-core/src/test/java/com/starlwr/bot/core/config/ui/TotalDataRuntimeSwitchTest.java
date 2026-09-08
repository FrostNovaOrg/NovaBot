package com.starlwr.bot.core.config.ui;

import com.starlwr.bot.core.config.NovaCoreProperties;
import com.starlwr.bot.core.service.CompositeLiveDataService;
import com.starlwr.bot.core.service.DefaultLiveDataService;
import com.starlwr.bot.core.service.TotalDataStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 累计存储运行时判定与自动恢复
 * <p>
 * 量的是三件事，而这三件此前都要靠重启才办得到：
 * <ol>
 *     <li>没配时判定为假，且说得出该去配哪个键</li>
 *     <li>运行中填上地址，<b>同一个进程</b>当场认账</li>
 *     <li>连不上就降级、连回来自己恢复——两个方向都量</li>
 * </ol>
 * <p>
 * 不装 Redis、不引嵌入式实现：这几件事跟 Redis 本身没关系，跟「本进程怎么换后端、
 * 怎么判定此刻可不可用」有关系。连接工厂换成假的，反倒能把「连不上」这一档量出来——
 * 真 Redis 上要量它得先把它杀掉。
 */
@DisplayName("累计存储运行时判定")
class TotalDataRuntimeSwitchTest {
    private static final String PLATFORM = "bilibili";

    private static final long UID = 12345L;

    /**
     * 假 Redis 此刻通不通
     */
    private final AtomicInteger connections = new AtomicInteger();

    private volatile boolean up = true;

    /**
     * 手拨的钟：探活带缓存，「缓存到期之后才重新探」这件事用真钟量得靠等，
     * 等出来的判据在慢机器上会偶尔红——那种红比不量还糟
     */
    private final AtomicLong now = new AtomicLong(1_000_000L);

    private DefaultLiveDataService live;

    private TotalDataStorage storage;

    private CompositeLiveDataService service;

    @BeforeEach
    void setUp() {
        live = new DefaultLiveDataService(new NovaCoreProperties());
        storage = new TotalDataStorage(TotalDataStorage.Settings.UNSET, settings -> factory(), now::get);
        service = new CompositeLiveDataService(live, storage);
    }

    /**
     * 假连接工厂：{@code up} 为假时 PING 不通，并数一数总共取了几次连接
     */
    private RedisConnectionFactory factory() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(factory.getConnection()).thenAnswer(invocation -> {
            connections.incrementAndGet();
            if (!up) {
                throw new IllegalStateException("连不上");
            }
            return connection;
        });
        return factory;
    }

    /**
     * 把钟拨过探活缓存的窗口
     */
    private void afterProbeWindow() {
        now.addAndGet(TotalDataStorage.PROBE_CACHE_MILLIS + 1);
    }

    @Test
    @DisplayName("没配累计存储：判定为假，而且是「没配」不是「连不上」")
    void unconfiguredMeansUnsupported() {
        assertFalse(storage.isConfigured(), "一个地址都没填，不该算配过");
        assertFalse(service.supportsTotalData());
        assertEquals("", storage.describeTarget());
        assertEquals(0, connections.get(), "没配的时候不该去连任何东西");
    }

    @Test
    @DisplayName("⚠️ 运行中填上 Redis 地址，保存那一步就该把它落到跑着的程序上")
    void appliesRedisHostAtRuntime() {
        RuntimeConfigurationApplier applier =
                RuntimeConfigurationApplier.bench(new NovaCoreProperties()).totalDataStorage(storage).build();

        List<String> restart = applier.applyAndTrack(Map.of("spring.data.redis.host", "127.0.0.1"));

        assertEquals(List.of(), restart, "填上地址之后还要重启一次，等于「配好即用」这句话没兑现");
        assertTrue(service.supportsTotalData(), "地址已落地，累计数据此刻就该可用");
        assertEquals("127.0.0.1:6379", storage.describeTarget());
    }

    @Test
    @DisplayName("⚠️ 换的是后端不是进程：本场数据原封不动，接着采")
    void switchingBackendDoesNotRestartAnything() {
        live.incrementLiveMetric(PLATFORM, UID, "danmu", 3);
        assertFalse(service.supportsTotalData(), "先立住不可用这一档");

        storage.applyHost("127.0.0.1");

        assertTrue(service.supportsTotalData());
        // 本场数据活在内存里，重启一次就没了。切换前写进去的那一笔还在，
        // 才说得上「换的是后端不是进程」——这句话不能靠对象没换过来证，那是恒真的
        assertEquals(3.0, service.getLiveMetric(PLATFORM, UID, "danmu"),
                "本场数据在切换前后必须一个不差——它正是重启会丢的那一份");
        live.incrementLiveMetric(PLATFORM, UID, "danmu", 1);
        assertEquals(4.0, service.getLiveMetric(PLATFORM, UID, "danmu"), "切换之后还得接着采得下去");
    }

    @Test
    @DisplayName("⚠️ 连不上就降级，连回来自己恢复：两个方向都得走得通")
    void degradesAndRecoversOnItsOwn() {
        storage.applyHost("127.0.0.1");
        assertTrue(service.supportsTotalData(), "先立住可用这一档");

        up = false;
        afterProbeWindow();
        assertFalse(service.supportsTotalData(), "连不上却还宣称可用，查出来会是一片 0");
        assertTrue(storage.isConfigured(), "连不上不等于没配过——两者的说法完全不同");

        up = true;
        afterProbeWindow();
        assertTrue(service.supportsTotalData(), "连回来之后该自己恢复，不该等一次重启");
    }

    @Test
    @DisplayName("⚠️ 连不上时累计查询一律回默认值，不去问那个连不上的后端")
    void unavailableTotalQueriesFallBack() {
        storage.applyHost("127.0.0.1");
        up = false;
        afterProbeWindow();

        assertEquals(0.0, service.getTotalMetric(PLATFORM, UID, "danmu"));
        assertEquals(0.0, service.getTotalUserMetric(PLATFORM, UID, "danmu", 999L));
        assertEquals(List.of(), service.getTotalUserRanking(PLATFORM, UID, "danmu", 10));
        assertEquals(0, service.getTotalUserRank(PLATFORM, UID, "danmu", 999L));
        assertEquals(0, service.getTotalMetricUserCount(PLATFORM, UID, "danmu"));
    }

    @Test
    @DisplayName("探活带缓存：一窗之内只问一趟，窗口过了才重新问")
    void probeIsCachedWithinTheWindow() {
        storage.applyHost("127.0.0.1");

        connections.set(0);
        for (int i = 0; i < 20; i++) {
            assertTrue(service.supportsTotalData());
        }
        assertEquals(1, connections.get(), "每问一次判定就连一趟，等于把网络往返挂在开菜单这条路上");

        afterProbeWindow();
        assertTrue(service.supportsTotalData());
        assertEquals(2, connections.get(), "窗口过了还不重新探，掉线就永远不会被发现");
    }

    @Test
    @DisplayName("地址改成空的：判定回到「没配」，旧后端不再被问")
    void clearingHostTurnsItOff() {
        storage.applyHost("127.0.0.1");
        assertTrue(service.supportsTotalData());

        storage.applyHost("");

        assertFalse(storage.isConfigured());
        assertFalse(service.supportsTotalData());
    }

    @Test
    @DisplayName("⚠️ 运行中改库号，保存那一步就该落到跑着的程序上")
    void appliesRedisDatabaseAtRuntime() {
        storage.applyHost("127.0.0.1");
        RuntimeConfigurationApplier applier =
                RuntimeConfigurationApplier.bench(new NovaCoreProperties()).totalDataStorage(storage).build();

        List<String> restart = applier.applyAndTrack(Map.of("spring.data.redis.database", "3"));

        assertEquals(List.of(), restart,
                "库号与地址那三项一起改时，只有它说要重启——那句话读起来像半件事没办完");
        assertEquals("127.0.0.1:6379/3", storage.describeTarget(),
                "库号该跟着一起就地换后端，而不是换上去的还是旧库号");
    }

    @Test
    @DisplayName("阴性：端口写成一句话时按需重启处理，不当作已生效")
    void unparsablePortCountsAsRestartRequired() {
        RuntimeConfigurationApplier applier =
                RuntimeConfigurationApplier.bench(new NovaCoreProperties()).totalDataStorage(storage).build();

        List<String> restart = applier.applyAndTrack(Map.of("spring.data.redis.port", "六三七九"));

        assertEquals(List.of("spring.data.redis.port"), restart);
    }
}
