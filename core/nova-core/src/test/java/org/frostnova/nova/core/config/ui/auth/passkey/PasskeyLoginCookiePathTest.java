package org.frostnova.nova.core.config.ui.auth.passkey;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.ui.ConfigUiSecurityFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配了部署前缀时，通行密钥登录发的会话 Cookie 落在前缀下
 * <p>
 * 抓的用户故障：配了 {@code server.servlet.context-path=/novabot}，用通行密钥登录以后又被弹回登录页。
 * 会话 Cookie 的 Path 写死 {@code /config}，浏览器访问 {@code /novabot/config} 时不会把它带回来。
 * 同一格里再量一次没配前缀时，Path 仍是 {@code /config}。
 */
@DisplayName("配了部署前缀时，通行密钥登录的会话 Cookie 带上前缀")
class PasskeyLoginCookiePathTest extends PasskeyTestSupport {

    @Test
    @DisplayName("Path 是「前缀 + /config」；没配前缀时仍是 /config")
    void sessionCookiePathCarriesPrefix() {
        TestAuthenticator authenticator = new TestAuthenticator(TestAuthenticator.ES256);
        register(authenticator, "我的手机", 1);

        assertEquals("/novabot/config", loginCookiePath(authenticator, "/novabot", 2),
                "配了前缀 /novabot 时，Path 还是 /config 的话，浏览器不会把会话带回 /novabot/config");
        assertEquals("/config", loginCookiePath(authenticator, "", 3),
                "没配前缀时 Path 与改前逐字相同");
    }

    /**
     * 抓的用户故障：有人在请求头里塞 {@code X-Forwarded-Prefix}，改掉请求上的 context path，
     * 会话 Cookie 跟着落到那条路径上。Path 只许取本程序配的前缀
     */
    @Test
    @DisplayName("请求上的 context path 被改成别的时，Path 仍取配置的前缀")
    void sessionCookiePathIgnoresRequestContextPath() {
        TestAuthenticator authenticator = new TestAuthenticator(TestAuthenticator.ES256);
        register(authenticator, "我的手机", 1);

        assertEquals("/novabot/config",
                loginCookiePath(authenticator, "/novabot", "/evil\"'<script>", 2),
                "会话 Cookie 的 Path 认了请求上那一份前缀");
    }

    private String loginCookiePath(TestAuthenticator authenticator, String prefix, long signCount) {
        return loginCookiePath(authenticator, prefix, prefix, signCount);
    }

    private String loginCookiePath(TestAuthenticator authenticator, String configured, String onRequest,
                                   long signCount) {
        JSONObject assertion = authenticator.assertion(loginChallenge(), ORIGIN, RP_ID, signCount);
        ResponseEntity<JSONObject> response = controller.loginVerify(assertion, prefixed(configured, onRequest));
        assertTrue(response.getBody().getBooleanValue("success"),
                "前提：这一趟通行密钥登录成功，" + response.getBody().getString("message"));

        List<String> paths = response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).stream()
                .filter(header -> header.startsWith(ConfigUiSecurityFilter.SESSION_COOKIE + "="))
                .flatMap(header -> Arrays.stream(header.split(";")).map(String::strip))
                .filter(part -> part.startsWith("Path="))
                .toList();
        assertEquals(1, paths.size(), "前提：这一趟写下了一份会话 Cookie，" + response.getHeaders());
        return paths.get(0).substring("Path=".length());
    }

    /**
     * 一趟请求：servlet 上下文的 context path 是 configured，请求上的是 onRequest
     */
    private static MockHttpServletRequest prefixed(String configured, String onRequest) {
        MockServletContext context = new MockServletContext();
        context.setContextPath(configured);
        MockHttpServletRequest request = new MockHttpServletRequest(context, "POST",
                onRequest + "/config/api/auth/passkey/login/verify");
        request.setContextPath(onRequest);
        request.addHeader("Host", HOST);
        request.setRemoteAddr(CLIENT_IP);
        return request;
    }
}
