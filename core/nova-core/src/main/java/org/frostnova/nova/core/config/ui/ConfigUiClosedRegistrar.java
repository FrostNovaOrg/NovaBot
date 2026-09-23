package org.frostnova.nova.core.config.ui;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 关闭过滤器的登记器——只在控制台开关为 {@code false} 时装配
 *
 * <h2>为什么另立一个登记器</h2>
 * {@link ConfigUiRegistrar} 是开关开着时的那一套（页面、安全过滤器、会话）。
 * 开关关掉时要装的是另一件事：把 {@code /config} 一律打成 404。
 * 两套装配条件互斥，分放两个配置类，条件各写各的注解，不用在一处读配置分叉。
 *
 * <h2>顺序与安全过滤器同档</h2>
 * 都排在安全响应头那道之后（见 {@link ConfigUiRegistrar#FILTER_ORDER} 的注释）：
 * 本过滤器拒绝时会直接把响应写完返回，排在它后面的响应头补不上去。
 * 开着时装的是安全过滤器、关着时装的是本件，两者不会同时在场。
 */
@Configuration
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "false")
public class ConfigUiClosedRegistrar {

    /**
     * 与安全过滤器同档：安全响应头那道仍排在前面。
     */
    public static final int FILTER_ORDER = ConfigUiRegistrar.FILTER_ORDER;

    @Bean
    public FilterRegistrationBean<ConfigUiClosedFilter> configUiClosedFilterRegistration() {
        FilterRegistrationBean<ConfigUiClosedFilter> registration =
                new FilterRegistrationBean<>(new ConfigUiClosedFilter());
        registration.addUrlPatterns(ConfigUiController.BASE_PATH, ConfigUiController.BASE_PATH + "/*");
        registration.setOrder(FILTER_ORDER);
        registration.setName("configUiClosedFilter");
        return registration;
    }
}
