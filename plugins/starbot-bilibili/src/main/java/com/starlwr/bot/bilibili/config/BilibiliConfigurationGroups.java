package com.starlwr.bot.bilibili.config;

import com.starlwr.bot.core.config.ui.ConfigurationGroupContributor;
import com.starlwr.bot.core.config.ui.ConfigurationGroups;
import com.starlwr.bot.core.plugin.StarBotComponent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 哔哩哔哩插件申报给设置页的配置分组前缀
 * <p>
 * 此前这八条写死在核心的分组表里，于是没装本插件的实例也背着这些键名。
 * 装了本插件才把账号／直播／动态等前缀登记进对应的组；插件挪走，前缀跟着消失。
 */
@StarBotComponent
public class BilibiliConfigurationGroups implements ConfigurationGroupContributor {
    @Override
    public Map<String, ConfigurationGroups.Group> prefixes() {
        Map<String, ConfigurationGroups.Group> prefixes = new LinkedHashMap<>();
        prefixes.put("starbot.bilibili.account", ConfigurationGroups.COLLECT);
        prefixes.put("starbot.bilibili.live", ConfigurationGroups.COLLECT);
        prefixes.put("starbot.bilibili.dynamic", ConfigurationGroups.COLLECT);
        prefixes.put("starbot.bilibili.live.report-logo-path", ConfigurationGroups.REPORT);
        prefixes.put("starbot.bilibili.dynamic.logo-path", ConfigurationGroups.REPORT);
        prefixes.put("starbot.bilibili.debug", ConfigurationGroups.LOG_DEBUG);
        prefixes.put("starbot.bilibili.bilibili-thread", ConfigurationGroups.SERVICE);
        prefixes.put("starbot.bilibili.network", ConfigurationGroups.SERVICE);
        return prefixes;
    }
}
