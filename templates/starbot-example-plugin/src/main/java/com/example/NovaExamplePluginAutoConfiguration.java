package com.example;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.Ordered;

/**
 * 理由见 OneBotAdapterPluginAutoConfiguration
 * <p>
 * 排除自身：否则配置类经 .imports 与扫描双注册
 */
@AutoConfiguration
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
@ComponentScan(
        basePackages = "com.example",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = NovaExamplePluginAutoConfiguration.class))
public class NovaExamplePluginAutoConfiguration {
}
