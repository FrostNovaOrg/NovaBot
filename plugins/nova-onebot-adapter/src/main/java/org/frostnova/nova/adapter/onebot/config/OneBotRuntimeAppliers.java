package org.frostnova.nova.adapter.onebot.config;

import org.frostnova.nova.adapter.onebot.service.OneBotConnectionManager;
import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplierContributor;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.properties.NovaBotPrefixes;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * OneBot 适配器申报的即时生效应用器：告警目标三项写回运行中的适配器属性
 */
@NovaComponent
public class OneBotRuntimeAppliers implements RuntimeConfigurationApplierContributor {
    private final OneBotAdapterPluginProperties properties;

    private final ObjectProvider<OneBotConnectionManager> connections;

    public OneBotRuntimeAppliers() {
        this(new OneBotAdapterPluginProperties());
    }

    public OneBotRuntimeAppliers(OneBotAdapterPluginProperties properties) {
        this(properties, null);
    }

    @Autowired
    public OneBotRuntimeAppliers(OneBotAdapterPluginProperties properties, ObjectProvider<OneBotConnectionManager> connections) {
        this.connections = connections;
        this.properties = properties;
    }

    @Override
    public Map<String, Consumer<String>> appliers() {
        Map<String, Consumer<String>> appliers = new LinkedHashMap<>();
        appliers.put("novabot.adapter.onebot.alert.platform",
                value -> properties.getAlert().setPlatform(value));
        appliers.put("novabot.adapter.onebot.alert.type",
                value -> properties.getAlert().setType(Integer.parseInt(value.trim())));
        appliers.put("novabot.adapter.onebot.alert.num",
                value -> properties.getAlert().setNum(
                        value == null || value.isBlank() ? null : Long.parseLong(value.trim())));
        appliers.put(OneBotAdapterPluginProperties.NapCat.ENABLED_KEY, value -> {
            boolean enabled = value.isBlank() || Boolean.parseBoolean(value);
            if (connections == null) properties.getNapcat().setEnabled(enabled);
            else connections.getObject().setChannelEnabled(enabled);
        });
        return appliers;
    }

    @Override
    public Map<String, Function<String, String>> valueValidators() {
        return Map.of(OneBotAdapterPluginProperties.NapCat.ENABLED_KEY, value ->
                "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value) ? null : "请选择启用或停用");
    }

    @Override
    public Map<String, String> appliedElsewhere() {
        Map<String, String> elsewhere = new LinkedHashMap<>();
        elsewhere.put(NovaBotPrefixes.ADAPTER + ".senders",
                "/api/setup/bot 保存时经 BotConnectionTester#apply 当场重建连接");
        return elsewhere;
    }
}
