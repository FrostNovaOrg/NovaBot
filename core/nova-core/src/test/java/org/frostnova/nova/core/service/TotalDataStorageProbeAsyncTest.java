package org.frostnova.nova.core.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 探活不在请求线程上做
 * <p>
 * 量的是「主机丢包」那一档的另一半：已有的判据量过连不上会降级、连回来自己恢复
 * （那要真探一趟才知道答案）；这里量的是<b>判定本身不能等网络</b>——
 * 地址不可达时建连要等满连接超时才死心，谁在请求线程上等这一趟，
 * 谁就把首页与菜单挂住整整一个超时。
 * <p>
 * 执行器用一个「收下任务但先不跑」的：调用返回时探活还没发生，
 * 这件事由此变成可以断言的事实——比拿秒表量耗时确定得多，慢机器上也不会偶尔红。
 */
@DisplayName("累计存储探活：请求线程只读缓存，不等网络")
class TotalDataStorageProbeAsyncTest {
    /**
     * 收下的探活任务：先不跑，判据自己决定什么时候放行
     */
    private final List<Runnable> queuedProbes = new ArrayList<>();

    /**
     * 手拨的钟：窗口期推进不靠等
     */
    private final AtomicLong now = new AtomicLong(1_000_000L);

    private TotalDataStorage storage;

    @BeforeEach
    void setUp() {
        Executor collector = queuedProbes::add;
        storage = new TotalDataStorage(TotalDataStorage.Settings.UNSET,
                settings -> factory(), now::get, collector);
    }

    /**
     * 假连接工厂：能连上即可，这里量的是「判定等不等网络」，不是「连没连上」
     */
    private RedisConnectionFactory factory() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(factory.getConnection()).thenReturn(connection);
        return factory;
    }

    @Test
    @DisplayName("⚠️ 判定不等网络：探活还没跑，调用就得回来，且答「不可用」")
    void callerDoesNotWaitForTheProbe() {
        storage.applyHost("127.0.0.1");

        assertFalse(storage.isAvailable(), "没探过就答可用，是把「不知道」说成了「很好」");
        assertEquals(1, queuedProbes.size(), "缓存里没有旧读数时该派一趟探活，而不是自己当场去连");

        // 放行那一趟——后台真跑完之后，同一窗口内的下一次问就该拿到真值
        queuedProbes.remove(0).run();
        assertTrue(storage.isAvailable(), "探活已跑完，仍答不可用的话，「总数据」永远上不了菜单");
    }

    @Test
    @DisplayName("阴性：一趟探活没跑完时不重复派，跑完且窗口过了才派下一趟")
    void doesNotQueueASecondProbeWhileOneIsInFlight() {
        storage.applyHost("127.0.0.1");

        for (int i = 0; i < 10; i++) {
            storage.isAvailable();
        }
        assertEquals(1, queuedProbes.size(), "同一趟还没跑完，十次问只该派一趟——主机丢包时排队的都是注定白等的");

        queuedProbes.remove(0).run();

        now.addAndGet(TotalDataStorage.PROBE_CACHE_MILLIS + 1);
        storage.isAvailable();
        assertEquals(1, queuedProbes.size(), "上一趟已跑完、窗口已过，该再派一趟——否则掉线永远不会被发现");
    }
}
