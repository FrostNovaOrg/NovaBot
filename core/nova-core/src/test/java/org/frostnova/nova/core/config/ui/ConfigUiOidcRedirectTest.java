package org.frostnova.nova.core.config.ui;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccountStore;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSession;
import org.frostnova.nova.core.config.ui.auth.oidc.OidcTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.net.URI;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConfigUiOidcRedirectTest extends OidcTestSupport {
    private ConfigUiAuthService auth;
    private ConfigUiAccountStore accounts;
    private ConfigUiOidcController controller;

    @BeforeEach
    void console() {
        auth = mock(ConfigUiAuthService.class);
        when(auth.oidcEnabled()).thenReturn(true);
        accounts = mock(ConfigUiAccountStore.class);
        var account = mock(ConfigUiAccount.class);
        when(account.status()).thenReturn(ConfigUiAccount.Status.PENDING);
        when(accounts.register(any(), any())).thenReturn(account);
        controller = new ConfigUiOidcController(client, auth, accounts, new NovaCoreProperties());
    }

    static Stream<Arguments> deployments() {
        return Stream.of(
                Arguments.of("", true, "bot.example", null),
                Arguments.of("", false, "bot.example", null),
                Arguments.of("/novabot", true, "bot.example", null),
                Arguments.of("/novabot", false, "bot.example", null),
                Arguments.of("", true, "evil.example", null),
                Arguments.of("", true, "bot.example", "/evil"),
                Arguments.of("/novabot", true, "evil.example", "/evil"));
    }

    // 内部端口和请求头里的主机、前缀不能进入登录完成后的控制台地址。
    @ParameterizedTest(name = "prefix={0}, forwarded={1}, host={2}, forwardedPrefix={3}")
    @MethodSource("deployments")
    void pendingCallbackUsesOnlyConfiguredPath(String prefix, boolean forwarded, String host,
                                               String forwardedPrefix) throws Exception {
        var browser = start(false);
        var request = callback(browser, prefix);
        if (forwarded) {
            request.addHeader("X-Forwarded-Proto", "https");
            request.addHeader("X-Forwarded-Host", host);
            request.addHeader("X-Forwarded-Port", "8444");
            if (forwardedPrefix != null) request.addHeader("X-Forwarded-Prefix", forwardedPrefix);
        }
        assertResult(request, prefix, "pending");
        assertTrue(provider.pkceChecked, "回调已完成账户中心的登录校验");
        verify(accounts).register(any(), any());
    }

    @ParameterizedTest
    @CsvSource({"invalid_request,failed", "temporarily_unavailable,busy", "access_denied,denied",
            "invalid_client,configuration"})
    void providerErrorsAlsoRedirectToPath(String error, String result) throws Exception {
        var request = forwardedCallback(start(false));
        request.setParameter("error", error);
        assertResult(request, "", result);
        verifyNoInteractions(accounts);
    }

    @Test
    void bindingCallbackAlsoRedirectsToPath() throws Exception {
        var session = mock(ConfigUiSession.class);
        when(session.getId()).thenReturn("local-session");
        when(session.getAccountId()).thenReturn(ConfigUiAccount.LOCAL_ID);
        when(auth.validate("local-session")).thenReturn(Optional.of(session));
        when(auth.freshLocal(session)).thenReturn(true);
        var request = forwardedCallback(start(true));
        assertResult(request, "", "bound");
        verify(accounts).bind(any());
    }

    private Browser start(boolean bind) throws Exception {
        var request = request("", "/config/api/oidc/login");
        var response = new MockHttpServletResponse();
        String url;
        if (bind) {
            request.setAttribute(ConfigUiAccess.ACCOUNT, ConfigUiAccount.local(clock.instant()));
            request.setAttribute(ConfigUiAccess.FRESH_LOCAL, true);
            request.setAttribute(ConfigUiAccess.SESSION, auth.validate("local-session").orElseThrow());
            controller.bind(request, response);
            url = com.alibaba.fastjson2.JSONObject.parseObject(response.getContentAsString()).getString("authorizationUrl");
        } else {
            controller.login(request, response);
            url = response.getHeader(HttpHeaders.LOCATION);
            assertTrue(url.startsWith(ISSUER + "/authorize?"), "去账户中心仍使用完整地址");
        }
        var query = query(URI.create(url).getRawQuery());
        provider.nonce = query.get("nonce");
        provider.challenge = query.get("code_challenge");
        provider.redirect = query.get("redirect_uri");
        return new Browser(query.get("state"), response.getCookie(ConfigUiOidcController.COOKIE));
    }

    private MockHttpServletRequest callback(Browser browser, String prefix) {
        var request = request(prefix, "/config/api/oidc/callback");
        request.setParameter("state", browser.state());
        request.setParameter("code", "code-1");
        request.setParameter("iss", ISSUER);
        request.setCookies(browser.cookie());
        return request;
    }

    private MockHttpServletRequest forwardedCallback(Browser browser) {
        var request = callback(browser, "");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "bot.example");
        request.addHeader("X-Forwarded-Port", "8444");
        return request;
    }

    private static MockHttpServletRequest request(String prefix, String path) {
        var context = new MockServletContext();
        context.setContextPath(prefix);
        var request = new MockHttpServletRequest(context, "GET", prefix + path);
        request.setContextPath(prefix);
        return request;
    }

    private void assertResult(MockHttpServletRequest request, String prefix, String result) throws Exception {
        var response = new MockHttpServletResponse();
        new ForwardedHeaderFilter().doFilter(request, response, (incoming, outgoing) ->
                controller.callback((HttpServletRequest) incoming, (HttpServletResponse) outgoing));
        String location = response.getHeader(HttpHeaders.LOCATION);
        System.out.printf("OIDC result=%s, configuredPrefix=%s, Location: %s%n", result, prefix, location);
        assertEquals(302, response.getStatus());
        assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
        assertEquals(prefix + "/config?oidc=" + result, location, "Location");
    }

    private record Browser(String state, Cookie cookie) {}
}
