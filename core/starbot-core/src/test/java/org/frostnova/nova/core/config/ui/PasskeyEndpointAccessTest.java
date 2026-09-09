package org.frostnova.nova.core.config.ui;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSession;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSessionStore;
import org.frostnova.nova.core.config.ui.auth.LoginThrottle;
import org.frostnova.nova.core.util.IpMatcher;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 通行密钥四条路各自要不要先登录
 * <p>
 * 这一组量的是安全过滤器上那张放行名单，而<b>名单错了在功能上完全看不出来</b>：
 * 多放一条，未登录的人就能给这台机器再配一把永久有效的钥匙；少放一条，
 * 通行密钥登录这条路整个不存在——而后者的表现是「点了没反应」，
 * 与「这个浏览器不支持」长得一模一样。
 */
@DisplayName("通行密钥接口的身份要求")
class PasskeyEndpointAccessTest {
    private static final String TOKEN = "operator-token-for-test-0123456789";

    private static final String PASSWORD = "correct horse battery staple";

    private ConfigUiAuthService authService;

    private ConfigUiSecurityFilter filter;

    @BeforeEach
    void setUp() {
        NovaCoreProperties.ConfigUi.Auth auth = new NovaCoreProperties.ConfigUi.Auth();
        auth.setPassword(PASSWORD);
        auth.setTotp(false);

        authService = new ConfigUiAuthService(auth,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(auth.getMaxFailures(), Duration.ofMinutes(15)), null);

        // 使用协议置为已同意：否则那道闸会先一步把请求挡下，量到的就不是放行名单了
        NovaCoreProperties.ConfigUi.Agreement agreement = new NovaCoreProperties.ConfigUi.Agreement();
        agreement.setAcceptedVersion(ConfigUiAgreement.VERSION);
        agreement.setAcceptedBy(ConfigUiSession.Channel.PASSWORD.wire());

        // 令牌通道关掉：开着的话，未登录的那几趟会被它换成会话而放行，
        // 于是这一组量到的是「令牌通道开着没有」，不是「这条路要不要先登录」
        // auth 那份配置对象的 operator-token 默认就是关，过滤器读的正是它这一位
        filter = new ConfigUiSecurityFilter(TOKEN, new IpMatcher(List.of("0.0.0.0/0", "::/0")),
                authService, auth, agreement);
    }

    private MockFilterChain visit(String path, String sessionId) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", ConfigUiController.BASE_PATH + path);
        request.setRemoteAddr("127.0.0.1");
        if (sessionId != null) {
            request.setCookies(new Cookie(ConfigUiSecurityFilter.SESSION_COOKIE, sessionId));
            request.addHeader(ConfigUiSecurityFilter.CSRF_HEADER,
                    authService.validate(sessionId).orElseThrow().getCsrfToken());
        }

        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return chain;
    }

    private String login() {
        return authService.login(PASSWORD.toCharArray(), null, "127.0.0.1").session().getId();
    }

    @Test
    @DisplayName("通行密钥登录那两条不要求先登录——它本身就是进门那一步")
    void loginEndpointsArePublic() throws Exception {
        assertNotNull(visit("/api/auth/passkey/login/options", null).getRequest(),
                "取登录参数这一条被挡住的话, 通行密钥登录整条路都不存在");
        assertNotNull(visit("/api/auth/passkey/login/verify", null).getRequest(),
                "验签这一条被挡住的话, 通行密钥登录整条路都不存在");
    }

    @Test
    @DisplayName("登记那两条要求先登录")
    void registerEndpointsRequireSession() throws Exception {
        assertNull(visit("/api/auth/passkey/register/options", null).getRequest(),
                "未登录的人不该拿得到登记参数");
        assertNull(visit("/api/auth/passkey/register/verify", null).getRequest(),
                "未登录的人不该给这台机器配得上新钥匙");

        // 阳性对照：登录之后这两条该通。少了它，一个把整个前缀都挡掉的实现同样能让上面两条绿
        String session = login();
        assertNotNull(visit("/api/auth/passkey/register/options", session).getRequest());
        assertNotNull(visit("/api/auth/passkey/register/verify", session).getRequest());
    }

    @Test
    @DisplayName("管理列表与删除要求先登录")
    void managementRequiresSession() throws Exception {
        assertNull(visit("/api/auth/passkeys", null).getRequest());
        assertNotNull(visit("/api/auth/passkeys", login()).getRequest(), "阳性对照：登录之后该通");
    }
}
