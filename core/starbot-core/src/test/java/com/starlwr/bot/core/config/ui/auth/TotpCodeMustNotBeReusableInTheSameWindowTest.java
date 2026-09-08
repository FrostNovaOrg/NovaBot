package com.starlwr.bot.core.config.ui.auth;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.NovaCoreProperties;
import com.starlwr.bot.core.config.ui.ConfigurationFileService;
import com.starlwr.bot.core.protocol.EventStreamTokenService;
import com.starlwr.bot.core.web.ReadOnlyTokenController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 同一窗口内已经用过的动态码不得再被接受
 * <p>
 * 校验成功后必须消费该时间步。否则知道口令的人只要拿到还在窗口内的一枚已用码，
 * 就能再登一次，或拿去代签发只读口令。
 */
@DisplayName("动态码成功使用后同一窗口不得再用")
class TotpCodeMustNotBeReusableInTheSameWindowTest {
    private static final String PASSWORD = "correct horse battery staple";

    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");

    @TempDir
    Path dir;

    private final AtomicReference<Instant> clock = new AtomicReference<>(NOW);

    private ConfigUiAuthService authService;

    private ReadOnlyTokenController proxyIssue;

    @BeforeEach
    void setUp() {
        NovaCoreProperties.ConfigUi.Auth properties = new NovaCoreProperties.ConfigUi.Auth();
        properties.setPassword(PASSWORD);
        properties.setTotp(true);
        properties.setTotpSecret(SECRET);

        authService = new ConfigUiAuthService(properties,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(properties.getMaxFailures(), Duration.ofMinutes(15)),
                (ConfigurationFileService) null,
                clock::get);

        NovaCoreProperties core = new NovaCoreProperties();
        core.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        EventStreamTokenService tokens = new EventStreamTokenService(core.getLive());

        @SuppressWarnings("unchecked")
        ObjectProvider<ConfigUiAuthService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(authService);
        proxyIssue = new ReadOnlyTokenController(provider, tokens);
    }

    private String code() {
        return TotpGenerator.currentCode(SECRET, clock.get());
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", ReadOnlyTokenController.PATH);
        request.setRemoteAddr("192.0.2.1");
        return request;
    }

    @Test
    @DisplayName("对照：时钟钉住时这一枚动态码第一次登录能过")
    void firstUseInTheWindowSucceeds() {
        ConfigUiAuthService.LoginResult first = authService.login(PASSWORD.toCharArray(), code(), "192.0.2.1");

        assertTrue(first.success(), "第一次应过, " + first.message());
    }

    @Test
    @DisplayName("同一枚动态码第二次登录必须失败，且不得再签发另一把会话")
    void secondLoginWithTheSameCodeMustFail() {
        String totp = code();
        ConfigUiAuthService.LoginResult first = authService.login(PASSWORD.toCharArray(), totp, "192.0.2.1");
        assertTrue(first.success(), "台面：第一次应过");

        ConfigUiAuthService.LoginResult second = authService.login(PASSWORD.toCharArray(), totp, "192.0.2.1");

        assertFalse(second.success(),
                "同一窗口第二次登录必须失败, 实际 success=" + second.success()
                        + " firstSession=" + first.session().getId()
                        + " secondSession=" + (second.session() == null ? "null" : second.session().getId()));
    }

    @Test
    @DisplayName("登录用过的动态码，不得再拿去代签发只读口令")
    void usedCodeMustNotIssueAReadOnlyToken() {
        String totp = code();
        ConfigUiAuthService.LoginResult first = authService.login(PASSWORD.toCharArray(), totp, "192.0.2.1");
        assertTrue(first.success(), "台面：第一次登录应过");

        JSONObject body = new JSONObject();
        body.put("password", PASSWORD);
        body.put("code", totp);
        body.put("label", "面板");
        ResponseEntity<String> response = proxyIssue.issue(body, request());

        assertFalse(response.getStatusCode().is2xxSuccessful(),
                "已用过的动态码代签发必须失败, 实际 HTTP " + response.getStatusCode().value()
                        + " body=" + response.getBody());
    }

    @Test
    @DisplayName("对照：错口令不得把有效动态码提前消费掉")
    void wrongPasswordMustNotConsumeAValidCode() {
        String totp = code();
        ConfigUiAuthService.LoginResult wrong = authService.login("guess".toCharArray(), totp, "203.0.113.10");
        assertFalse(wrong.success(), "口令错应失败");

        ConfigUiAuthService.LoginResult right = authService.login(PASSWORD.toCharArray(), totp, "192.0.2.1");
        assertTrue(right.success(), "口令错的那一次不该烧掉这枚还没用过的码, " + right.message());
        assertNotEquals("guess", PASSWORD);
    }
}
