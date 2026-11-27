package org.frostnova.nova.core.assistant;

import org.frostnova.nova.core.config.ui.ConfigurationMetadataService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.frostnova.nova.core.properties.ConfigChoices;
import org.frostnova.nova.core.properties.ConfigEffect;
import org.frostnova.nova.core.properties.ConfigLabel;
import org.frostnova.nova.core.properties.ConfigLevel;
import org.frostnova.nova.core.properties.ConfigSummary;
import org.springframework.beans.BeanWrapperImpl;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.*;

class AssistantPropertiesTest {
    @ParameterizedTest @ValueSource(ints = {0, 1, 8, 9})
    void concurrentQuestionsBindWithinTheSupportedRange(int limit) {
        assertEquals(2, new AssistantProperties().getMaxConcurrentQuestions());
        var binder = new Binder(new MapConfigurationPropertySource(Map.of("novabot.assistant.max-concurrent-questions", limit)));
        var target = Bindable.of(AssistantProperties.class);
        if (limit == 0 || limit == 9) assertThrows(BindException.class, () -> binder.bind("novabot.assistant", target));
        else assertEquals(limit, binder.bind("novabot.assistant", target).get().getMaxConcurrentQuestions());
    }

    @Test void apiFormatDefaultsToChatAndOffersAdvancedChoices() {
        var field = assertDoesNotThrow(() -> AssistantProperties.class.getDeclaredField("apiFormat"));
        assertEquals("chat", new BeanWrapperImpl(new AssistantProperties()).getPropertyValue("apiFormat"));
        assertEquals("接口格式", field.getAnnotation(ConfigLabel.class).value());
        assertEquals(ConfigLevel.Level.ADVANCED, field.getAnnotation(ConfigLevel.class).value());
        assertEquals(ConfigEffect.Effect.IMMEDIATE, field.getAnnotation(ConfigEffect.class).value());
        assertArrayEquals(new String[]{"chat", "responses"}, field.getAnnotation(ConfigChoices.class).value());
    }

    @ParameterizedTest @ValueSource(strings = {"chat", "responses", "unknown"})
    void bindingAcceptsOnlyApiFormatChoices(String format) {
        var binder = new Binder(new MapConfigurationPropertySource(Map.of("novabot.assistant.api-format", format)));
        var target = Bindable.of(AssistantProperties.class);
        if ("unknown".equals(format)) assertThrows(BindException.class, () -> binder.bind("novabot.assistant", target));
        else assertEquals(format, new BeanWrapperImpl(binder.bind("novabot.assistant", target).get()).getPropertyValue("apiFormat"));
    }

    @Test void thinkingDefaultsAndChoicesAreAvailableToSettings() {
        var field = assertDoesNotThrow(() -> AssistantProperties.class.getDeclaredField("thinking"));
        assertEquals("off", new BeanWrapperImpl(new AssistantProperties()).getPropertyValue("thinking"));
        assertEquals("思考强度", field.getAnnotation(ConfigLabel.class).value());
        assertEquals(ConfigLevel.Level.COMMON, field.getAnnotation(ConfigLevel.class).value());
        assertEquals(ConfigEffect.Effect.IMMEDIATE, field.getAnnotation(ConfigEffect.class).value());
        assertArrayEquals(new String[]{"off", "low", "medium", "high", "max"}, field.getAnnotation(ConfigChoices.class).value());
        assertArrayEquals(new String[]{"关闭／模型默认", "低", "中", "高", "最高"}, field.getAnnotation(ConfigChoices.class).labels());
        assertTrue(field.getAnnotation(ConfigSummary.class).details());
        String help = new ConfigurationMetadataService().getFields().stream()
                .filter(setting -> "novabot.assistant.thinking".equals(setting.name()))
                .findFirst().orElseThrow().description();
        assertTrue(help.contains("8000 token"), help);
        assertTrue(help.contains("/responses"), help);
    }

    @ParameterizedTest @ValueSource(strings = {"off", "low", "medium", "high", "max"})
    void bindingAcceptsThinkingChoices(String thinking) {
        var binder = new Binder(new MapConfigurationPropertySource(Map.of("novabot.assistant.thinking", thinking)));
        var bound = binder.bind("novabot.assistant", Bindable.of(AssistantProperties.class));
        assertTrue(bound.isBound());
        assertEquals(thinking, new BeanWrapperImpl(bound.get()).getPropertyValue("thinking"));
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"unknown", "HIGH", " low "})
    void setterRejectsUnknownThinkingChoices(String thinking) throws Exception {
        var setter = assertDoesNotThrow(() -> AssistantProperties.class.getMethod("setThinking", String.class));
        var error = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> setter.invoke(new AssistantProperties(), thinking)).getCause();
        assertInstanceOf(IllegalArgumentException.class, error);
        assertTrue(error.getMessage().contains("思考强度"));
        assertTrue(error.getMessage().contains("关闭"));
        assertTrue(error.getMessage().contains("中（medium）"));
    }

    // 抓住配置绑定接受零或负数预算，导致金额上限无效。
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "-0.01"})
    void bindingRejectsNonPositiveWeeklyBudget(String budget) {
        var binder = new Binder(new MapConfigurationPropertySource(Map.of("novabot.assistant.weekly-budget-yuan", budget)));
        assertThrows(org.springframework.boot.context.properties.bind.BindException.class,
                () -> binder.bind("novabot.assistant", Bindable.of(AssistantProperties.class)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"streamer-daily-budget-yuan", "streamer-weekly-budget-yuan"})
    void streamerBudgetBindingAcceptsZeroButRejectsNegativeValues(String key) {
        String property = "novabot.assistant." + key;
        var unlimited = new Binder(new MapConfigurationPropertySource(Map.of(property, "0")));
        assertTrue(unlimited.bind("novabot.assistant", Bindable.of(AssistantProperties.class)).isBound());
        var negative = new Binder(new MapConfigurationPropertySource(Map.of(property, "-0.01")));
        assertThrows(org.springframework.boot.context.properties.bind.BindException.class,
                () -> negative.bind("novabot.assistant", Bindable.of(AssistantProperties.class)));
    }

    @Test
    void settingsValidationUsesTheSameBudgetRangesAsStartup() {
        var properties = new AssistantProperties();
        var excluded = new ExcludedViewersStore(properties, Path.of("."), Clock.systemUTC());
        var validators = new AssistantRuntimeConfiguration(properties, excluded).valueValidators();
        var total = validators.get("novabot.assistant.weekly-budget-yuan");
        assertTrue(total.apply("0").contains("大于 0"));
        assertTrue(total.apply("abc").contains("填写数字"));
        assertNull(total.apply("10.5"));
        for (String key : List.of("streamer-daily-budget-yuan", "streamer-weekly-budget-yuan")) {
            var validate = validators.get("novabot.assistant." + key);
            assertTrue(validate.apply("-0.01").contains("不能小于 0"));
            assertNull(validate.apply("0"));
        }
    }
}
