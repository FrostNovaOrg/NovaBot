package org.frostnova.nova.adapter.qqbot;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.Ordered;

/**
 * QQ 官方机器人插件的自报类。
 * 装载顺序和扫描规则见 {@code org.frostnova.nova.adapter.onebot.OneBotAdapterPluginAutoConfiguration}。
 */
@AutoConfiguration
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
@ComponentScan(excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE, classes = QqBotPluginAutoConfiguration.class))
public class QqBotPluginAutoConfiguration {
    public static final String PLATFORM = "qq-official";
    static final String DISPLAY_NAME = "QQ 官方机器人";
}
