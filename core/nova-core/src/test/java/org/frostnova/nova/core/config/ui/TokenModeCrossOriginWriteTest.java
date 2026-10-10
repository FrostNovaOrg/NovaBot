package org.frostnova.nova.core.config.ui;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSession;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSessionStore;
import org.frostnova.nova.core.config.ui.auth.LoginThrottle;
import org.frostnova.nova.core.util.IpMatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.http.Cookie;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 未配口令时，写请求同样核对来源
 * <p>
 * 抓的用户故障：没设控制台密码时，同一台机器上另一个端口的网页借浏览器自动带上的令牌 Cookie，
 * 暗中替使用者发控制台的写请求。SameSite 按站点划分、不按端口，挡不住这一种。
 */
@DisplayName("令牌形态下写请求核对来源")
class TokenModeCrossOriginWriteTest {

    private static final String COOKIE_NAME = "starbot_config_token";

    private static final String TOKEN = "fake-token-for-test-not-a-secret";

    private static final String HOST = "127.0.0.1:8766";

    private static final String PATH = ConfigUiController.BASE_PATH + "/api/values";

    private ConfigUiSecurityFilter tokenFormFilter() {
        NovaCoreProperties.ConfigUi.Auth auth = new NovaCoreProperties.ConfigUi.Auth();

        ConfigUiAuthService authService = new ConfigUiAuthService(auth,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(5, Duration.ofMinutes(15)), null);

        assertFalse(authService.isEnabled(), "这一组问的是未配口令那一形态，前提先自证");

        NovaCoreProperties.ConfigUi.Agreement agreement = new NovaCoreProperties.ConfigUi.Agreement();
        agreement.setAcceptedVersion(ConfigUiAgreement.VERSION);
        agreement.setAcceptedBy(ConfigUiSession.Channel.OPERATOR_TOKEN.wire());

        return new ConfigUiSecurityFilter(TOKEN, new IpMatcher(List.of("0.0.0.0/0", "::/0")),
                authService, auth, agreement);
    }

    /**
     * 令牌只在 Cookie 里：地址栏与请求头都不带，正是浏览器替别的页面自动附上的那一种
     */
    private MockHttpServletRequest cookieOnly(String method, String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, PATH);
        request.setRemoteAddr("127.0.0.1");
        request.addHeader(HttpHeaders.HOST, HOST);
        request.addHeader(HttpHeaders.ORIGIN, origin);
        request.setCookies(new Cookie(COOKIE_NAME, TOKEN));
        return request;
    }

    @Test
    @DisplayName("🔴 同机另一端口的页面带着 Cookie 发 POST：回 403，处理链没走到")
    void crossPortWriteIsRejected() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        tokenFormFilter().doFilter(cookieOnly("POST", "http://127.0.0.1:9000"), response, chain);

        assertEquals(403, response.getStatus(), "跨端口写请求要被拒；得到：" + response.getContentAsString());
        assertNull(chain.getRequest(), "被拒的写请求不能走到处理链");
    }

    @Test
    @DisplayName("对照：本站页面带着 Cookie 发 POST 照常放行")
    void sameOriginWriteIsAllowed() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        tokenFormFilter().doFilter(cookieOnly("POST", "http://" + HOST), response, chain);

        assertNotNull(chain.getRequest(), "本站页面的写请求要照常放行；状态：" + response.getStatus());
    }

    @Test
    @DisplayName("对照：同机另一端口的 GET 照常放行，读请求不核来源")
    void crossPortReadIsAllowed() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        tokenFormFilter().doFilter(cookieOnly("GET", "http://127.0.0.1:9000"), response, chain);

        assertNotNull(chain.getRequest(), "读请求不受来源核对影响；状态：" + response.getStatus());
    }
}
