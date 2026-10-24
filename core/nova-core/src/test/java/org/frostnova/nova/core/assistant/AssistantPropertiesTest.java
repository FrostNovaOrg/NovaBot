package org.frostnova.nova.core.assistant;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

class AssistantPropertiesTest {
    // 抓住配置绑定接受零或负数预算，导致金额上限无效。
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "-0.01"})
    void bindingRejectsNonPositiveBudget(String budget) {
        var binder = new Binder(new MapConfigurationPropertySource(Map.of("novabot.assistant.monthly-budget-yuan", budget)));
        assertThrows(org.springframework.boot.context.properties.bind.BindException.class,
                () -> binder.bind("novabot.assistant", Bindable.of(AssistantProperties.class)));
    }
}
