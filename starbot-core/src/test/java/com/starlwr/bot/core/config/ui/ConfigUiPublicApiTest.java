package com.starlwr.bot.core.config.ui;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.config.ui.auth.ConfigUiAuthService;
import com.starlwr.bot.core.config.ui.auth.ConfigUiSession;
import com.starlwr.bot.core.config.ui.auth.ConfigUiSessionStore;
import com.starlwr.bot.core.config.ui.auth.LoginThrottle;
import com.starlwr.bot.core.util.IpMatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公开口名单只在口令形态生效
 * <p>
 * 登录页要先问「要不要登录」再提交口令，这两步都发生在认出人之前，所以那几条
 * 在口令形态下不要求会话。未配口令时没有登录页，令牌本身就是凭据——同一组路径
 * 若仍公开，任何能连上这个端口的程序都能绕过地址栏里的那把钥匙。
 * <p>
 * 过滤器把这两件事写在两个方法里，名单只被口令那一支查阅。名单本身看不出来
 * 「另一形态根本不读它」，所以这一组把两种形态对着同一条路径量一遍。
 */
@DisplayName("公开口名单的形态")
class ConfigUiPublicApiTest {
    private static final String TOKEN = "operator-token-for-test-0123456789";

    private static final String PASSWORD = "correct horse battery staple";

    /**
     * 公开口那几条。过滤器里的集合是私有的，这里按路径字面量对着量——
     * 多一条或少一条，功能上常常看不出来。
     */
    private static final List<String> PUBLIC_PATHS = List.of(
            ConfigUiController.BASE_PATH + "/api/auth/state",
            ConfigUiController.BASE_PATH + "/api/auth/login",
            ConfigUiController.BASE_PATH + "/api/auth/passkey/login/options",
            ConfigUiController.BASE_PATH + "/api/auth/passkey/login/verify");

    private ConfigUiSecurityFilter passwordFormFilter() {
        StarBotCoreProperties.ConfigUi.Auth auth = new StarBotCoreProperties.ConfigUi.Auth();
        auth.setPassword(PASSWORD);
        auth.setTotp(false);

        ConfigUiAuthService authService = new ConfigUiAuthService(auth,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(auth.getMaxFailures(), Duration.ofMinutes(15)), null);

        assertTrue(authService.isEnabled(), "这一组问的正是口令形态，前提先自证");

        StarBotCoreProperties.ConfigUi.Agreement agreement = new StarBotCoreProperties.ConfigUi.Agreement();
        agreement.setAcceptedVersion(ConfigUiAgreement.VERSION);
        agreement.setAcceptedBy(ConfigUiSession.Channel.PASSWORD.wire());

        return new ConfigUiSecurityFilter(TOKEN, new IpMatcher(List.of("0.0.0.0/0", "::/0")),
                authService, auth, agreement);
    }

    private ConfigUiSecurityFilter tokenFormFilter() {
        StarBotCoreProperties.ConfigUi.Auth auth = new StarBotCoreProperties.ConfigUi.Auth();

        ConfigUiAuthService authService = new ConfigUiAuthService(auth,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(5, Duration.ofMinutes(15)), null);

        assertFalse(authService.isEnabled(), "这一组问的正是未配口令那一形态，前提先自证");

        StarBotCoreProperties.ConfigUi.Agreement agreement = new StarBotCoreProperties.ConfigUi.Agreement();
        agreement.setAcceptedVersion(ConfigUiAgreement.VERSION);
        agreement.setAcceptedBy(ConfigUiSession.Channel.OPERATOR_TOKEN.wire());

        return new ConfigUiSecurityFilter(TOKEN, new IpMatcher(List.of("0.0.0.0/0", "::/0")),
                authService, auth, agreement);
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    @Test
    @DisplayName("阳：令牌形态下，公开口名单里的路径没带令牌一律 401")
    void tokenFormRejectsPublicPathsWithoutToken() throws Exception {
        ConfigUiSecurityFilter filter = tokenFormFilter();

        for (String path : PUBLIC_PATHS) {
            String method = path.endsWith("/state") ? "GET" : "POST";
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(request(method, path), response, chain);

            assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatus(),
                    path + " 在令牌形态下没带令牌却放行了");
            assertNull(chain.getRequest(), path + " 更不该走进后面那一端");
        }
    }

    @Test
    @DisplayName("阴：口令形态下，同一组路径不要求先登录")
    void passwordFormAllowsPublicPathsWithoutSession() throws Exception {
        ConfigUiSecurityFilter filter = passwordFormFilter();

        for (String path : PUBLIC_PATHS) {
            String method = path.endsWith("/state") ? "GET" : "POST";
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(request(method, path), response, chain);

            assertNotNull(chain.getRequest(),
                    path + " 在口令形态下被挡住了，登录页自己要用的接口将无从调用");
            assertEquals(HttpStatus.OK.value(), response.getStatus(), path);
        }
    }
}
