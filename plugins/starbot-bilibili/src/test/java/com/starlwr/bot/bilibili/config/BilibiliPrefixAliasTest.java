package com.starlwr.bot.bilibili.config;

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
 * 哔哩哔哩配置前缀：现行 novabot.bilibili 与上一档 novabot.bilibili
 */
@DisplayName("哔哩哔哩配置键产品前缀")
class BilibiliPrefixAliasTest {
    @Test
    @DisplayName("只写 novabot.bilibili 能绑上")
    void newPrefixBinds() {
        NovaBilibiliProperties properties = bind(Map.of(
                NovaBotPrefixes.BILIBILI + ".account.anonymous", "true"));
        assertTrue(properties.getAccount().isAnonymous());
    }

    @Test
    @DisplayName("只写旧 novabot.bilibili 仍能绑上")
    void legacyPrefixStillBinds() {
        NovaBilibiliProperties properties = bind(Map.of(
                NovaBotPrefixes.BILIBILI_LEGACY + ".account.anonymous", "true"));
        assertTrue(properties.getAccount().isAnonymous());
    }

    @Test
    @DisplayName("两套同在时现行键胜")
    void newPrefixWinsWhenBothPresent() {
        NovaBilibiliProperties properties = bind(Map.of(
                NovaBotPrefixes.BILIBILI_LEGACY + ".account.anonymous", "true",
                NovaBotPrefixes.BILIBILI + ".account.anonymous", "false"));
        assertFalse(properties.getAccount().isAnonymous());
    }

    private NovaBilibiliProperties bind(Map<String, Object> values) {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("fixture", new LinkedHashMap<>(values)));
        DeferredLogFactory factory = type -> org.apache.commons.logging.LogFactory.getLog("noop");
        new NovaBotPrefixAlias(factory).postProcessEnvironment(environment, null);
        NovaBilibiliProperties properties = new NovaBilibiliProperties();
        Binder.get(environment).bind(
                NovaBilibiliProperties.class.getAnnotation(ConfigurationProperties.class).prefix(),
                Bindable.ofInstance(properties));
        return properties;
    }
}
