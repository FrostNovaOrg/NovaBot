package com.starlwr.bot.core.timeline;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Duration;

/**
 * 把这台机器自己的起停记进时间线
 * <p>
 * 「昨晚八点到十点为什么一条推送都没有」——最常见的答案是那两个小时它根本没在跑，
 * 而时间线上此前没有任何东西说得出这件事：进程不在的时候，没有谁能往里写。
 * 起停两条一记，那段空白就有了解释；没有这两条的话，「停过」与「在跑但没事发生」
 * 在日志页上长得一模一样。
 * <p>
 * <b>单独一个类而不是挂在别的启动件上。</b>核心里已有七八处听
 * {@link ApplicationReadyEvent} 的组件（清残留、查版本、下依赖……），
 * 顺手挂进其中任何一个，都会让「记起停」这件事的存亡取决于那个组件的去留——
 * 而那个组件被删掉的那天，少的只是时间线上一类记录，没有任何红会替人发现。
 */
@Component
public class SystemTimelineRecorder {
    private final TimelineWriter timeline;

    @Autowired
    public SystemTimelineRecorder(TimelineWriter timeline) {
        this.timeline = timeline;
    }

    /**
     * 启动完成
     * <p>
     * 排在最后（{@link Order} 取最大）：这一条要说的是「起来了」，
     * 而别的启动件还在跑的时候它并没有起完。
     */
    @Order(Integer.MAX_VALUE)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReadyEvent() {
        timeline.record(TimelineEvent.of(TimelineEventType.SYSTEM_STARTED, TimelineEvent.Level.INFO)
                .text("NovaBot 已启动")
                .detail("startup_ms", String.valueOf(startupMillis()))
                .build());
    }

    /**
     * 开始退出
     * <p>
     * 排在最前（{@link Order} 取最小）：往时间线上写要经磁盘，而别的组件正在这一轮里
     * 关线程池、断连接。等它们走完再写，写的那一下就可能撞上一个已经关掉的东西。
     */
    @Order(Integer.MIN_VALUE)
    @EventListener(ContextClosedEvent.class)
    public void onContextClosedEvent() {
        timeline.record(TimelineEvent.of(TimelineEventType.SYSTEM_STOPPING, TimelineEvent.Level.INFO)
                .text("NovaBot 正在退出")
                .detail("uptime_s", String.valueOf(uptime().toSeconds()))
                .build());
    }

    /**
     * 从进程起来到此刻用了多久，毫秒
     * <p>
     * 「今天怎么起了三分钟」这种问题得有一个数才答得了，而它只有起来的那一刻知道。
     */
    private static long startupMillis() {
        return uptime().toMillis();
    }

    private static Duration uptime() {
        return Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime());
    }
}
