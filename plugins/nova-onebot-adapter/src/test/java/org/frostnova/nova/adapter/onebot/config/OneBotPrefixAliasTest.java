package org.frostnova.nova.adapter.onebot.config;

import org.frostnova.nova.core.config.NovaBotPrefixAlias;
import org.frostnova.nova.core.properties.NovaBotPrefixes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OneBot 配置前缀：适配器、告警、NapCat 各一对新旧键
 */
@DisplayName("OneBot 配置键产品前缀")
class OneBotPrefixAliasTest {
    @Test
    @DisplayName("只写 novabot.adapter.onebot 能绑上")
    void newAdapterPrefixBinds() {
        OneBotAdapterPluginProperties properties = bind(Map.of(
                NovaBotPrefixes.ADAPTER + ".security.enabled", "false"));
        assertFalse(properties.getSecurity().isEnabled());
    }

    @Test
    @DisplayName("只写旧 novabot.adapter.onebot 仍能绑上")
    void legacyAdapterPrefixStillBinds() {
        OneBotAdapterPluginProperties properties = bind(Map.of(
                NovaBotPrefixes.ADAPTER_LEGACY + ".security.enabled", "false"));
        assertFalse(properties.getSecurity().isEnabled());
    }

    @Test
    @DisplayName("adapter 两套同在时现行键胜")
    void newAdapterPrefixWinsWhenBothPresent() {
        OneBotAdapterPluginProperties properties = bind(Map.of(
                NovaBotPrefixes.ADAPTER_LEGACY + ".security.enabled", "false",
                NovaBotPrefixes.ADAPTER + ".security.enabled", "true"));
        assertTrue(properties.getSecurity().isEnabled());
    }

    @Test
    @DisplayName("只写 novabot.adapter.onebot.alert 能绑上")
    void newAlertPrefixBinds() {
        OneBotAdapterPluginProperties properties = bind(Map.of(
                NovaBotPrefixes.ADAPTER_ALERT + ".num", "7"));
        assertEquals(7L, properties.getAlert().getNum());
    }

    @Test
    @DisplayName("只写旧 novabot.adapter.onebot.alert 仍能绑上")
    void legacyAlertPrefixStillBinds() {
        OneBotAdapterPluginProperties properties = bind(Map.of(
                NovaBotPrefixes.ADAPTER_ALERT_LEGACY + ".num", "7"));
        assertEquals(7L, properties.getAlert().getNum());
    }

    @Test
    @DisplayName("只写 novabot.adapter.onebot.napcat 能绑上")
    void newNapcatPrefixBinds() {
        OneBotAdapterPluginProperties properties = bind(Map.of(
                NovaBotPrefixes.ADAPTER_NAPCAT + ".address", "http://127.0.0.1:1"));
        assertEquals("http://127.0.0.1:1", properties.getNapcat().getAddress());
    }

    @Test
    @DisplayName("只写旧 novabot.adapter.onebot.napcat 仍能绑上")
    void legacyNapcatPrefixStillBinds() {
        OneBotAdapterPluginProperties properties = bind(Map.of(
                NovaBotPrefixes.ADAPTER_NAPCAT_LEGACY + ".address", "http://127.0.0.1:1"));
        assertEquals("http://127.0.0.1:1", properties.getNapcat().getAddress());
    }

    private OneBotAdapterPluginProperties bind(Map<String, Object> values) {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("fixture", new LinkedHashMap<>(values)));
        DeferredLogFactory factory = type -> org.apache.commons.logging.LogFactory.getLog("noop");
        new NovaBotPrefixAlias(factory).postProcessEnvironment(environment, null);
        OneBotAdapterPluginProperties properties = new OneBotAdapterPluginProperties();
        Binder.get(environment).bind(
                OneBotAdapterPluginProperties.class.getAnnotation(ConfigurationProperties.class).prefix(),
                Bindable.ofInstance(properties));
        return properties;
    }
}
