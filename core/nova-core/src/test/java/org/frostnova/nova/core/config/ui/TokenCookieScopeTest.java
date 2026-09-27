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
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动令牌 Cookie 的路径与 Secure
 * <p>
 * 抓的用户故障：走 https 用启动令牌进控制台时，令牌 Cookie 既不带 {@code Secure}、路径又是「/」——
 * 同一台机器上别的路径都收得到这份令牌，而只要同域名下有一次明文 http 请求，浏览器就会把令牌随它送出去。
 * http 下照旧能进，只是不带 {@code Secure}。
 * <p>
 * 还有旧版本写下的那一份：它路径是「/」，路径不同的两份同名 Cookie 在浏览器里是并存的，
 * 新的这份顶不掉它，而它照样会被送到别的路径上。这里一并钉住「并存时照常进」与「写新的一趟顺手清掉旧的」。
 */
@DisplayName("启动令牌 Cookie 的路径与 Secure")
class TokenCookieScopeTest {

    private static final String COOKIE_NAME = "starbot_config_token";

    private static final String TOKEN = "operator-token-for-test-0123456789";

    /**
     * 上一次启动写下的那份令牌——没配固定令牌时每次启动都是新的一把，旧的那份多半是另一个值
     */
    private static final String STALE = "token-from-a-previous-boot-0123456789";

    private ConfigUiSecurityFilter tokenFormFilter() {
        NovaCoreProperties.ConfigUi.Auth auth = new NovaCoreProperties.ConfigUi.Auth();

        ConfigUiAuthService authService = new ConfigUiAuthService(auth,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(5, Duration.ofMinutes(15)), null);

        assertFalse(authService.isEnabled(), "这一组问的正是未配口令那一形态，前提先自证");

        NovaCoreProperties.ConfigUi.Agreement agreement = new NovaCoreProperties.ConfigUi.Agreement();
        agreement.setAcceptedVersion(ConfigUiAgreement.VERSION);
        agreement.setAcceptedBy(ConfigUiSession.Channel.OPERATOR_TOKEN.wire());

        return new ConfigUiSecurityFilter(TOKEN, new IpMatcher(List.of("0.0.0.0/0", "::/0")),
                authService, auth, agreement);
    }

    private MockHttpServletRequest request(String method, String path, String token, boolean secure) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("127.0.0.1");
        request.setSecure(secure);
        if (token != null) {
            request.setParameter("token", token);
        }
        return request;
    }

    private Cookie cookie(String value, String path) {
        Cookie cookie = new Cookie(COOKIE_NAME, value);
        cookie.setPath(path);
        return cookie;
    }

    /**
     * 旧版本写下的那副样子：值是上一把令牌，路径「/」
     */
    private Cookie staleCookie() {
        return cookie(STALE, "/");
    }

    /**
     * 把一条 Set-Cookie 拆成逐段（去空白）后按整段比对
     * <p>
     * 不能用 {@code contains}：「Path=/」是「Path=/config」的子串，那样会把刚写下的那份也当成要清的那份。
     */
    private static List<String> parts(String setCookie) {
        return Arrays.stream(setCookie.split(";")).map(String::strip).toList();
    }

    private static List<String> expiredSetCookies(MockHttpServletResponse response) {
        return response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(header -> parts(header).contains("Max-Age=0"))
                .toList();
    }

    @Test
    @DisplayName("🔴 走 https 写下的令牌 Cookie：路径缩到 /config、带 Secure；http 下照旧能进，只是不带 Secure")
    void tokenCookieIsScopedToConfigAndSecureOnHttps() throws Exception {
        ConfigUiSecurityFilter filter = tokenFormFilter();

        MockHttpServletResponse https = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request("GET", ConfigUiController.BASE_PATH, TOKEN, true), https, chain);

        assertNotNull(chain.getRequest(), "前提：令牌对时这一趟要放行，否则下面量到的是那张拒绝页");
        Cookie written = https.getCookie(COOKIE_NAME);
        assertNotNull(written, "凭令牌进来那一趟要把令牌 Cookie 写下，此后每一趟都靠它");
        assertTrue(written.getSecure(),
                "走 https 时不带 Secure 的话，同域名下只要有一次明文请求，浏览器就会把令牌随它送出去");
        assertEquals(ConfigUiController.BASE_PATH, written.getPath(),
                "路径缩到 /config：写「/」的话，同一台机器上别的路径也收得到这份令牌");
        assertTrue(written.isHttpOnly(), "照旧挡住页面脚本读取");
        assertEquals("Strict", written.getAttribute("SameSite"), "照旧让跨站请求带不上它");

        MockHttpServletResponse http = new MockHttpServletResponse();
        MockFilterChain plain = new MockFilterChain();
        filter.doFilter(request("GET", ConfigUiController.BASE_PATH, TOKEN, false), http, plain);

        assertNotNull(plain.getRequest(), "http 下照旧能进，不是把明文那条路整个关掉");
        Cookie plainWritten = http.getCookie(COOKIE_NAME);
        assertNotNull(plainWritten, "http 下也照旧写下");
        assertFalse(plainWritten.getSecure(),
                "Secure 跟着连接走：http 那一趟带上它，浏览器拒收这份 Cookie，人就被自己那道标记关在门外");
        assertEquals(ConfigUiController.BASE_PATH, plainWritten.getPath(), "两种连接下的路径一致");
    }

    @Test
    @DisplayName("🔴 旧版那份路径为「/」的令牌 Cookie 还在时照常进，写新的一趟顺手把它清掉、新的只落在 /config")
    void staleTokenCookieIsClearedWhenTheNewOneIsWritten() throws Exception {
        ConfigUiSecurityFilter filter = tokenFormFilter();

        // 浏览器里还留着旧版那份时带着启动令牌来：值多半已经不是这一把了
        MockHttpServletRequest visit = request("GET", ConfigUiController.BASE_PATH, TOKEN, true);
        visit.setCookies(staleCookie());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(visit, response, chain);

        assertNotNull(chain.getRequest(),
                "旧的那份还在时要照常进：这一趟认的是地址栏里的令牌，不读 Cookie，旧的值再旧也挡不住进门");

        List<String> cleared = expiredSetCookies(response);
        assertEquals(1, cleared.size(),
                "写新的一趟要顺手清掉旧的那份：路径不同的两份同名 Cookie 在浏览器里并存，"
                        + "新的这份顶不掉它，它照样会被送到别的路径上；得到：" + response.getHeaders(HttpHeaders.SET_COOKIE));
        assertTrue(parts(cleared.get(0)).contains("Path=/"),
                "清的是路径为「/」的那份，路径得照它自己那副样子写；得到：" + cleared.get(0));
        assertTrue(parts(cleared.get(0)).contains("Max-Age=0"), "Max-Age=0，浏览器收到即弃；得到：" + cleared.get(0));

        Cookie written = response.getCookie(COOKIE_NAME);
        assertNotNull(written, "新的一趟照旧把令牌 Cookie 写下");
        assertEquals(ConfigUiController.BASE_PATH, written.getPath(), "新的这份只落在 /config 下");

        assertTrue(cleared.stream().noneMatch(header -> parts(header).contains("Path=" + ConfigUiController.BASE_PATH)),
                "清的不能是刚写下的那份：路径写成 /config 的话，下一趟连门都进不来");

        // 两份并存的那一趟，浏览器按 RFC 6265 §5.4 把路径长的送在前：/config 那份排在「/」前面
        MockHttpServletRequest next = request("GET", ConfigUiController.BASE_PATH, null, true);
        next.setCookies(cookie(TOKEN, ConfigUiController.BASE_PATH), staleCookie());
        MockFilterChain after = new MockFilterChain();
        filter.doFilter(next, new MockHttpServletResponse(), after);

        assertNotNull(after.getRequest(),
                "两份并存时照常进控制台：浏览器送来的次序里 /config 在前，取到的是新的那份，不会取到旧的");
    }
}
