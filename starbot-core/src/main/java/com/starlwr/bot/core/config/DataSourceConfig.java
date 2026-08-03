package com.starlwr.bot.core.config;

import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.datasource.EmptyDataSource;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 数据源配置类
 */
@Profile("!mysql")
@Configuration
@EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
public class DataSourceConfig {
    @Bean
    @ConditionalOnMissingBean(AbstractDataSource.class)
    public AbstractDataSource emptyDataSource(ApplicationEventPublisher publisher) {
        return new EmptyDataSource(publisher);
    }
}
