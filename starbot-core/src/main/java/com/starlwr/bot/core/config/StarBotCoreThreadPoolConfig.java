package com.starlwr.bot.core.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * StarBotCore 线程池配置类
 */
@Slf4j
@Configuration
public class StarBotCoreThreadPoolConfig {
    private final StarBotCoreProperties properties;

    @Autowired
    public StarBotCoreThreadPoolConfig(StarBotCoreProperties properties) {
        this.properties = properties;
    }

    @Bean
    public ThreadPoolTaskExecutor networkThreadPool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getNetworkThread().getCorePoolSize());
        executor.setMaxPoolSize(properties.getNetworkThread().getMaxPoolSize());
        executor.setQueueCapacity(properties.getNetworkThread().getQueueCapacity());
        executor.setKeepAliveSeconds(properties.getNetworkThread().getKeepAliveSeconds());
        executor.setThreadNamePrefix("network-thread-");
        executor.setRejectedExecutionHandler(new NetworkWithLogCallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    private static class NetworkWithLogCallerRunsPolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            if (executor.isShutdown()) {
                return;
            }
            log.warn("网络请求线程池资源已耗尽, 请考虑增加线程池大小!");
            r.run();
        }
    }
}
