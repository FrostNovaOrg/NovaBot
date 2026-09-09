package org.frostnova.nova.adapter.onebot.config;

import org.frostnova.nova.core.config.ui.ConfigurationGroupContributor;
import org.frostnova.nova.core.config.ui.ConfigurationGroups;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.properties.NovaBotPrefixes;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OneBot 适配器申报给设置页的配置分组前缀
 * <p>
 * 此前这些前缀写死在核心的分组表里，于是没装本适配器的实例也背着这些键名。
 * 装了本适配器才把连接／安全／探测等前缀登记进对应的组；适配器挪走，前缀跟着消失。
 */
@NovaComponent
public class OneBotConfigurationGroups implements ConfigurationGroupContributor {
    @Override
    public Map<String, ConfigurationGroups.Group> prefixes() {
        Map<String, ConfigurationGroups.Group> prefixes = new LinkedHashMap<>();
        // 备用的全体提醒方案属于「怎么发」，不属于适配器的工程参数
        prefixes.put(NovaBotPrefixes.NAPCAT_EXT + ".enable-backup-at-all", ConfigurationGroups.PUSH);
        prefixes.put(NovaBotPrefixes.ADAPTER_ALERT, ConfigurationGroups.ALERT);
        prefixes.put(NovaBotPrefixes.ADAPTER_NAPCAT, ConfigurationGroups.SERVICE);
        // 适配器这几段逐段登记而不写一条 novabot.adapter.onebot 兜底：
        // 兜底会让此后每一个新加的适配器配置项自动落进折起来的高级区，且没有任何东西会提起这件事
        prefixes.put(NovaBotPrefixes.ADAPTER + ".base-url", ConfigurationGroups.SERVICE);
        prefixes.put(NovaBotPrefixes.ADAPTER + ".senders", ConfigurationGroups.SERVICE);
        prefixes.put(NovaBotPrefixes.ADAPTER + ".security", ConfigurationGroups.SERVICE);
        prefixes.put(NovaBotPrefixes.ADAPTER + ".websocket-thread", ConfigurationGroups.SERVICE);
        prefixes.put(NovaBotPrefixes.ADAPTER + ".detect", ConfigurationGroups.SERVICE);
        prefixes.put(NovaBotPrefixes.ADAPTER + ".extension", ConfigurationGroups.SERVICE);
        return prefixes;
    }
}
