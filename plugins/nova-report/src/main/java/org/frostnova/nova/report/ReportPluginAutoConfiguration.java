package org.frostnova.nova.report;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.Ordered;

/**
 * 本插件对 Spring 的自报：「我在这里，扫我」
 * <p>
 * 五个插件模块各有一份同形的自报类。为什么需要它、为什么它要排在最后装、
 * 为什么要排除自身，一并写在 {@code OneBotAdapterPluginAutoConfiguration} 的类注释里，
 * 此处不复述。
 */
@AutoConfiguration
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
@ComponentScan(
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = ReportPluginAutoConfiguration.class))
public class ReportPluginAutoConfiguration {
}
