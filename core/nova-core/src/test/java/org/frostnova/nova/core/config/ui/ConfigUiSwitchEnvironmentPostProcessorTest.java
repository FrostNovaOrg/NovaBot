package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动期把控制台开关的写法规整成 true／false
 * <p>
 * 抓的用户故障：主人把 {@code novabot.core.config-ui.enabled} 写成 {@code 1}／{@code on}／{@code yes}
 * 想开（YAML 里写数字、环境变量里写 ON 都是这一档），装配条件两边都只认字面 true／false——
 * 开着的那一套（注册器等十八处）不装配，关着的那一套（关闭过滤器）也不装配，
 * 控制台全 404，日志里没有一句说为什么。写成 {@code 0}／{@code off}／{@code no} 想关的同病。
 * <p>
 * 后处理器必须真的登记在 {@code META-INF/spring.factories}，且在装配条件求值之前把值改写好——
 * 生产里这一步由 SpringApplication 在容器刷新前调用，这里照同一时序手动跑。
 */
@DisplayName("控制台开关启动期规整")
class ConfigUiSwitchEnvironmentPostProcessorTest {
    private static final String TYPE =
            "org.frostnova.nova.core.config.ui.ConfigUiSwitchEnvironmentPostProcessor";

    private static final String KEY = "novabot.core.config-ui.enabled";

    private static final String FACTORIES_KEY = "org.springframework.boot.EnvironmentPostProcessor";

    /**
     * 装上登记在 spring.factories 里的那只后处理器；装不上回 null，各格按未销办
     */
    private static EnvironmentPostProcessor load(RecordingLog log) {
        try {
            DeferredLogFactory logs = ignored -> log;
            Class<?> type = Class.forName(TYPE);
            return (EnvironmentPostProcessor) type.getConstructor(DeferredLogFactory.class).newInstance(logs);
        } catch (ReflectiveOperationException | ClassCastException absent) {
            return null;
        }
    }

    /**
     * 装配条件的探针：与生产十八处同一键、同一写法（开着那套认 true＋缺省即开，关着那套认 false）
     */
    @Configuration
    static class Probe {
        @Bean
        @ConditionalOnProperty(name = KEY, havingValue = "true", matchIfMissing = true)
        static String configUiOnSet() {
            return "on";
        }

        @Bean
        @ConditionalOnProperty(name = KEY, havingValue = "false")
        static String configUiOffSet() {
            return "off";
        }
    }

    /**
     * 原始写法进环境、后处理器照生产时序跑过、再按装配条件起一个真的容器
     */
    private static AnnotationConfigApplicationContext contextAfterNormalization(EnvironmentPostProcessor processor,
                                                                                String raw) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        if (raw != null) {
            context.getEnvironment().getPropertySources()
                    .addLast(new MapPropertySource("raw", Map.of(KEY, raw)));
        }
        if (processor != null) {
            processor.postProcessEnvironment(context.getEnvironment(), null);
        }
        context.register(Probe.class);
        context.refresh();
        return context;
    }

    @Test
    @DisplayName("各写法规整后各是什么：认 true/on/yes/1 与 false/off/no/0，没写与空值当默认开，认不出的按关")
    void normalizesEachSpelling() {
        List<String> bad = new ArrayList<>();
        EnvironmentPostProcessor processor = load(new RecordingLog());
        if (processor == null) {
            bad.add("后处理器不在或装不上");
        } else {
            Object[][] cases = {
                    {"true", "true"},
                    {"TRUE", "true"},
                    {" true ", "true"},
                    {"on", "true"},
                    {"ON", "true"},
                    {"yes", "true"},
                    {"1", "true"},
                    {"false", "false"},
                    {"OFF", "false"},
                    {"no", "false"},
                    {"0", "false"},
                    {"", "true"},
                    {"   ", "true"},
                    {"ture", "false"},
                    {"enabled", "false"},
                    {"2", "false"},
            };
            for (Object[] one : cases) {
                String raw = (String) one[0];
                String want = (String) one[1];
                try {
                    MockEnvironment env = new MockEnvironment();
                    env.getPropertySources().addLast(new MapPropertySource("file", Map.of(KEY, raw)));
                    processor.postProcessEnvironment(env, null);
                    assertEquals(want, env.getProperty(KEY), "输入「" + raw + "」");
                } catch (AssertionError | RuntimeException e) {
                    bad.add("「" + raw + "」→ " + want + ": " + e.getMessage());
                }
            }
            try {
                MockEnvironment env = new MockEnvironment();
                processor.postProcessEnvironment(env, null);
                assertNull(env.getProperty(KEY), "没写这个键时不能替它补值");
            } catch (AssertionError | RuntimeException e) {
                bad.add("没写: " + e.getMessage());
            }
        }
        assertTrue(bad.isEmpty(),
                "规整格未销 " + bad.size() + " 问: " + String.join("; ", bad));
    }

    @Test
    @DisplayName("认不出的值按关处理，并在日志里打一句点名键与原值、说清该写什么的错")
    void unknownValueLogsPlainError() {
        List<String> bad = new ArrayList<>();
        RecordingLog log = new RecordingLog();
        EnvironmentPostProcessor processor = load(log);
        if (processor == null) {
            bad.add("后处理器不在或装不上");
        } else {
            MockEnvironment env = new MockEnvironment();
            env.getPropertySources().addLast(new MapPropertySource("file", Map.of(KEY, "ture")));
            processor.postProcessEnvironment(env, null);
            String errors = String.join("\n", log.errors);
            try {
                assertTrue(errors.contains(KEY), "那句错没点名是哪个键:\n" + errors);
            } catch (AssertionError e) {
                bad.add(e.getMessage());
            }
            try {
                assertTrue(errors.contains("ture"), "那句错没带原值:\n" + errors);
            } catch (AssertionError e) {
                bad.add(e.getMessage());
            }
            try {
                assertTrue(errors.contains("关"), "那句错没说按关处理:\n" + errors);
            } catch (AssertionError e) {
                bad.add(e.getMessage());
            }
            try {
                assertTrue(errors.contains("true") && errors.contains("false"),
                        "那句错没告诉该写 true 还是 false:\n" + errors);
            } catch (AssertionError e) {
                bad.add(e.getMessage());
            }
        }
        assertTrue(bad.isEmpty(),
                "那句错的格未销 " + bad.size() + " 问: " + String.join("; ", bad));
    }

    @Test
    @DisplayName("装配层：写 1 开着那套在、关着那套不在；写 0 反过来")
    void normalizedValueDrivesAssembly() {
        EnvironmentPostProcessor processor = load(new RecordingLog());
        List<String> bad = new ArrayList<>();

        // 对照：没写与规范写法不需要后处理器也该对，这两格红说明量错了地方
        try (AnnotationConfigApplicationContext absent = contextAfterNormalization(processor, null);
             AnnotationConfigApplicationContext canonical = contextAfterNormalization(processor, "true")) {
            if (!(absent.containsBean("configUiOnSet") && !absent.containsBean("configUiOffSet"))) {
                bad.add("对照失效：没写时默认该是开");
            }
            if (!(canonical.containsBean("configUiOnSet") && !canonical.containsBean("configUiOffSet"))) {
                bad.add("对照失效：写 true 时该是开");
            }
        }

        if (processor == null) {
            bad.add("后处理器不在或装不上");
        } else {
            try (AnnotationConfigApplicationContext one = contextAfterNormalization(processor, "1")) {
                if (!(one.containsBean("configUiOnSet") && !one.containsBean("configUiOffSet"))) {
                    bad.add("写 1：想要开，实际开着那套" + (one.containsBean("configUiOnSet") ? "在" : "不在")
                            + "、关着那套" + (one.containsBean("configUiOffSet") ? "在" : "不在"));
                }
            }
            try (AnnotationConfigApplicationContext zero = contextAfterNormalization(processor, "0")) {
                if (!(zero.containsBean("configUiOffSet") && !zero.containsBean("configUiOnSet"))) {
                    bad.add("写 0：想要关，实际开着那套" + (zero.containsBean("configUiOnSet") ? "在" : "不在")
                            + "、关着那套" + (zero.containsBean("configUiOffSet") ? "在" : "不在"));
                }
            }
        }

        assertTrue(bad.isEmpty(),
                "装配格未销 " + bad.size() + " 问: " + String.join("; ", bad));
    }

    @Test
    @DisplayName("后处理器登记在 META-INF/spring.factories 的 EnvironmentPostProcessor 名下")
    void registeredInSpringFactories() throws Exception {
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
    }

    /**
     * 只记日志消息。后处理器跑在日志系统初始化之前，Boot 给的是延迟日志
     */
    private static final class RecordingLog implements org.apache.commons.logging.Log {
        final List<String> errors = new ArrayList<>();

        @Override public boolean isFatalEnabled() { return true; }
        @Override public boolean isErrorEnabled() { return true; }
        @Override public boolean isWarnEnabled() { return true; }
        @Override public boolean isInfoEnabled() { return true; }
        @Override public boolean isDebugEnabled() { return true; }
        @Override public boolean isTraceEnabled() { return true; }
        @Override public void fatal(Object message) { }
        @Override public void fatal(Object message, Throwable t) { }
        @Override public void error(Object message) { errors.add(String.valueOf(message)); }
        @Override public void error(Object message, Throwable t) { errors.add(String.valueOf(message)); }
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
