package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSession;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSessionStore;
import org.frostnova.nova.core.config.ui.auth.LoginThrottle;
import org.frostnova.nova.core.config.ui.auth.LowIterationPasswordHash;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录状态接口新增的那两位
 * <p>
 * 界面上有两处只能靠它们判断，而两处都<b>没有别的现象</b>可依：
 * <ul>
 *   <li><b>令牌会话常驻条</b>——那条通道绕过了口令与二次验证，人是从后门进来的。
 *       不常驻一条的话，「进来之后改口令、再把通道关掉」这两步只会被忘掉，那道门就一直开着</li>
 *   <li><b>锁定剩余秒数</b>——登录页的倒计时照它走。给「还剩多少」而不是「什么时候解锁」，
 *       是因为后者要拿浏览器的钟去减，而那台电脑的钟未必准</li>
 * </ul>
 */
@DisplayName("登录状态接口")
class AuthStateSurfaceTest {
    private static final String PASSWORD = "correct horse battery staple";

    /**
     * 口令哈希只算一次，且只迭代 1 次
     * <p>
     * 每个用例各构造一次登录服务，而登录校验每趟都要真算一遍哈希——PBKDF2 一次就是几百毫秒，
     * 这一格量的却根本不是哈希。填哈希串进去时启动那一步原样接受，
     * 与生产里「配置文件中已经是哈希」的那一形态走的是同一条路，见 {@link LowIterationPasswordHash}。
     */
    private static final String HASHED = LowIterationPasswordHash.of(PASSWORD);

    private ConfigUiAuthService authService;
    private ConfigUiAuthController controller;

    @BeforeEach
    void setUp() {
        NovaCoreProperties properties = new NovaCoreProperties();
        NovaCoreProperties.ConfigUi.Auth auth = properties.getConfigUi().getAuth();
        auth.setPassword(HASHED);
        auth.setTotp(false);
        // 锁定阈值压到 2 次：判据要的是「锁上之后接口怎么答」，
        // 而默认的 5 次只是让这一格多跑三趟 PBKDF2
        auth.setMaxFailures(2);

        authService = new ConfigUiAuthService(auth,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(auth.getMaxFailures(), Duration.ofMinutes(15)), null);
        controller = new ConfigUiAuthController(authService, null, properties);
    }

    private MockHttpServletRequest request(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                ConfigUiController.BASE_PATH + "/api/auth/state");
        request.setRemoteAddr(ip);
        return request;
    }

    // 当前账号没有显示名时，个人菜单依赖自己的邮箱；未登录不能泄漏邮箱。
    @Test void stateIncludesOnlyAuthenticatedAccountsOwnEmail() {
        var request = request("127.0.0.1");
        request.setAttribute(ConfigUiAccess.ACCOUNT, new org.frostnova.nova.core.config.ui.auth.ConfigUiAccount(
                "member-a", org.frostnova.nova.core.config.ui.auth.ConfigUiAccount.Role.MEMBER,
                org.frostnova.nova.core.config.ui.auth.ConfigUiAccount.Status.ENABLED, null,
                "member@example.com", null, null, java.util.List.of(), java.time.Instant.EPOCH, null, true));
        assertEquals("member@example.com", controller.state(request).getString("email"));
        assertFalse(controller.state(request("127.0.0.1")).containsKey("email"));
    }

    // 已登录的人要能找到账户中心，地址必须与账号列表使用同一个来源。
    @Test
    void authenticatedOidcSetupExposesManagementUri() {
        var oidcProperties = new NovaCoreProperties();
        oidcProperties.getConfigUi().getOidc().setEnabled(true);
        oidcProperties.getConfigUi().getOidc().setIssuer("https://accounts.example.com");
        oidcProperties.getConfigUi().getOidc().setManagementUri("https://accounts.example.com/manage");
        authService.setOidcClient(new org.frostnova.nova.core.config.ui.auth.oidc.OidcClient(
                oidcProperties, new org.springframework.web.client.RestTemplate()));
        var request = request("127.0.0.1");
        request.setAttribute(ConfigUiAccess.ACCOUNT,
                org.frostnova.nova.core.config.ui.auth.ConfigUiAccount.local(java.time.Instant.now()));
        assertEquals("https://accounts.example.com/manage", controller.state(request).getString("managementUri"));
    }

    // 没填管理地址时回空串，页面据此隐藏账户中心入口。
    @Test
    void authenticatedOidcSetupWithoutManagementUriExposesEmptyString() {
        var oidcProperties = new NovaCoreProperties();
        oidcProperties.getConfigUi().getOidc().setEnabled(true);
        oidcProperties.getConfigUi().getOidc().setIssuer("https://accounts.example.com");
        authService.setOidcClient(new org.frostnova.nova.core.config.ui.auth.oidc.OidcClient(
                oidcProperties, new org.springframework.web.client.RestTemplate()));
        var request = request("127.0.0.1");
        request.setAttribute(ConfigUiAccess.ACCOUNT,
                org.frostnova.nova.core.config.ui.auth.ConfigUiAccount.local(java.time.Instant.now()));
        assertEquals("", controller.state(request).getString("managementUri"));
        // 未登录时不能把管理地址带到登录页。
        assertFalse(controller.state(request("127.0.0.1")).containsKey("managementUri"));
    }

    // 没开统一登录时保持原来的回包，避免普通本地登录冒出账户中心入口。
    @Test
    void localOnlyStateDoesNotExposeManagementUri() {
        var request = request("127.0.0.1");
        request.setAttribute(ConfigUiAccess.ACCOUNT,
                org.frostnova.nova.core.config.ui.auth.ConfigUiAccount.local(java.time.Instant.now()));
        assertFalse(controller.state(request).containsKey("managementUri"));
    }

    @Test
    @DisplayName("🔴 令牌会话报 operatorSession 为真")
    void operatorSessionIsReported() {
        MockHttpServletRequest request = request("127.0.0.1");
        ConfigUiSession session = authService.issueForOperator("127.0.0.1");
        request.setCookies(new Cookie(ConfigUiSecurityFilter.SESSION_COOKIE, session.getId()));

        JSONObject state = controller.state(request);

        assertTrue(state.getBooleanValue("authenticated"));
        assertTrue(state.getBooleanValue("operatorSession"),
                "从后门进来的那一次，控制台顶上要常驻一条提醒");
    }

    @Test
    @DisplayName("🔴 阴性对照：口令会话报 operatorSession 为假")
    void passwordSessionIsNotOperator() {
        MockHttpServletRequest request = request("127.0.0.1");
        ConfigUiSession session = authService.login(PASSWORD.toCharArray(), null, "127.0.0.1").session();
        request.setCookies(new Cookie(ConfigUiSecurityFilter.SESSION_COOKIE, session.getId()));

        JSONObject state = controller.state(request);

        assertTrue(state.getBooleanValue("authenticated"));
        assertFalse(state.getBooleanValue("operatorSession"),
                "正门进来的人不该看见那条提醒——常驻条一旦人人都有，就没人再读它了");
    }

    @Test
    @DisplayName("没登录时 operatorSession 也是假，不是空")
    void unauthenticatedIsNotOperator() {
        JSONObject state = controller.state(request("127.0.0.1"));

        assertFalse(state.getBooleanValue("authenticated"));
        assertFalse(state.getBooleanValue("operatorSession"));
    }

    @Test
    @DisplayName("没被锁的来源报 0 秒")
    void notLockedReportsZero() {
        assertEquals(0L, controller.state(request("10.0.0.1")).getLongValue("lockedSeconds"));
    }

    @Test
    @DisplayName("🔴 连错到锁定之后，报的是这个来源的实际剩余秒数")
    void lockedSourceReportsRemainingSeconds() {
        for (int i = 0; i < 2; i++) {
            authService.login("猜错的口令".toCharArray(), null, "10.0.0.2");
        }

        long seconds = controller.state(request("10.0.0.2")).getLongValue("lockedSeconds");

        // 首次锁定 15 分钟。给一个区间而不是钉死 900：这一格与真实时钟之间隔着几次 PBKDF2，
        // 钉死的话它会在慢机器上偶发红，而那种红除了让人把判据关掉之外没有别的作用
        assertTrue(seconds > 840 && seconds <= 900, "剩余秒数应在 15 分钟上下，实为 " + seconds);

        // 锁定是按来源计的：别的地址不该跟着一起被关在外面
        assertEquals(0L, controller.state(request("10.0.0.3")).getLongValue("lockedSeconds"),
                "锁定按来源计，隔壁那个地址不该受牵连");
    }

    @Test
    @DisplayName("🔴 锁定期内登录失败时，那一趟响应也带着剩余秒数")
    void loginFailureCarriesRemainingSeconds() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                ConfigUiController.BASE_PATH + "/api/auth/login");
        request.setRemoteAddr("10.0.0.4");

        JSONObject body = new JSONObject();
        body.put("password", "猜错的口令");

        // 第一次只是失败，还没锁上
        assertEquals(0L, controller.login(body, request).getBody().getLongValue("lockedSeconds"));

        // 第二次达到阈值：这一趟的响应就该带上秒数了。
        // 🔴 拿 LoginResult 里那个 retryAfter 是不行的——刚好第 N 次失败时它还是零，
        // 而此刻锁定已经生效，界面会因此把表单放开，让人再白试一次
        assertTrue(controller.login(body, request).getBody().getLongValue("lockedSeconds") > 0,
                "把自己锁进去的那一次，界面就得知道");
    }
}
