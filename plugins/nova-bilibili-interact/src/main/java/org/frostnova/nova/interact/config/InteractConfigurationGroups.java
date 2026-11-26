package org.frostnova.nova.interact.config;

import org.frostnova.nova.core.config.ui.ConfigurationGroupContributor;
import org.frostnova.nova.core.config.ui.ConfigurationGroups;
import org.frostnova.nova.core.plugin.NovaComponent;

import java.util.Map;

/**
 * 直播间互动插件申报给设置页的配置分组前缀
 * <p>
 * 发言账号凭据那两项、弹幕那三项与监听账号的账号配置同在「采集」组：都是「这台机器用哪个号连哔哩哔哩、怎么用」。
 * 插件挪走，前缀跟着消失。
 */
@NovaComponent
public class InteractConfigurationGroups implements ConfigurationGroupContributor {
    /**
     * 本插件的配置前缀
     */
    public static final String PREFIX = "novabot.bilibili.interact";

    @Override
    public Map<String, ConfigurationGroups.Group> prefixes() {
        return Map.of(PREFIX, ConfigurationGroups.COLLECT);
    }
}
