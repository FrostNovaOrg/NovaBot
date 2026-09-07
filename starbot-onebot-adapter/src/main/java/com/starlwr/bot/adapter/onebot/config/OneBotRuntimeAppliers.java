package com.starlwr.bot.adapter.onebot.config;

import com.starlwr.bot.core.config.ui.RuntimeConfigurationApplierContributor;
import com.starlwr.bot.core.plugin.StarBotComponent;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * OneBot 适配器申报的即时生效应用器：告警目标三项写回运行中的适配器属性
 */
@StarBotComponent
public class OneBotRuntimeAppliers implements RuntimeConfigurationApplierContributor {
    private final OneBotAdapterPluginProperties properties;

    public OneBotRuntimeAppliers() {
        this(new OneBotAdapterPluginProperties());
    }

    @Autowired
    public OneBotRuntimeAppliers(OneBotAdapterPluginProperties properties) {
        this.properties = properties;
    }

    @Override
    public Map<String, Consumer<String>> appliers() {
        Map<String, Consumer<String>> appliers = new LinkedHashMap<>();
        appliers.put("starbot.adapter.onebot.alert.platform",
                value -> properties.getAlert().setPlatform(value));
        appliers.put("starbot.adapter.onebot.alert.type",
                value -> properties.getAlert().setType(Integer.parseInt(value.trim())));
        appliers.put("starbot.adapter.onebot.alert.num",
                value -> properties.getAlert().setNum(
                        value == null || value.isBlank() ? null : Long.parseLong(value.trim())));
        return appliers;
    }
}
