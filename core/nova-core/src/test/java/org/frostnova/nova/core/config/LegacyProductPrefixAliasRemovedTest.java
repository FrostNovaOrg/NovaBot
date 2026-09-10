package org.frostnova.nova.core.config;

import org.frostnova.nova.core.properties.NovaBotPrefixes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 5.4 起旧产品前缀不再在启动时映射到现行键。
 */
@DisplayName("旧产品前缀兼容层已撤")
class LegacyProductPrefixAliasRemovedTest {
    private static final String ALIAS = "org.frostnova.nova.core.config.NovaBotPrefixAlias";

    private static final String LEGACY_KEY = "starbot.core.log.event-log";

    private static final String CURRENT_KEY = NovaBotPrefixes.CORE + ".log.event-log";

    @Test
    @DisplayName("登记表不含别名处理器，且只写旧键跑过本产品 EPP 后现行键仍缺")
    void aliasGoneAndLegacyKeyStaysUnmapped() throws Exception {
        List<String> red = new ArrayList<>();
        List<String> declared = declaredProcessors();

        try {
            assertFalse(declared.contains(ALIAS),
                    "spring.factories 的 EnvironmentPostProcessor 仍含 " + ALIAS + "，实有：" + declared);
        } catch (AssertionError e) {
            red.add("① " + e.getMessage());
        }

        MockEnvironment environment = new MockEnvironment();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(LEGACY_KEY, "true");
        environment.getPropertySources().addFirst(new MapPropertySource("fixture", values));
        runProductProcessors(declared, environment);

        try {
            assertFalse(environment.containsProperty(CURRENT_KEY),
                    "只写 " + LEGACY_KEY + " 时现行键 " + CURRENT_KEY + " 不应出现, 实值="
                            + environment.getProperty(CURRENT_KEY));
        } catch (AssertionError e) {
            red.add("② " + e.getMessage());
        }

        if (!red.isEmpty()) {
            fail("阴性格 " + red.size() + " 问未销: " + String.join("; ", red));
        }
    }

    private static List<String> declaredProcessors() throws Exception {
        String key = "org.springframework.boot.EnvironmentPostProcessor";
        List<String> declared = new ArrayList<>();
        var urls = LegacyProductPrefixAliasRemovedTest.class.getClassLoader()
                .getResources("META-INF/spring.factories");
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
                String name = one.trim();
                if (!name.isEmpty()) {
                    declared.add(name);
                }
            }
        }
        assertTrue(declared.contains(DataLocationGuard.class.getName()),
                "spring.factories 未列出数据落点守卫，实有：" + declared);
        return declared;
    }

    private static void runProductProcessors(List<String> declared, MockEnvironment environment)
            throws Exception {
        DeferredLogFactory logs = type -> new NoOpLog();
        for (String name : declared) {
            if (!name.startsWith("org.frostnova.nova.")) {
                continue;
            }
            Class<?> type = Class.forName(name);
            Object instance;
            try {
                instance = type.getConstructor(DeferredLogFactory.class).newInstance(logs);
            } catch (NoSuchMethodException e) {
                instance = type.getDeclaredConstructor().newInstance();
            }
            if (instance instanceof EnvironmentPostProcessor processor) {
                processor.postProcessEnvironment(environment, null);
            }
        }
    }

    private record NoOpLog() implements org.apache.commons.logging.Log {
        @Override public boolean isFatalEnabled() { return true; }
        @Override public boolean isErrorEnabled() { return true; }
        @Override public boolean isWarnEnabled() { return true; }
        @Override public boolean isInfoEnabled() { return true; }
        @Override public boolean isDebugEnabled() { return true; }
        @Override public boolean isTraceEnabled() { return true; }
        @Override public void fatal(Object message) { }
        @Override public void fatal(Object message, Throwable t) { }
        @Override public void error(Object message) { }
        @Override public void error(Object message, Throwable t) { }
        @Override public void warn(Object message) { }
        @Override public void warn(Object message, Throwable t) { }
        @Override public void info(Object message) { }
        @Override public void info(Object message, Throwable t) { }
        @Override public void debug(Object message) { }
        @Override public void debug(Object message, Throwable t) { }
        @Override public void trace(Object message) { }
        @Override public void trace(Object message, Throwable t) { }
    }
}
