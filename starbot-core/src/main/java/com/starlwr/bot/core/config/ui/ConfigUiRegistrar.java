package com.starlwr.bot.core.config.ui;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.util.IpMatcher;
import com.starlwr.bot.core.util.SecureToken;
import com.starlwr.bot.core.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * 配置界面注册器
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "starbot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class ConfigUiRegistrar {
    private final StarBotCoreProperties properties;

    private final WebServerApplicationContext webContext;

    /**
     * 实际生效的访问令牌
     */
    private final String token;

    public ConfigUiRegistrar(StarBotCoreProperties properties, WebServerApplicationContext webContext) {
        this.properties = properties;
        this.webContext = webContext;
        this.token = resolveToken(properties.getConfigUi());
    }

    /**
     * 解析访问令牌，未配置时自动生成
     * @param configUi 配置界面配置
     * @return 访问令牌
     */
    private String resolveToken(StarBotCoreProperties.ConfigUi configUi) {
        if (StringUtil.isBlank(configUi.getToken())) {
            return SecureToken.generate();
        }

        String configured = configUi.getToken().strip();
        if (SecureToken.isWeak(configured)) {
            log.error("配置界面的访问令牌强度过低, 请改用长度不低于 {} 位的随机字符串", SecureToken.MIN_LENGTH);
        }

        return configured;
    }

    /**
     * 注册配置界面安全过滤器
     * @return 过滤器注册信息
     */
    @Bean
    public FilterRegistrationBean<ConfigUiSecurityFilter> configUiSecurityFilterRegistration() {
        IpMatcher ipMatcher = new IpMatcher(properties.getConfigUi().getAllowIps());
        if (ipMatcher.isEmpty()) {
            log.error("配置界面的 IP 白名单为空, 所有访问都将被拒绝, 请检查 starbot.core.config-ui.allow-ips 配置");
        }

        FilterRegistrationBean<ConfigUiSecurityFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ConfigUiSecurityFilter(properties.getConfigUi(), token, ipMatcher));
        registration.addUrlPatterns(ConfigUiController.BASE_PATH, ConfigUiController.BASE_PATH + "/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        registration.setName("configUiSecurityFilter");

        return registration;
    }

    /**
     * 启动完毕后输出配置界面地址
     * <p>
     * 令牌只在启动日志中出现一次，使用者复制该地址即可直接进入界面，无需另行配置。
     */
    @Order(20000)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReadyEvent() {
        int port = webContext.getWebServer().getPort();
        String address = properties.getConfigUi().getAllowIps().stream().anyMatch(ip -> ip.startsWith("127."))
                ? "127.0.0.1"
                : "本机地址";

        log.info("配置界面已启动: http://{}:{}{}?token={}", address, port, ConfigUiController.BASE_PATH, token);
        log.info("该地址包含访问令牌, 请勿分享。令牌未在配置文件中显式设置时, 每次启动都会重新生成");
    }
}
