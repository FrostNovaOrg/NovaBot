package com.starlwr.bot.core.web;

import com.starlwr.bot.core.config.ui.ConfigUiController;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.List;

/**
 * 注册安全响应头过滤器
 * <p>
 * <b>不挂 {@code @ConditionalOnProperty}。</b>这几条头与控制台开没开无关：
 * 控制台关掉时 {@code /nova/readonly-token} 与推送接口照样在跑，它们的响应同样要带上。
 */
@Configuration
public class SecurityHeadersConfiguration {
    /**
     * 响应不许被存下来的命名空间
     * <p>
     * {@code /config} 是控制台（配置原文、令牌清单都在它下面），
     * {@code /nova} 下面是代签发只读口令——响应体里就是那把口令
     */
    private static final List<String> NO_STORE_PREFIXES =
            List.of(ConfigUiController.BASE_PATH, "/nova");

    @Bean
    public FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilterRegistration() {
        FilterRegistrationBean<SecurityHeadersFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new SecurityHeadersFilter(NO_STORE_PREFIXES));
        registration.addUrlPatterns("/*");
        // 🔴 要排在控制台安全过滤器（HIGHEST_PRECEDENCE + 1）**之前**：
        // 它拒绝时会直接把响应写完返回，排在它后面的话那些 401/403 就一条头都带不上，
        // 而「被拒绝的那次访问」正是最不该被缓存、最不该能被嵌进别人页面的那一次
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("securityHeadersFilter");

        return registration;
    }
}
