package com.starlwr.bot.bilibili.config;

import com.starlwr.bot.core.plugin.StarBotComponent;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * StarBotBilibili 线程池配置
 */
@StarBotComponent
public class StarBotBilibiliThreadPoolConfig {
    /**
     * 哔哩哔哩相关的定时任务调度器
     * <p>
     * 直播间心跳、重连、动态轮询与备用直播推送均在此调度器上执行。使用独立调度器而非共享
     * Spring Boot 的默认调度器，可避免这些高频任务与其他插件的定时任务互相阻塞。
     * @param properties 配置
     * @return 调度器
     */
    @Bean("bilibiliTaskScheduler")
    public ThreadPoolTaskScheduler bilibiliTaskScheduler(StarBotBilibiliProperties properties) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

        scheduler.setPoolSize(Math.max(2, properties.getBilibiliThread().getCorePoolSize()));
        scheduler.setThreadNamePrefix("bilibili-scheduler-");
        // 心跳与重连任务在停机时无需等待，直接中断以免拖慢关闭流程
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();

        return scheduler;
    }
}
