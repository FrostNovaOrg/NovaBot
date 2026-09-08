package com.starlwr.bot.bilibili.config;

import com.starlwr.bot.core.config.ui.ConfigurationKeyAliasContributor;
import com.starlwr.bot.core.plugin.NovaComponent;
import com.starlwr.bot.core.properties.NovaBotPrefixes;

import java.util.Map;

/**
 * 哔哩哔哩插件申报的配置键改名：现行 {@code novabot.bilibili} → 上一档 {@code starbot.bilibili}
 */
@NovaComponent
public class BilibiliConfigurationKeyAliases implements ConfigurationKeyAliasContributor {
    @Override
    public Map<String, String> renamed() {
        return Map.of(NovaBotPrefixes.BILIBILI, NovaBotPrefixes.BILIBILI_LEGACY);
    }
}
