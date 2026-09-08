package com.starlwr.bot.core.config.ui;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.config.ui.auth.ConfigUiAuthService;
import com.starlwr.bot.core.config.ui.auth.ConfigUiSession;
import com.starlwr.bot.core.config.ui.auth.ConfigUiSessionStore;
import com.starlwr.bot.core.config.ui.auth.LoginThrottle;
import com.starlwr.bot.core.util.IpMatcher;
import jakarta.servlet.FilterChain;
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
 * 「忘记口令」启动令牌通道的开关
 * <p>
 * 这道后门<b>绕过二次验证</b>：拿着启动日志里那个地址就能直接进控制台，
 * 不问口令、不问验证码。它默认保留是因为忘记口令时它是唯一不改配置就能进去的路，
 * <b>默认关掉会把人锁在门外，那比留一道后门更糟</b>。
 * <p>
 * 但它一旦被别的服务借用（例如经 {@code auth_request} 代理 OneBot 实现的 WebUI），
 * <b>就同时成了那些服务的后门</b>，所以必须能关。
 */
@DisplayName("启动令牌通道开关")
class OperatorTokenSwitchTest {
    private static final String TOKEN = "operator-token-for-test-0123456789";

    private static final String PASSWORD = "correct horse battery staple";

    private ConfigUiAuthService authService() {
        StarBotCoreProperties.ConfigUi.Auth properties = new StarBotCoreProperties.ConfigUi.Auth();
        properties.setPassword(PASSWORD);
        properties.setTotp(false);
        return new ConfigUiAuthService(properties,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(properties.getMaxFailures(), Duration.ofMinutes(15)), null);
    }

    private ConfigUiSecurityFilter filter(ConfigUiAuthService auth, boolean operatorToken) {
        // 这一组用例问的是「令牌通道开关」，因此把使用协议置于已同意——
        // 否则协议那道闸会先一步把请求挡下，上面那条阳性对照测到的就不是令牌开关了。
        // 版本与通道两项都要给：少了通道那一项，这份记录按判法仍然算「说不出是谁点的」
        StarBotCoreProperties.ConfigUi.Agreement agreement = new StarBotCoreProperties.ConfigUi.Agreement();
        agreement.setAcceptedVersion(ConfigUiAgreement.VERSION);
        agreement.setAcceptedBy(ConfigUiSession.Channel.PASSWORD.wire());

        // 过滤器现在读的是配置对象本体那一位，不是构造时抄下来的布尔——
        // 「上锁之后自动关掉这条通道」要当场生效，抄一份的写法在那件事上会静静失效
        StarBotCoreProperties.ConfigUi.Auth authProperties = new StarBotCoreProperties.ConfigUi.Auth();
        authProperties.setOperatorToken(operatorToken);

        return new ConfigUiSecurityFilter(TOKEN, new IpMatcher(List.of("0.0.0.0/0", "::/0")), auth, authProperties, agreement);
    }

    /**
     * 拿着地址栏令牌访问一个受保护的接口
     * @return 响应
     */
    private MockHttpServletResponse visitWithToken(boolean operatorToken) throws Exception {
        ConfigUiAuthService auth = authService();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", ConfigUiController.BASE_PATH + "/api/session-check");
        request.setParameter("token", TOKEN);
        request.setRemoteAddr("127.0.0.1");

        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();
        filter(auth, operatorToken).doFilter(request, response, chain);
        return response;
    }

    @Test
    @DisplayName("尺子先过阳性对照：开着时地址栏令牌确实能进")
    void tokenWorksWhenEnabled() throws Exception {
        MockHttpServletResponse response = visitWithToken(true);

        assertEquals(200, response.getStatus(),
                "阳性对照：这一条不通过，下面那条「关掉后进不去」就说明不了任何事");
        assertNotNull(response.getHeader("Set-Cookie"), "换到会话之后浏览器就照常走会话那一套");
    }

    @Test
    @DisplayName("🔴 关掉之后，同一个令牌必须进不去")
    void tokenRejectedWhenDisabled() throws Exception {
        MockHttpServletResponse response = visitWithToken(false);

        assertEquals(401, response.getStatus(), "后门关了就是关了，令牌再对也不认");
        assertNull(response.getHeader("Set-Cookie"), "更不该顺手发一个会话出去");
    }

    @Test
    @DisplayName("🔴 关掉后门不等于关掉所有人：已登录的会话照常放行")
    void sessionStillWorksWhenTokenDisabled() throws Exception {
        ConfigUiAuthService auth = authService();
        // 直接签一个会话，模拟已经用口令登录过的浏览器
        ConfigUiSession session = auth.issueForOperator("127.0.0.1");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", ConfigUiController.BASE_PATH + "/api/session-check");
        request.setCookies(new jakarta.servlet.http.Cookie(ConfigUiSecurityFilter.SESSION_COOKIE, session.getId()));
        request.setRemoteAddr("127.0.0.1");

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter(auth, false).doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus(),
                "这一条守着「别把人锁在门外」——关的是后门，不是正门");
    }
}
