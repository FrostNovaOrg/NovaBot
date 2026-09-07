package com.starlwr.bot.adapter.onebot.config;

import com.starlwr.bot.core.config.ui.ConfigurationKeyAliasContributor;
import com.starlwr.bot.core.plugin.StarBotComponent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OneBot 适配器申报的配置键改名：告警目标三项的现行位置 → 旧位置
 */
@StarBotComponent
public class OneBotConfigurationKeyAliases implements ConfigurationKeyAliasContributor {
    @Override
    public Map<String, String> renamed() {
        Map<String, String> renamed = new LinkedHashMap<>();
        renamed.put("starbot.adapter.onebot.alert.platform", "starbot.core.alert.qq-platform");
        renamed.put("starbot.adapter.onebot.alert.type", "starbot.core.alert.qq-type");
        renamed.put("starbot.adapter.onebot.alert.num", "starbot.core.alert.qq-num");
        return renamed;
    }
}
