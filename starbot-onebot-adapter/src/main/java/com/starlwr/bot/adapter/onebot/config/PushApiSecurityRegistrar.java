package com.starlwr.bot.adapter.onebot.config;

import com.starlwr.bot.core.util.IpMatcher;
import com.starlwr.bot.adapter.onebot.security.PushApiSecurityFilter;
import com.starlwr.bot.adapter.onebot.security.PushApiTokenStore;
import com.starlwr.bot.adapter.onebot.security.RateLimiter;
import com.starlwr.bot.core.plugin.StarBotComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * 推送接口安全组件注册器
 */
@Slf4j
@StarBotComponent
public class PushApiSecurityRegistrar {
    /**
     * 推送接口 Token 存储
     * @return Token 存储
     */
    @Bean
    public PushApiTokenStore pushApiTokenStore() {
        return new PushApiTokenStore();
    }

    /**
     * 来源 IP 白名单匹配器
     * @param properties 配置
     * @return IP 匹配器
     */
    @Bean
    public IpMatcher pushApiIpMatcher(OneBotAdapterPluginProperties properties) {
        IpMatcher matcher = new IpMatcher(properties.getSecurity().getAllowIps());

        if (matcher.isEmpty()) {
            log.error("推送接口 IP 白名单为空, 所有请求都将被拒绝, 请检查 starbot.adapter.onebot.security.allow-ips 配置");
        }

        return matcher;
    }

    /**
     * 推送接口频率限制器
     * @param properties 配置
     * @return 频率限制器
     */
    @Bean
    public RateLimiter pushApiRateLimiter(OneBotAdapterPluginProperties properties) {
        OneBotAdapterPluginProperties.Security.RateLimit rateLimit = properties.getSecurity().getRateLimit();
        return new RateLimiter(rateLimit.getPermitsPerMinute(), rateLimit.getBurst());
    }

    /**
     * 注册推送接口安全过滤器
     * <p>
     * 过滤器需排在处理链最前，确保鉴权发生在任何业务逻辑之前。
     * @param properties 配置
     * @param tokenStore Token 存储
     * @param ipMatcher IP 匹配器
     * @param rateLimiter 频率限制器
     * @return 过滤器注册信息
     */
    @Bean
    public FilterRegistrationBean<PushApiSecurityFilter> pushApiSecurityFilterRegistration(OneBotAdapterPluginProperties properties,
                                                                                          PushApiTokenStore tokenStore,
                                                                                          IpMatcher ipMatcher,
                                                                                          RateLimiter rateLimiter) {
        FilterRegistrationBean<PushApiSecurityFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new PushApiSecurityFilter(properties.getSecurity(), tokenStore, ipMatcher, rateLimiter));
        registration.addUrlPatterns(properties.getBaseUrl() + "/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("pushApiSecurityFilter");

        if (!properties.getSecurity().isEnabled()) {
            registration.setEnabled(false);
            log.error("推送接口安全校验已被关闭, 任何能访问到本服务端口的程序都可以借由 StarBot 发送消息, 请仅在完全可信的隔离网络中使用此配置");
        }

        return registration;
    }
}
