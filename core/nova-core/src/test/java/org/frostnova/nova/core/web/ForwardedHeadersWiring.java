package org.frostnova.nova.core.web;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 照运行中的装法，看开着某一种 {@code server.forward-headers-strategy} 时装上的是哪道转发头过滤器
 * <p>
 * 装的是 Boot 自己的 Tomcat 网页服务装配，外加本程序 {@code web} 包里的各个装配类：
 * 顶替不顶替得了 Boot 那一道，由真的条件判断说了算，不由测试自己挑一个过滤器来用。
 */
public final class ForwardedHeadersWiring {
    private ForwardedHeadersWiring() {
    }

    /**
     * 本程序 {@code web} 包里名字以 Configuration 结尾的装配类，与运行中扫描到的是同一批
     */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = SecurityHeadersConfiguration.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = "org\\.frostnova\\.nova\\.core\\.web\\.[A-Za-z]+Configuration"))
    static class WebConfigurations {
    }

    /**
     * 装好之后看到的
     * @param filter 装上的转发头过滤器；没装时为 null
     */
    public record Installed(Filter filter) {
    }

    /**
     * 装一遍
     * @param properties 配置，形如 {@code server.forward-headers-strategy=framework}
     * @return 装上的东西
     */
    public static Installed install(String... properties) {
        AtomicReference<Installed> found = new AtomicReference<>();
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class,
                        TomcatServletWebServerAutoConfiguration.class))
                .withUserConfiguration(NovaCoreProperties.class, WebConfigurations.class)
                .withPropertyValues(properties)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    List<Filter> forwarded = context.getBeansOfType(FilterRegistrationBean.class).values().stream()
                            .map(registration -> (Filter) registration.getFilter())
                            .filter(filter -> filter instanceof ForwardedHeaderFilter)
                            .toList();
                    assertTrue(forwarded.size() <= 1, "转发头过滤器只能有一道，现在有 " + forwarded);
                    found.set(new Installed(forwarded.isEmpty() ? null : forwarded.get(0)));
                });
        return found.get();
    }

    /**
     * 开着 {@code framework} 时装上的那道转发头过滤器
     * @param extra 另外的配置
     * @return 过滤器
     */
    public static Filter framework(String... extra) {
        String[] properties = new String[extra.length + 1];
        properties[0] = "server.forward-headers-strategy=framework";
        System.arraycopy(extra, 0, properties, 1, extra.length);
        Filter filter = install(properties).filter();
        assertTrue(filter != null, "开着 framework 时应当装上一道转发头过滤器");
        return filter;
    }

    /**
     * 让请求过一遍这道过滤器，拿到排在它后面的那一环看到的请求
     * @param filter 过滤器；为 null 时原样交出
     * @param request 请求
     * @return 后面那一环看到的请求
     */
    public static HttpServletRequest through(Filter filter, MockHttpServletRequest request) throws Exception {
        if (filter == null) {
            return request;
        }
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (passed, ignored) -> seen.set((HttpServletRequest) passed));
        assertTrue(seen.get() != null, "过滤器没有把请求交给后面（回了 " + response.getStatus() + "）");
        return seen.get();
    }

    /**
     * 一个从某个对端连进来、带着几个头的请求
     * @param peer 直接连进来的对端
     * @param headers 头名与值，成对
     * @return 请求
     */
    public static MockHttpServletRequest request(String path, String peer, String... headers) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        request.setRemoteAddr(peer);
        for (int i = 0; i < headers.length; i += 2) {
            request.addHeader(headers[i], headers[i + 1]);
        }
        return request;
    }
}
