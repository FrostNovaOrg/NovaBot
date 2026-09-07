package com.starlwr.bot.adapter.onebot.config;

import com.starlwr.bot.core.config.ui.ConfigurationGroupContributor;
import com.starlwr.bot.core.config.ui.ConfigurationGroups;
import com.starlwr.bot.core.plugin.StarBotComponent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OneBot 适配器申报给设置页的配置分组前缀
 * <p>
 * 此前这些前缀写死在核心的分组表里，于是没装本适配器的实例也背着这些键名。
 * 装了本适配器才把连接／安全／探测等前缀登记进对应的组；适配器挪走，前缀跟着消失。
 */
@StarBotComponent
public class OneBotConfigurationGroups implements ConfigurationGroupContributor {
    @Override
    public Map<String, ConfigurationGroups.Group> prefixes() {
        Map<String, ConfigurationGroups.Group> prefixes = new LinkedHashMap<>();
        // 备用的全体提醒方案属于「怎么发」，不属于适配器的工程参数
        prefixes.put("starbot.adapter.onebot.extension.napcat.enable-backup-at-all", ConfigurationGroups.PUSH);
        prefixes.put("starbot.adapter.onebot.alert", ConfigurationGroups.ALERT);
        // 适配器这几段逐段登记而不写一条 starbot.adapter.onebot 兜底：
        // 兜底会让此后每一个新加的适配器配置项自动落进折起来的高级区，且没有任何东西会提起这件事
        prefixes.put("starbot.adapter.onebot.base-url", ConfigurationGroups.SERVICE);
        prefixes.put("starbot.adapter.onebot.senders", ConfigurationGroups.SERVICE);
        prefixes.put("starbot.adapter.onebot.security", ConfigurationGroups.SERVICE);
        prefixes.put("starbot.adapter.onebot.websocket-thread", ConfigurationGroups.SERVICE);
        prefixes.put("starbot.adapter.onebot.detect", ConfigurationGroups.SERVICE);
        prefixes.put("starbot.adapter.onebot.extension", ConfigurationGroups.SERVICE);
        return prefixes;
    }
}
