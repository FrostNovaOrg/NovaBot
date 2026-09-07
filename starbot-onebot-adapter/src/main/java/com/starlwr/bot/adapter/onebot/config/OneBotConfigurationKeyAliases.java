package com.starlwr.bot.adapter.onebot.config;

import com.starlwr.bot.core.config.ui.ConfigurationKeyAliasContributor;
import com.starlwr.bot.core.plugin.StarBotComponent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OneBot 适配器申报的配置键改名：告警目标三项与代登录四项的现行位置 → 旧位置
 */
@StarBotComponent
public class OneBotConfigurationKeyAliases implements ConfigurationKeyAliasContributor {
    @Override
    public Map<String, String> renamed() {
        Map<String, String> renamed = new LinkedHashMap<>();
        renamed.put("starbot.adapter.onebot.alert.platform", "starbot.core.alert.qq-platform");
        renamed.put("starbot.adapter.onebot.alert.type", "starbot.core.alert.qq-type");
        renamed.put("starbot.adapter.onebot.alert.num", "starbot.core.alert.qq-num");
        renamed.put("starbot.adapter.onebot.napcat.token", "starbot.core.config-ui.napcat.token");
        renamed.put("starbot.adapter.onebot.napcat.token-hash", "starbot.core.config-ui.napcat.token-hash");
        renamed.put("starbot.adapter.onebot.napcat.totp-secret", "starbot.core.config-ui.napcat.totp-secret");
        renamed.put("starbot.adapter.onebot.napcat.address", "starbot.core.config-ui.napcat.address");
        return renamed;
    }
}
