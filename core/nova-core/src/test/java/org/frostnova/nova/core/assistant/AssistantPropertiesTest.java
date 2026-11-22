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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AssistantPropertiesTest {
    @Test void apiFormatDefaultsToChatAndOffersAdvancedChoices() {
        var field = assertDoesNotThrow(() -> AssistantProperties.class.getDeclaredField("apiFormat"));
        assertEquals("chat", new BeanWrapperImpl(new AssistantProperties()).getPropertyValue("apiFormat"));
        assertEquals("接口格式", field.getAnnotation(ConfigLabel.class).value());
        assertEquals(ConfigLevel.Level.ADVANCED, field.getAnnotation(ConfigLevel.class).value());
        assertEquals(ConfigEffect.Effect.RESTART, field.getAnnotation(ConfigEffect.class).value());
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
        assertEquals(ConfigEffect.Effect.RESTART, field.getAnnotation(ConfigEffect.class).value());
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
    void bindingRejectsNonPositiveBudget(String budget) {
        var binder = new Binder(new MapConfigurationPropertySource(Map.of("novabot.assistant.monthly-budget-yuan", budget)));
        assertThrows(org.springframework.boot.context.properties.bind.BindException.class,
                () -> binder.bind("novabot.assistant", Bindable.of(AssistantProperties.class)));
    }
}
