package com.starlwr.bot.core.config;

import com.starlwr.bot.core.multicaster.InterruptibleEventMulticaster;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 事件配置类
 */
@Configuration
public class EventConfig {
    @Bean
    public InterruptibleEventMulticaster applicationEventMulticaster() {
        return new InterruptibleEventMulticaster();
    }
}
