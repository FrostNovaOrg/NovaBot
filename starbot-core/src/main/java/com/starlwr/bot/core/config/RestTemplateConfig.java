package com.starlwr.bot.core.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * RestTemplate 配置
 */
@Configuration
public class RestTemplateConfig {
    private final StarBotCoreProperties properties;

    @Autowired
    public RestTemplateConfig(StarBotCoreProperties properties) {
        this.properties = properties;
    }

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.of(properties.getNetwork().getConnectTimeout(), ChronoUnit.SECONDS));
        factory.setReadTimeout(Duration.of(properties.getNetwork().getReadTimeout(), ChronoUnit.SECONDS));
        return new RestTemplate(factory);
    }
}
