package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动期把配置里的监听地址斜杠形态收成裸地址
 * <p>
 * Spring 绑定 {@code server.address} 走的不是配置文件服务，文件里若是
 * {@code InetAddress.toString()} 那种 {@code /127.0.0.1}，进程在任何 Bean 起来之前就死。
 * 后处理器必须真的登记在 {@code META-INF/spring.factories}，且实现的是 Boot 那一支接口。
 */
@DisplayName("监听地址启动期归一")
class ServerAddressEnvironmentPostProcessorTest {
    private static final String TYPE =
            "org.frostnova.nova.core.config.ui.ServerAddressEnvironmentPostProcessor";

    private static final String KEY = "server.address";

    private static final String FACTORIES_KEY = "org.springframework.boot.EnvironmentPostProcessor";

    @Test
    @DisplayName("斜杠形态收成裸地址，已经是裸地址的原样，并且后处理器已登记")
    void normalizesSlashFormsAndIsRegistered() throws Exception {
        List<String> bad = new ArrayList<>();

        try {
            assertEquals("127.0.0.1", InetAddressText.fromFile("/127.0.0.1"));
            assertEquals("127.0.0.1", InetAddressText.fromFile("localhost/127.0.0.1"));
            assertEquals("0:0:0:0:0:0:0:1", InetAddressText.fromFile("/0:0:0:0:0:0:0:1"));
            assertEquals("127.0.0.1", InetAddressText.fromFile("127.0.0.1"));
            assertEquals("0.0.0.0", InetAddressText.fromFile("0.0.0.0"));
            assertEquals("::1", InetAddressText.fromFile("::1"));
        } catch (AssertionError e) {
            bad.add("① 归一规则: " + e.getMessage());
        }

        EnvironmentPostProcessor processor = null;
        try {
            Class<?> type = Class.forName(TYPE);
            DeferredLogFactory logs = ignored -> new NoOpLog();
            Object instance = type.getConstructor(DeferredLogFactory.class).newInstance(logs);
            assertTrue(instance instanceof EnvironmentPostProcessor,
                    "必须实现 " + FACTORIES_KEY);
            processor = (EnvironmentPostProcessor) instance;
        } catch (ReflectiveOperationException | AssertionError e) {
            bad.add("② 装不上: " + e);
        }

        if (processor != null) {
            Object[][] cases = {
                    {"/127.0.0.1", "127.0.0.1"},
                    {"localhost/127.0.0.1", "127.0.0.1"},
                    {"/0:0:0:0:0:0:0:1", "0:0:0:0:0:0:0:1"},
                    {"127.0.0.1", "127.0.0.1"},
                    {"0.0.0.0", "0.0.0.0"},
                    {"::1", "::1"},
            };
            for (Object[] one : cases) {
                String raw = (String) one[0];
                String want = (String) one[1];
                try {
                    MockEnvironment env = new MockEnvironment();
                    env.getPropertySources().addLast(new MapPropertySource("file", Map.of(KEY, raw)));
                    processor.postProcessEnvironment(env, null);
                    assertEquals(want, env.getProperty(KEY), "输入 " + raw);
                } catch (AssertionError | RuntimeException e) {
                    bad.add("③ " + raw + " → " + want + ": " + e.getMessage());
                }
            }
        }

        if (processor != null) {
            try {
                MockEnvironment env = new MockEnvironment();
                env.getPropertySources().addLast(new MapPropertySource("file",
                        Map.of("management.server.address", "/127.0.0.1")));
                processor.postProcessEnvironment(env, null);
                assertEquals("127.0.0.1", env.getProperty("management.server.address"),
                        "management.server.address 斜杠形态应收成裸地址");
            } catch (AssertionError | RuntimeException e) {
                bad.add("⑤ management.server.address: " + e.getMessage());
            }
        }

        try {
            List<String> declared = new ArrayList<>();
            var urls = getClass().getClassLoader().getResources("META-INF/spring.factories");
            while (urls.hasMoreElements()) {
                Properties props = new Properties();
                try (var in = urls.nextElement().openStream()) {
                    props.load(in);
                }
                String value = props.getProperty(FACTORIES_KEY);
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
            assertTrue(declared.contains(TYPE),
                    "spring.factories 未列出后处理器，实有：" + declared);
            assertTrue(Class.forName(FACTORIES_KEY).isAssignableFrom(Class.forName(TYPE)),
                    "后处理器没有实现 " + FACTORIES_KEY);
        } catch (AssertionError | ReflectiveOperationException e) {
            bad.add("④ 登记: " + e.getMessage());
        }

        assertTrue(bad.isEmpty(),
                "后处理器格未销 " + bad.size() + " 问: " + String.join("; ", bad));
    }

    /**
     * 只把消息丢掉。后处理器跑在日志系统初始化之前，Boot 给的是延迟日志。
     */
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
