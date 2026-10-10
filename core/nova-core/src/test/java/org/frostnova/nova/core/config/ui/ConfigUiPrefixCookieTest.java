package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSession;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSessionStore;
import org.frostnova.nova.core.config.ui.auth.LoginThrottle;
import org.frostnova.nova.core.config.ui.auth.LowIterationPasswordHash;
import org.frostnova.nova.core.util.IpMatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配了部署前缀时，控制台发的 Cookie 落在前缀下
 * <p>
 * 抓的用户故障：配了 {@code server.servlet.context-path=/novabot}，用口令登录以后又被弹回登录页。
 * 会话 Cookie 的 Path 写死 {@code /config}，浏览器访问 {@code /novabot/config} 时不会把它带回来，
 * 服务端就当这一趟没登录。启动令牌换会话、令牌形态写令牌 Cookie 是同一种写法、同一个毛病。
 * <p>
 * 每一处都量两次：配了前缀时 Path 是「前缀 + /config」，没配时仍是 {@code /config}，现有部署一点不变。
 * 通行密钥登录那一处在 {@code PasskeyLoginCookiePathTest}。
 * <p>
 * 请求方能在请求头里塞 {@code X-Forwarded-Prefix}、改掉请求上的 context path，
 * 那一种在 {@link ConfigUiPrefixFromConfigTest}。
 */
@DisplayName("配了部署前缀时，Cookie 的 Path 带上前缀")
class ConfigUiPrefixCookieTest {

    private static final String PASSWORD = "correct horse battery staple";

    private static final String PASSWORD_HASH = LowIterationPasswordHash.of(PASSWORD);

    private static final String TOKEN = "operator-token-for-test-0123456789";

    private static final String TOKEN_COOKIE = "starbot_config_token";

    /**
     * 发 Cookie 的一处：给定前缀，跑一趟，交回那一趟的响应
     */
    @FunctionalInterface
    interface Source {
        /**
         * @param configured 本程序配的前缀，即 servlet 上下文自己的 context path
         * @param onRequest 请求上的 context path，平时与前者相同，转发头能把它改成别的
         */
        MockHttpServletResponse run(String configured, String onRequest) throws Exception;
    }

    static Stream<Arguments> sources() {
        return Stream.of(
                Arguments.of("口令登录", ConfigUiSecurityFilter.SESSION_COOKIE, (Source) ConfigUiPrefixCookieTest::passwordLogin),
                Arguments.of("启动令牌换会话", ConfigUiSecurityFilter.SESSION_COOKIE, (Source) ConfigUiPrefixCookieTest::operatorTokenRedeem),
                Arguments.of("令牌形态写令牌", TOKEN_COOKIE, (Source) ConfigUiPrefixCookieTest::tokenForm));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    @DisplayName("Path 是「前缀 + /config」；没配前缀时仍是 /config")
    void cookiePathCarriesPrefix(String what, String cookieName, Source source) throws Exception {
        assertEquals("/novabot/config", path(source.run("/novabot", "/novabot"), cookieName),
                what + "：配了前缀 /novabot 时，Path 还是 /config 的话，浏览器不会把它带回 /novabot/config");
        assertEquals("/config", path(source.run("", ""), cookieName),
                what + "：没配前缀时 Path 与改前逐字相同");
    }

    /**
     * 一趟请求：servlet 上下文的 context path 是 configured，请求上的是 onRequest
     */
    static MockHttpServletRequest request(String method, String configured, String onRequest, String path) {
        MockServletContext context = new MockServletContext();
        context.setContextPath(configured);
        MockHttpServletRequest request = new MockHttpServletRequest(context, method, onRequest + path);
        request.setContextPath(onRequest);
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    /**
     * 取某个 Cookie 写下（不是清掉）的那一份的 Path
     */
    static String path(MockHttpServletResponse response, String cookieName) {
        List<String> written = response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(header -> header.startsWith(cookieName + "="))
                .map(header -> Arrays.stream(header.split(";")).map(String::strip).toList())
                .filter(parts -> !parts.contains("Max-Age=0"))
                .map(parts -> parts.stream().filter(p -> p.startsWith("Path=")).findFirst().orElse("Path 缺失"))
                .toList();
        assertEquals(1, written.size(), "前提：这一趟写下了一份 " + cookieName + "，实得 "
                + response.getHeaders(HttpHeaders.SET_COOKIE));
        return written.get(0).substring("Path=".length());
    }

    static ConfigUiAuthService passwordAuth() {
        NovaCoreProperties.ConfigUi.Auth auth = new NovaCoreProperties.ConfigUi.Auth();
        auth.setPassword(PASSWORD_HASH);
        auth.setTotp(false);
        return new ConfigUiAuthService(auth,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(auth.getMaxFailures(), Duration.ofMinutes(15)), null);
    }

    static NovaCoreProperties.ConfigUi.Agreement accepted(ConfigUiSession.Channel channel) {
        NovaCoreProperties.ConfigUi.Agreement agreement = new NovaCoreProperties.ConfigUi.Agreement();
        agreement.setAcceptedVersion(ConfigUiAgreement.VERSION);
        agreement.setAcceptedBy(channel.wire());
        return agreement;
    }

    static MockHttpServletResponse passwordLogin(String configured, String onRequest) throws Exception {
        Path directory = Files.createTempDirectory("prefix-cookie");
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(directory.resolve("data.json").toString());
        properties.getConfigUi().getAuth().setPassword(PASSWORD_HASH);
        properties.getConfigUi().getAuth().setTotp(false);
        ConfigUiAuthService service = new ConfigUiAuthService(properties.getConfigUi().getAuth(),
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(5, Duration.ofMinutes(15)), null);
        ConfigUiAuthController controller = new ConfigUiAuthController(service, null, properties);

        JSONObject body = new JSONObject();
        body.put("password", PASSWORD);
        var entity = controller.login(body, request("POST", configured, onRequest, "/config/api/auth/login"));
        assertTrue(entity.getBody().getBooleanValue("success"), "前提：口令对，这一趟登录成功");

        MockHttpServletResponse response = new MockHttpServletResponse();
        entity.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE)
                .forEach(header -> response.addHeader(HttpHeaders.SET_COOKIE, header));
        return response;
    }

    static MockHttpServletResponse operatorTokenRedeem(String configured, String onRequest) throws Exception {
        NovaCoreProperties.ConfigUi.Auth authProperties = new NovaCoreProperties.ConfigUi.Auth();
        authProperties.setOperatorToken(true);
        ConfigUiSecurityFilter filter = new ConfigUiSecurityFilter(TOKEN, new IpMatcher(List.of("0.0.0.0/0", "::/0")),
                passwordAuth(), authProperties, accepted(ConfigUiSession.Channel.PASSWORD));

        MockHttpServletRequest request = request("GET", configured, onRequest, "/config");
        request.setParameter("token", TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertNotNull(chain.getRequest(), "前提：启动令牌对，这一趟放行");
        return response;
    }

    static MockHttpServletResponse tokenForm(String configured, String onRequest) throws Exception {
        NovaCoreProperties.ConfigUi.Auth auth = new NovaCoreProperties.ConfigUi.Auth();
        ConfigUiAuthService authService = new ConfigUiAuthService(auth,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(5, Duration.ofMinutes(15)), null);
        assertFalse(authService.isEnabled(), "前提：未配口令，走令牌形态");
        ConfigUiSecurityFilter filter = new ConfigUiSecurityFilter(TOKEN, new IpMatcher(List.of("0.0.0.0/0", "::/0")),
                authService, auth, accepted(ConfigUiSession.Channel.OPERATOR_TOKEN));

        MockHttpServletRequest request = request("GET", configured, onRequest, "/config");
        request.setParameter("token", TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertNotNull(chain.getRequest(), "前提：令牌对，这一趟放行");
        return response;
    }
}
