package com.starlwr.bot.bilibili.protocol;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.config.StarBotBilibiliThreadPoolConfig;
import com.starlwr.bot.core.config.EventStreamProperties;
import com.starlwr.bot.core.event.live.common.WatchedUpdateEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.protocol.NovaEventStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件输出的房间统计定时任务，最后落在哪一台调度器上
 *
 * <h2>这一格补的是什么洞</h2>
 * 哔哩哔哩这一侧有九处按名字要 {@code bilibiliTaskScheduler}，其中八处把它存成字段，
 * 真起一次照字段逐个读就能核对。<b>{@link NovaEventBroadcaster} 是第九处，
 * 它在构造器里就地用掉、不留字段</b>——扫字段的量具在它这里读不到任何东西，
 * 而「读不到」与「读到的是对的」在报表上长得一模一样。所以这一处一直是没被量过，
 * 不是查过了。
 *
 * <h2>为什么这台挑错了要紧</h2>
 * 独立调度器的用处是隔离：直播间心跳、重连、动态轮询都在哔哩哔哩这台上跑，
 * 与核心那台默认调度器互不阻塞。事件输出的房间统计是每 5 秒一次的高频任务，
 * 落到默认那台上不会报错、日志里也看不出来，只是从此与核心的定时任务挤在一起。
 *
 * <h2>怎么量的</h2>
 * 按<b>线程名前缀</b>断言：容器里同时摆上两台候选（哔哩哔哩那台由产品代码
 * {@link StarBotBilibiliThreadPoolConfig} 自己造，前缀不写死在本件里），
 * 让容器按注入点上的名字挑，再看那个定时任务真正跑在谁的线程上。
 * 事件输出默认关闭时它一个任务都不排，因此这里显式开启——否则这一格量的是空气。
 */
@DisplayName("事件输出房间统计的调度器落点")
class NovaEventBroadcasterSchedulerTargetTest {
    private static final String PLATFORM = "bilibili";

    private AnnotationConfigApplicationContext context;

    private DefaultListableBeanFactory beans;

    private ThreadPoolTaskScheduler bilibiliScheduler;

    private ThreadPoolTaskScheduler defaultScheduler;

    private RecordingStream stream;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext();
        beans = context.getDefaultListableBeanFactory();

        bilibiliScheduler = new StarBotBilibiliThreadPoolConfig()
                .bilibiliTaskScheduler(new StarBotBilibiliProperties());

        // 另一台只要「不是哔哩哔哩那台」即可，前缀取一个与产品代码不重的名字
        defaultScheduler = new ThreadPoolTaskScheduler();
        defaultScheduler.setPoolSize(1);
        defaultScheduler.setThreadNamePrefix("gauge-default-scheduler-");
        defaultScheduler.initialize();

        stream = new RecordingStream();

        EventStreamProperties properties = new EventStreamProperties();
        properties.setEnabled(true);

        beans.registerSingleton("bilibiliTaskScheduler", bilibiliScheduler);
        beans.registerSingleton("taskScheduler", defaultScheduler);
        beans.registerSingleton("eventStreamProperties", properties);
        beans.registerSingleton("novaEventStream", stream);

        registerAsComponentScanDoes(NovaEventBroadcaster.class);
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
        if (bilibiliScheduler != null) {
            bilibiliScheduler.shutdown();
        }
        if (defaultScheduler != null) {
            defaultScheduler.shutdown();
        }
    }

    @Test
    @DisplayName("🔴 房间统计跑在哔哩哔哩那台调度器的线程上")
    void roomStatFlushRunsOnBilibiliScheduler() throws InterruptedException {
        context.refresh();

        NovaEventBroadcaster broadcaster = context.getBean(NovaEventBroadcaster.class);
        // 攒一条房间统计。没有脏数据时那个定时任务空转一圈就返回，一条都不推，
        // 也就没有线程名可读——这一句是让它有话说
        broadcaster.onEvent(new WatchedUpdateEvent(PLATFORM, room(), 100, "100人看过"));

        assertTrue(stream.published.await(20, TimeUnit.SECONDS),
                "等了 20 秒没等到房间统计被推出去：那个定时任务没排上，或是排在了一台没跑的调度器上");

        String thread = stream.thread.get();
        assertNotNull(thread, "推是推出来了，却没记下线程名");
        assertTrue(thread.startsWith(bilibiliScheduler.getThreadNamePrefix()),
                "房间统计跑在 " + thread + " 上，不是哔哩哔哩那台调度器");
        assertFalse(thread.startsWith(defaultScheduler.getThreadNamePrefix()),
                "房间统计跑到默认调度器上了，隔离没了");
        assertEquals(0L, defaultScheduler.getScheduledThreadPoolExecutor().getCompletedTaskCount(),
                "默认调度器上跑过任务：事件输出往那台上排了东西");
    }

    @Test
    @DisplayName("🔴 上一格的前提：容器里确实有两台候选，且两台的线程名前缀不同")
    void bothSchedulersAreCandidates() {
        context.refresh();

        assertEquals(2, beans.getBeanNamesForType(TaskScheduler.class).length,
                "容器里不再是两台候选，上一格挑不挑得对就没有读数了");
        assertFalse(bilibiliScheduler.getThreadNamePrefix().equals(defaultScheduler.getThreadNamePrefix()),
                "两台的线程名前缀一样，按前缀断言的那一格从此恒真");
    }

    /**
     * 照组件扫描那条路注册一个组件类
     */
    private void registerAsComponentScanDoes(Class<?> clazz) {
        AnnotatedGenericBeanDefinition definition = new AnnotatedGenericBeanDefinition(clazz);
        AnnotationConfigUtils.processCommonDefinitionAnnotations(definition);
        String name = AnnotationBeanNameGenerator.INSTANCE.generateBeanName(definition, beans);
        beans.registerBeanDefinition(name, definition);
    }

    private static LiveStreamerInfo room() {
        LiveStreamerInfo source = new LiveStreamerInfo();
        source.setRoomId(10000L);
        return source;
    }

    /**
     * 记下第一条消息是在哪个线程上推出来的
     */
    private static class RecordingStream extends NovaEventStream {
        private final CountDownLatch published = new CountDownLatch(1);

        private final AtomicReference<String> thread = new AtomicReference<>();

        RecordingStream() {
            super(64);
        }

        @Override
        public Frame publish(JSONObject envelope) {
            thread.compareAndSet(null, Thread.currentThread().getName());
            published.countDown();
            return super.publish(envelope);
        }
    }
}
