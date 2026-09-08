package com.starlwr.bot.core.config;

import com.starlwr.bot.core.properties.EventStreamProperties;
import com.starlwr.bot.core.properties.NovaBotPrefixes;
import com.starlwr.bot.core.protocol.NovaEventStreamConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置键产品前缀：只写现行键能绑上；只写上一档仍能绑上；两套同在时现行键胜
 */
@DisplayName("配置键产品前缀新旧两套")
class NovaBotPrefixAliasTest {
    @Test
    @DisplayName("只写 novabot.core 能绑到绑定根")
    void newCorePrefixBindsToRoot() {
        NovaCoreProperties properties = bindCore(Map.of(
                NovaBotPrefixes.CORE + ".log.event-log", "true"));
        assertTrue(properties.getLog().isEventLog(),
                "只写现行前缀时绑定根应吃到值");
    }

    @Test
    @DisplayName("只写旧 novabot.core 仍能绑到绑定根")
    void legacyCorePrefixStillBinds() {
        NovaCoreProperties properties = bindCore(Map.of(
                NovaBotPrefixes.CORE_LEGACY + ".log.event-log", "true"));
        assertTrue(properties.getLog().isEventLog(),
                "只写上一档前缀时绑定根仍应吃到值");
    }

    @Test
    @DisplayName("core 两套同在时现行键胜")
    void newCorePrefixWinsWhenBothPresent() {
        NovaCoreProperties properties = bindCore(Map.of(
                NovaBotPrefixes.CORE_LEGACY + ".log.event-log", "false",
                NovaBotPrefixes.CORE + ".log.event-log", "true"));
        assertTrue(properties.getLog().isEventLog(),
                "两套同在时应以现行键为准");
    }

    @Test
    @DisplayName("只写 novabot.core.event-stream 能绑到事件输出")
    void newEventStreamPrefixBinds() {
        EventStreamProperties properties = bindEventStream(Map.of(
                NovaBotPrefixes.EVENT_STREAM + ".enabled", "true"));
        assertTrue(properties.isEnabled(),
                "只写现行事件输出前缀时应启用");
    }

    @Test
    @DisplayName("只写旧 novabot.core.event-stream 仍能绑到事件输出")
    void legacyEventStreamPrefixStillBinds() {
        EventStreamProperties properties = bindEventStream(Map.of(
                NovaBotPrefixes.EVENT_STREAM_LEGACY + ".enabled", "true"));
        assertTrue(properties.isEnabled(),
                "只写上一档事件输出前缀时仍应启用");
    }

    @Test
    @DisplayName("event-stream 两套同在时现行键胜")
    void newEventStreamPrefixWinsWhenBothPresent() {
        EventStreamProperties properties = bindEventStream(Map.of(
                NovaBotPrefixes.EVENT_STREAM_LEGACY + ".enabled", "true",
                NovaBotPrefixes.EVENT_STREAM + ".enabled", "false"));
        assertFalse(properties.isEnabled(),
                "两套同在时应以现行键为准");
    }

    @Test
    @DisplayName("japan 形旧键：每个用到的前缀恰打一行读到旧键")
    void japanShapedYamlLogsOneLinePerPrefix() {
        List<String> logs = new ArrayList<>();
        MockEnvironment environment = environment(Map.ofEntries(
                Map.entry(NovaBotPrefixes.CORE_LEGACY + ".log.event-log", "true"),
                Map.entry(NovaBotPrefixes.EVENT_STREAM_LEGACY + ".enabled", "true"),
                Map.entry(NovaBotPrefixes.BILIBILI_LEGACY + ".account.anonymous", "true"),
                Map.entry(NovaBotPrefixes.ADAPTER_LEGACY + ".security.enabled", "false"),
                Map.entry(NovaBotPrefixes.ADAPTER_ALERT_LEGACY + ".num", "99"),
                Map.entry(NovaBotPrefixes.ADAPTER_NAPCAT_LEGACY + ".address", "http://127.0.0.1:1"),
                Map.entry(NovaBotPrefixes.NAPCAT_EXT_LEGACY + ".enable-backup-at-all", "false"),
                Map.entry("starbot.core.alert.qq-platform", "qq-onebot"),
                Map.entry("starbot.core.alert.qq-type", "1"),
                Map.entry("starbot.core.alert.qq-num", "88"),
                Map.entry("starbot.core.config-ui.napcat.address", "http://127.0.0.1:6099")));
        alias(logs).postProcessEnvironment(environment, null);

        List<String> rename = logs.stream().filter(line -> line.contains("读到旧键")).toList();
        assertEquals(NovaBotPrefixes.CURRENT_TO_LEGACY.size(), rename.size(),
                "每个前缀一行，不逐键刷屏，实有：" + rename);

        for (Map.Entry<String, String> pair : NovaBotPrefixes.CURRENT_TO_LEGACY) {
            String expected = "读到旧键 " + pair.getValue() + "，请迁到 " + pair.getKey();
            assertTrue(rename.contains(expected), "缺少：" + expected + " 实有：" + rename);
        }

        NovaCoreProperties core = new NovaCoreProperties();
        Binder.get(environment).bind(annotationPrefix(NovaCoreProperties.class),
                Bindable.ofInstance(core));
        assertTrue(core.getLog().isEventLog());
        assertTrue(new NovaEventStreamConfiguration().eventStreamProperties(environment).isEnabled());
    }

    @Test
    @DisplayName("别名处理器已登记进 EnvironmentPostProcessor")
    void aliasIsRegistered() throws Exception {
        String key = "org.springframework.boot.EnvironmentPostProcessor";
        List<String> declared = new ArrayList<>();
        var urls = getClass().getClassLoader().getResources("META-INF/spring.factories");
        while (urls.hasMoreElements()) {
            java.util.Properties props = new java.util.Properties();
            try (var in = urls.nextElement().openStream()) {
                props.load(in);
            }
            String value = props.getProperty(key);
            if (value == null) {
                continue;
            }
            for (String one : value.split(",")) {
                declared.add(one.trim());
            }
        }
        assertTrue(declared.contains(NovaBotPrefixAlias.class.getName()),
                "spring.factories 未列出前缀别名，实有：" + declared);
        int alias = declared.indexOf(NovaBotPrefixAlias.class.getName());
        int guard = declared.indexOf(DataLocationGuard.class.getName());
        assertTrue(alias >= 0 && guard >= 0 && alias < guard,
                "前缀别名须排在数据落点守卫之前，否则落点守卫读不到补进去的现行键");
    }

    private NovaCoreProperties bindCore(Map<String, Object> values) {
        MockEnvironment environment = environment(values);
        alias(new ArrayList<>()).postProcessEnvironment(environment, null);
        NovaCoreProperties properties = new NovaCoreProperties();
        Binder.get(environment).bind(annotationPrefix(NovaCoreProperties.class),
                Bindable.ofInstance(properties));
        return properties;
    }

    private EventStreamProperties bindEventStream(Map<String, Object> values) {
        MockEnvironment environment = environment(values);
        alias(new ArrayList<>()).postProcessEnvironment(environment, null);
        return new NovaEventStreamConfiguration().eventStreamProperties(environment);
    }

    private static MockEnvironment environment(Map<String, Object> values) {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("fixture", new LinkedHashMap<>(values)));
        return environment;
    }

    private static String annotationPrefix(Class<?> type) {
        return type.getAnnotation(ConfigurationProperties.class).prefix();
    }

    private static NovaBotPrefixAlias alias(List<String> logs) {
        DeferredLogFactory factory = type -> new CapturingLog(logs);
        return new NovaBotPrefixAlias(factory);
    }

    private record CapturingLog(List<String> sink) implements org.apache.commons.logging.Log {
        @Override public boolean isFatalEnabled() { return true; }
        @Override public boolean isErrorEnabled() { return true; }
        @Override public boolean isWarnEnabled() { return true; }
        @Override public boolean isInfoEnabled() { return true; }
        @Override public boolean isDebugEnabled() { return true; }
        @Override public boolean isTraceEnabled() { return true; }
        @Override public void fatal(Object message) { sink.add(String.valueOf(message)); }
        @Override public void fatal(Object message, Throwable t) { sink.add(String.valueOf(message)); }
        @Override public void error(Object message) { sink.add(String.valueOf(message)); }
        @Override public void error(Object message, Throwable t) { sink.add(String.valueOf(message)); }
        @Override public void warn(Object message) { sink.add(String.valueOf(message)); }
        @Override public void warn(Object message, Throwable t) { sink.add(String.valueOf(message)); }
        @Override public void info(Object message) { sink.add(String.valueOf(message)); }
        @Override public void info(Object message, Throwable t) { sink.add(String.valueOf(message)); }
        @Override public void debug(Object message) { sink.add(String.valueOf(message)); }
        @Override public void debug(Object message, Throwable t) { sink.add(String.valueOf(message)); }
        @Override public void trace(Object message) { sink.add(String.valueOf(message)); }
        @Override public void trace(Object message, Throwable t) { sink.add(String.valueOf(message)); }
    }
}
