package org.frostnova.nova.interact;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.Ordered;

/**
 * 本插件对 Spring 的自报：「我在这里，扫我」
 * <p>
 * 各插件模块有一份同形的自报类。为什么需要它、为什么它要排在最后装、
 * 为什么要排除自身，一并写在 {@code OneBotAdapterPluginAutoConfiguration} 的类注释里，
 * 此处不复述。
 * <p>
 * 包名不放在哔哩哔哩插件的包下面：那个插件按自己的包扫组件，落在它下面的类会被它也扫一遍。
 */
@AutoConfiguration
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
@ComponentScan(
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = InteractPluginAutoConfiguration.class))
public class InteractPluginAutoConfiguration {
}
