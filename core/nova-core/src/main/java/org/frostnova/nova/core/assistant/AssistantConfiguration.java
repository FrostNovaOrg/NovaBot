package org.frostnova.nova.core.assistant;

import org.frostnova.nova.core.config.RestTemplateConfig;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplierContributor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AssistantProperties.class)
public class AssistantConfiguration {
    @Bean
    AssistantKeyStore assistantKeyStore() { return new AssistantKeyStore(Path.of(".")); }

    @Bean
    AssistantUsageStore assistantUsageStore() { return new AssistantUsageStore(Path.of("."), Clock.systemUTC()); }

    @Bean
    AssistantLocalStore assistantLocalStore() { return new AssistantLocalStore(Path.of("."), Clock.systemUTC()); }

    @Bean
    ExcludedViewersStore excludedViewersStore(AssistantProperties properties, NovaCoreProperties core) {
        Path parent = Path.of(core.getLive().getLiveDataPath()).getParent();
        return new ExcludedViewersStore(properties, parent == null ? Path.of(".") : parent, Clock.systemUTC());
    }

    @Bean
    AssistantClient assistantClient(AssistantProperties properties, AssistantKeyStore keys, AssistantUsageStore usage) {
        return new AssistantClient(properties, keys, usage,
                timeout -> RestTemplateConfig.buildTemplate(
                        timeout.compareTo(Duration.ofSeconds(10)) < 0 ? timeout : Duration.ofSeconds(10), timeout));
    }

    @Bean
    RuntimeConfigurationApplierContributor assistantBudgetValidation() {
        return new RuntimeConfigurationApplierContributor() {
            @Override
            public Map<String, Consumer<String>> appliers() {
                return Map.of();
            }

            @Override
            public Map<String, Function<String, String>> valueValidators() {
                return Map.of(
                        "novabot.assistant.weekly-budget-yuan", value -> validateBudget(value, AssistantProperties::setWeeklyBudgetYuan),
                        "novabot.assistant.streamer-daily-budget-yuan", value -> validateBudget(value, AssistantProperties::setStreamerDailyBudgetYuan),
                        "novabot.assistant.streamer-weekly-budget-yuan", value -> validateBudget(value, AssistantProperties::setStreamerWeeklyBudgetYuan));
            }
        };
    }

    private static String validateBudget(String value, BiConsumer<AssistantProperties, BigDecimal> setter) {
        if (value.isBlank()) return null;
        try {
            setter.accept(new AssistantProperties(), new BigDecimal(value.strip()));
            return null;
        } catch (NumberFormatException error) {
            return "助手金额上限请填写数字";
        } catch (IllegalArgumentException error) {
            return error.getMessage();
        }
    }
}
