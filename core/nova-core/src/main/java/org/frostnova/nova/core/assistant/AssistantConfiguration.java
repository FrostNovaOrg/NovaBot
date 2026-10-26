package org.frostnova.nova.core.assistant;

import org.frostnova.nova.core.config.RestTemplateConfig;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

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
}
