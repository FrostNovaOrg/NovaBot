package org.frostnova.nova.core.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 探活的接线本身有常驻判据兜住
 * <p>
 * 「请求线程不等探活」「连不上会降级」那些是<b>行为</b>的判据；这里量的是<b>接线</b>——
 * 后台定时那一趟真的挂上了、建连超时真的设上了。这两处都没有对外可见的后果
 * 直到出事那天：定时那一趟丢了，掉线要等下一次有人打开页面才被发现；
 * 建连超时回到客户端默认的 10 秒，主机丢包时后台探活线程一次占满 10 秒、
 * 掉线要两个窗口才说得清。行为判据量不到它们——把接线拆了，那些判据照绿。
 * <p>
 * 都不碰网络：假调度器收下任务不跑，真调度器收下的那趟由判据自己拨钟放行；
 * 连接工厂只读它的配置面，建连是惰性的，不 {@code start} 就不发生。
 */
@DisplayName("累计存储探活：接线本身（后台定时与建连超时）")
class TotalDataStorageWiringTest {
    /**
     * 假调度器收下的后台任务：先不跑，判据自己决定什么时候放行
     */
    private final List<Runnable> executed = new ArrayList<>();

    /**
     * 手拨的钟：窗口期推进不靠等
     */
    private final AtomicLong now = new AtomicLong(1_000_000L);

    private ScheduledExecutorService scheduler;

    private TotalDataStorage storage;

    private void wired() {
        scheduler = mock(ScheduledExecutorService.class);
        doAnswer(invocation -> {
            executed.add(invocation.getArgument(0));
            return null;
        }).when(scheduler).execute(any(Runnable.class));

        storage = new TotalDataStorage(TotalDataStorage.Settings.UNSET,
                settings -> factory(), now::get, scheduler);
    }

    /**
     * 假连接工厂：能连上即可，这里量的是接线，不是「连没连上」
     */
    private RedisConnectionFactory factory() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(factory.getConnection()).thenReturn(connection);
        return factory;
    }

    @Test
    @DisplayName("⚠️ 后台线程注册了定时刷新：窗口期为周期，且那一趟真会派探活")
    void daemonThreadRefreshesOnSchedule() {
        wired();

        // 注册本身：生产那一支把自己的后台线程当探活执行器传进来（它就是 ScheduledExecutorService，
        // 走的与这里同一条路），初始延迟与周期都是探活窗口期——
        // 定时那一行被去掉、或周期被改慢，掉线最迟多久被说出来就变了，这里红
        ArgumentCaptor<Runnable> timer = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleWithFixedDelay(timer.capture(),
                eq(TotalDataStorage.PROBE_CACHE_MILLIS), eq(TotalDataStorage.PROBE_CACHE_MILLIS),
                eq(TimeUnit.MILLISECONDS));

        // 注册的那趟不是空活：先把有人问时派的第一趟探掉，再拨过窗口、跑定时那一趟——
        // 没人问它也派了探活。refresh 里的派活被拆掉的话，这里红
        storage.applyHost("127.0.0.1");
        storage.isAvailable();
        executed.remove(0).run();

        now.addAndGet(TotalDataStorage.PROBE_CACHE_MILLIS + 1);
        timer.getValue().run();
        assertEquals(1, executed.size(),
                "没人问、窗口已过，定时那一趟该自己派探活——没有它，掉线要等下一次有人打开页面才发现");
    }

    @Test
    @DisplayName("⚠️ 连接工厂的建连超时与命令超时都收到 2 秒")
    void connectTimeoutIsTwoSeconds() {
        LettuceConnectionFactory factory = (LettuceConnectionFactory) TotalDataStorage.lettuce(
                new TotalDataStorage.Settings("127.0.0.1", 6379, null, 0));

        LettuceClientConfiguration config = factory.getClientConfiguration();
        assertEquals(Duration.ofSeconds(2), config.getCommandTimeout(),
                "命令超时回框架默认的 60 秒的话，连上了但卡住时一条命令还是能把人挂一分钟");
        assertEquals(Duration.ofSeconds(2),
                config.getClientOptions().orElseThrow().getSocketOptions().getConnectTimeout(),
                "建连超时回客户端默认的 10 秒的话，主机丢包时后台探活线程一次占满 10 秒，掉线要两个窗口才说得清");
    }
}
