package com.starlwr.bot.adapter.onebot.config;

import com.starlwr.bot.core.plugin.StarBotComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * StarBotOneBotAdapterPlugin 线程池配置类
 */
@Slf4j
@StarBotComponent
public class OneBotThreadPoolConfig {
    private final OneBotAdapterPluginProperties properties;

    @Autowired
    public OneBotThreadPoolConfig(OneBotAdapterPluginProperties properties) {
        this.properties = properties;
    }

    @Bean
    public ThreadPoolTaskExecutor oneBotThreadPool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getWebsocketThread().getCorePoolSize());
        executor.setMaxPoolSize(properties.getWebsocketThread().getMaxPoolSize());
        executor.setQueueCapacity(properties.getWebsocketThread().getQueueCapacity());
        executor.setKeepAliveSeconds(properties.getWebsocketThread().getKeepAliveSeconds());
        executor.setThreadNamePrefix("onebot-thread-");
        executor.setRejectedExecutionHandler(new OneBotWithLogCallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    private static class OneBotWithLogCallerRunsPolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            if (executor.isShutdown()) {
                return;
            }
            log.warn("OneBot 线程池资源已耗尽, 请考虑增加线程池大小!");
            r.run();
        }
    }
}
