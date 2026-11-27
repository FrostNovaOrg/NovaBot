package org.frostnova.nova.core.web;

import jakarta.servlet.DispatcherType;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.server.autoconfigure.servlet.ForwardedHeaderFilterCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * 开着 {@code server.forward-headers-strategy=framework} 时，装本程序自己的转发头过滤器
 * <p>
 * Boot 装的那一道带着「没有别的转发头过滤器时才装」的条件，这里登记了一道，它就不装了。
 * 登记的写法照 Boot 那一道：同一个名字、排在最前、管普通请求、异步与出错转发三种，
 * Boot 给转发头过滤器的调整（例如 Tomcat 的相对跳转）照样用上。
 * <p>
 * 只在 {@code framework} 时装：没开这一项、或用 {@code native}（交给 Tomcat 处理）时，程序这边一道也不加，
 * 那两种情形的行为不变。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "server.forward-headers-strategy", havingValue = "framework")
public class ForwardedHeadersConfiguration {
    /**
     * 登记的类型写成 {@code FilterRegistrationBean<ForwardedHeaderFilter>}，是有意的：Boot 那道的条件
     * 按这个确切的类型找已有的登记，写成子类就认不出、两道一起装（同名登记会让启动直接失败）
     */
    @Bean
    public FilterRegistrationBean<ForwardedHeaderFilter> forwardedHeaderFilter(
            NovaCoreProperties properties, ObjectProvider<ForwardedHeaderFilterCustomizer> customizers) {
        TrustedProxyForwardedHeaderFilter filter =
                new TrustedProxyForwardedHeaderFilter(properties.getConfigUi().getTrustedProxies());
        customizers.orderedStream().forEach(customizer -> customizer.customize(filter));
        FilterRegistrationBean<ForwardedHeaderFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("forwardedHeaderFilter");
        return registration;
    }
}
