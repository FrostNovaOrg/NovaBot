package com.starlwr.bot.adapter.onebot.extension.napcat.config;

import com.starlwr.bot.core.config.NovaBotPrefixAlias;
import com.starlwr.bot.core.properties.NovaBotPrefixes;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NapCat 扩展配置前缀：现行 novabot.adapter.onebot.extension.napcat
 */
@DisplayName("NapCat 扩展配置键产品前缀")
class NapcatExtensionPrefixAliasTest {
    @Test
    @DisplayName("只写 novabot.adapter.onebot.extension.napcat 能绑上")
    void newPrefixBinds() {
        OneBotAdapterNapcatExtensionPluginProperties properties = bind(Map.of(
                NovaBotPrefixes.NAPCAT_EXT + ".enable-backup-at-all", "false"));
        assertFalse(properties.isEnableBackupAtAll());
    }

    @Test
    @DisplayName("只写旧 novabot.adapter.onebot.extension.napcat 仍能绑上")
    void legacyPrefixStillBinds() {
        OneBotAdapterNapcatExtensionPluginProperties properties = bind(Map.of(
                NovaBotPrefixes.NAPCAT_EXT_LEGACY + ".enable-backup-at-all", "false"));
        assertFalse(properties.isEnableBackupAtAll());
    }

    @Test
    @DisplayName("两套同在时现行键胜")
    void newPrefixWinsWhenBothPresent() {
        OneBotAdapterNapcatExtensionPluginProperties properties = bind(Map.of(
                NovaBotPrefixes.NAPCAT_EXT_LEGACY + ".enable-backup-at-all", "false",
                NovaBotPrefixes.NAPCAT_EXT + ".enable-backup-at-all", "true"));
        assertTrue(properties.isEnableBackupAtAll());
    }

    private OneBotAdapterNapcatExtensionPluginProperties bind(Map<String, Object> values) {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("fixture", new LinkedHashMap<>(values)));
        DeferredLogFactory factory = type -> org.apache.commons.logging.LogFactory.getLog("noop");
        new NovaBotPrefixAlias(factory).postProcessEnvironment(environment, null);
        OneBotAdapterNapcatExtensionPluginProperties properties = new OneBotAdapterNapcatExtensionPluginProperties();
        Binder.get(environment).bind(
                OneBotAdapterNapcatExtensionPluginProperties.class.getAnnotation(ConfigurationProperties.class).prefix(),
                Bindable.ofInstance(properties));
        return properties;
    }
}
