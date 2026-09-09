package org.frostnova.nova.adapter.onebot.config;

import org.frostnova.nova.core.config.ui.ConfigurationKeyAliasContributor;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.properties.NovaBotPrefixes;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OneBot 适配器申报的配置键改名：告警目标三项与代登录四项的现行位置 → 旧位置
 */
@NovaComponent
public class OneBotConfigurationKeyAliases implements ConfigurationKeyAliasContributor {
    @Override
    public Map<String, String> renamed() {
        Map<String, String> renamed = new LinkedHashMap<>();
        renamed.put(NovaBotPrefixes.ADAPTER, NovaBotPrefixes.ADAPTER_LEGACY);
        for (Map.Entry<String, String> relocated : NovaBotPrefixes.RELOCATED.entrySet()) {
            renamed.put(relocated.getValue(), relocated.getKey());
        }
        return renamed;
    }
}
