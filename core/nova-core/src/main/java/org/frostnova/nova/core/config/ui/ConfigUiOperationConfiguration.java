package org.frostnova.nova.core.config.ui;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class ConfigUiOperationConfiguration {
    @Bean
    public FilterRegistrationBean<ConfigUiOperationFilter> configUiOperationFilter(ConfigUiOperationStore store) {
        var registration = new FilterRegistrationBean<>(new ConfigUiOperationFilter(store));
        registration.addUrlPatterns("/config/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
