package com.starlwr.bot.core.config.ui;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.config.ui.auth.ConfigUiAuthService;
import com.starlwr.bot.core.config.ui.auth.ConfigUiSessionStore;
import com.starlwr.bot.core.config.ui.auth.LoginThrottle;
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

import java.time.Duration;

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
     * 登录会话存储
     */
    @Bean
    public ConfigUiSessionStore configUiSessionStore() {
        StarBotCoreProperties.ConfigUi.Auth auth = properties.getConfigUi().getAuth();
        return new ConfigUiSessionStore(
                Duration.ofHours(Math.max(1, auth.getSessionHours())),
                Duration.ofHours(Math.max(1, auth.getIdleHours())));
    }

    /**
     * 登录限流
     */
    @Bean
    public LoginThrottle configUiLoginThrottle() {
        StarBotCoreProperties.ConfigUi.Auth auth = properties.getConfigUi().getAuth();
        return new LoginThrottle(auth.getMaxFailures(), Duration.ofMinutes(Math.max(1, auth.getLockoutMinutes())));
    }

    /**
     * 登录校验
     */
    @Bean
    public ConfigUiAuthService configUiAuthService(ConfigUiSessionStore sessionStore, LoginThrottle throttle) {
        return new ConfigUiAuthService(properties.getConfigUi().getAuth(), sessionStore, throttle);
    }

    /**
     * 注册配置界面安全过滤器
     * @return 过滤器注册信息
     */
    @Bean
    public FilterRegistrationBean<ConfigUiSecurityFilter> configUiSecurityFilterRegistration(ConfigUiAuthService authService) {
        IpMatcher ipMatcher = new IpMatcher(properties.getConfigUi().getAllowIps());
        if (ipMatcher.isEmpty()) {
            log.error("配置界面的 IP 白名单为空, 所有访问都将被拒绝, 请检查 starbot.core.config-ui.allow-ips 配置");
        }

        FilterRegistrationBean<ConfigUiSecurityFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ConfigUiSecurityFilter(token, ipMatcher, authService));
        registration.addUrlPatterns(ConfigUiController.BASE_PATH, ConfigUiController.BASE_PATH + "/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        registration.setName("configUiSecurityFilter");

        return registration;
    }

    /**
     * 启动完毕后输出配置界面地址
     * <p>
     * 未启用口令登录时，令牌只在启动日志中出现一次，使用者复制该地址即可直接进入界面，无需另行配置。
     */
    @Order(20000)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReadyEvent() {
        int port = webContext.getWebServer().getPort();
        String address = properties.getConfigUi().getAllowIps().stream().anyMatch(ip -> ip.startsWith("127."))
                ? "127.0.0.1"
                : "本机地址";

        StarBotCoreProperties.ConfigUi.Auth auth = properties.getConfigUi().getAuth();
        if (!StringUtil.isBlank(auth.getPassword())) {
            log.info("配置界面已启动: http://{}:{}{}", address, port, ConfigUiController.BASE_PATH);
            log.info("已启用口令登录{}", StringUtil.isBlank(auth.getTotpSecret()) ? "" : "与二次验证");
            // 令牌仍然有效，它是忘记口令时唯一不必重启就能进去的路。
            // 这一行必须打出来——否则「运维通道」只存在于代码里，真需要时谁也拿不到令牌
            log.info("忘记口令时可用以下地址直接进入（该地址等同于口令，请勿分享）:");
            log.info("  http://{}:{}{}?token={}", address, port, ConfigUiController.BASE_PATH, token);
            return;
        }

        log.info("配置界面已启动: http://{}:{}{}?token={}", address, port, ConfigUiController.BASE_PATH, token);
        log.info("该地址包含访问令牌, 请勿分享。令牌未在配置文件中显式设置时, 每次启动都会重新生成");

        // 白名单与口令分处两处配置，放开了前者却忘了后者是最容易犯的错，而它的后果是面板对全网敞开
        boolean open = properties.getConfigUi().getAllowIps().stream()
                .anyMatch(ip -> ip.startsWith("0.0.0.0") || ip.startsWith("::/") || "*".equals(ip.strip()));
        if (open) {
            log.error("配置界面的 IP 白名单已放开到任意地址, 但未设置登录口令");
            log.error("请设置 starbot.core.config-ui.auth.password, 否则任何人都能改推送目标与查看运行数据");
        }
    }
}
