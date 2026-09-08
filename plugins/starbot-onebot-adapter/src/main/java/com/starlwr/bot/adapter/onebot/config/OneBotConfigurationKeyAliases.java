package com.starlwr.bot.adapter.onebot.config;

import com.starlwr.bot.core.config.ui.ConfigurationKeyAliasContributor;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.properties.NovaBotPrefixes;

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
        renamed.put(NovaBotPrefixes.ADAPTER, NovaBotPrefixes.ADAPTER_LEGACY);
        renamed.put(NovaBotPrefixes.ADAPTER_ALERT + ".platform", "starbot.core.alert.qq-platform");
        renamed.put(NovaBotPrefixes.ADAPTER_ALERT + ".type", "starbot.core.alert.qq-type");
        renamed.put(NovaBotPrefixes.ADAPTER_ALERT + ".num", "starbot.core.alert.qq-num");
        renamed.put(NovaBotPrefixes.ADAPTER_NAPCAT + ".token", "starbot.core.config-ui.napcat.token");
        renamed.put(NovaBotPrefixes.ADAPTER_NAPCAT + ".token-hash", "starbot.core.config-ui.napcat.token-hash");
        renamed.put(NovaBotPrefixes.ADAPTER_NAPCAT + ".totp-secret", "starbot.core.config-ui.napcat.totp-secret");
        renamed.put(NovaBotPrefixes.ADAPTER_NAPCAT + ".address", "starbot.core.config-ui.napcat.address");
        return renamed;
    }
}
