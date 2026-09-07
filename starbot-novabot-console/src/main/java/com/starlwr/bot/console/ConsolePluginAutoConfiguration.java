package com.starlwr.bot.console;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * 本插件对 Spring 的自报：「我在这里，扫我」
 * <p>
 * 五个插件模块各有一份同形的自报类。为什么需要它、为什么当前版本它是不生效的、
 * 为什么要排除自身，一并写在 {@code OneBotAdapterPluginAutoConfiguration} 的类注释里，
 * 此处不复述。
 */
@AutoConfiguration
@ComponentScan(
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = ConsolePluginAutoConfiguration.class))
public class ConsolePluginAutoConfiguration {
}
