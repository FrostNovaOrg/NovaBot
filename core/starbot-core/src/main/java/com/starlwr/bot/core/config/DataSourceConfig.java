package com.starlwr.bot.core.config;

import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.datasource.EmptyDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 数据源配置类
 * <p>
 * 数据源实现按 {@code spring.profiles.active} 选取（如 {@code json} 对应 {@code JsonDataSource}），
 * 选不到任何实现时回落到空数据源，程序仍能启动进配置界面，而不是直接起不来。
 */
@Configuration
public class DataSourceConfig {
    @Bean
    @ConditionalOnMissingBean(AbstractDataSource.class)
    public AbstractDataSource emptyDataSource(ApplicationEventPublisher publisher) {
        return new EmptyDataSource(publisher);
    }
}
