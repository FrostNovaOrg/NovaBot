package com.starlwr.bot.core.config.ui.auth.passkey;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.NovaCoreProperties;
import com.starlwr.bot.core.config.ui.ConfigUiPasskeyController;
import com.starlwr.bot.core.config.ui.auth.ConfigUiAuthService;
import com.starlwr.bot.core.config.ui.auth.ConfigUiSessionStore;
import com.starlwr.bot.core.config.ui.auth.LoginThrottle;
import com.starlwr.bot.core.config.ui.auth.TotpGenerator;
import com.starlwr.bot.core.service.StarBotStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 二次验证开着时，通行密钥登录不得只靠「人在场」
 * <p>
 * 口令那条路此时要动态码。通行密钥若只验 UP、不验 UV，一把只需触摸的安全钥匙
 * 就能单独登入，二次验证形同没开。
 */
@DisplayName("二次验证开启时通行密钥登录必须确认使用者身份")
class PasskeyMustNotSkipUserVerificationWhenTotpEnabledTest {
    private static final String PASSWORD = "correct horse battery staple";

    private static final String SECRET = "JBSWY3DPEHPK3PXP";

    private static final String HOST = "localhost:7827";

    private static final String RP_ID = "localhost";

    private static final String ORIGIN = "http://localhost:7827";

    private static final String CLIENT_IP = "127.0.0.1";

    @TempDir
    Path directory;

    private ConfigUiAuthService authService;

    private ConfigUiPasskeyController controller;

    @BeforeEach
    void setUp() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(directory.resolve("data.json").toString());

        NovaCoreProperties.ConfigUi.Auth auth = properties.getConfigUi().getAuth();
        auth.setPassword(PASSWORD);
        auth.setTotp(true);
        auth.setTotpSecret(SECRET);

        PasskeyStore store = new PasskeyStore(new StarBotStateStore(properties));
        authService = new ConfigUiAuthService(auth,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(auth.getMaxFailures(), Duration.ofMinutes(15)), null);
        controller = new ConfigUiPasskeyController(new PasskeyService(store, authService), properties);
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/config/api/auth/passkey");
        request.addHeader("Host", HOST);
        request.setRemoteAddr(CLIENT_IP);
        return request;
    }

    private TestAuthenticator registered() {
        TestAuthenticator authenticator = new TestAuthenticator(TestAuthenticator.ES256);
        JSONObject options = controller.registerOptions(request());
        JSONObject result = controller.registerVerify(
                authenticator.register(options.getString("challenge"), ORIGIN, RP_ID, "安全钥匙", 7), request());
        assertTrue(result.getBooleanValue("success"), "台面：登记应成功, " + result.getString("message"));
        return authenticator;
    }

    private String loginChallenge() {
        JSONObject options = controller.loginOptions(request());
        assertTrue(options.getBooleanValue("success"), "台面：应能取出登录挑战, " + options.getString("message"));
        return options.getString("challenge");
    }

    @Test
    @DisplayName("台面：二次验证开着，口令路径没有动态码进不来")
    void passwordPathStillRequiresTotp() {
        assertTrue(authService.totpRequired());
        assertFalse(authService.login(PASSWORD.toCharArray(), null, "203.0.113.8").success(),
                "口令路径在二次验证开着时应要动态码");
        assertTrue(authService.login(PASSWORD.toCharArray(),
                TotpGenerator.currentCode(SECRET, Instant.now()), "203.0.113.9").success(),
                "口令加动态码应仍能登入");
    }

    @Test
    @DisplayName("对照：没确认人在场的断言仍应被拒")
    void stillRejectsMissingUserPresence() {
        TestAuthenticator authenticator = registered();

        ResponseEntity<JSONObject> response = controller.loginVerify(
                authenticator.assertion(loginChallenge(), ORIGIN, RP_ID, 8, 0), request());

        assertEquals(401, response.getStatusCode().value(), "标志位为 0 应拒");
        assertFalse(response.getBody().getBooleanValue("success"));
    }

    @Test
    @DisplayName("二次验证开着时，合法签名但只有人在场、没有使用者验证，必须拒")
    void mustRejectUserPresentWithoutUserVerificationWhenTotpIsOn() {
        TestAuthenticator authenticator = registered();
        assertTrue(authService.totpRequired(), "本条的前提：二次验证开着");

        ResponseEntity<JSONObject> response = controller.loginVerify(
                authenticator.assertion(loginChallenge(), ORIGIN, RP_ID, 8, TestAuthenticator.FLAG_USER_PRESENT),
                request());

        assertEquals(401, response.getStatusCode().value(),
                "UP=1 UV=0 在二次验证开着时必须拒, 实际 HTTP " + response.getStatusCode().value()
                        + " success=" + response.getBody().getBooleanValue("success"));
        assertFalse(response.getBody().getBooleanValue("success"),
                "不得签发会话: " + response.getBody());
    }

    @Test
    @DisplayName("对照：人在场且已验证使用者时，通行密钥仍应能登入")
    void userVerifiedAssertionStillLogsIn() {
        TestAuthenticator authenticator = registered();

        ResponseEntity<JSONObject> response = controller.loginVerify(
                authenticator.assertion(loginChallenge(), ORIGIN, RP_ID, 8), request());

        assertEquals(200, response.getStatusCode().value(),
                "UV=1 的合法断言应通过, 实际 " + response.getStatusCode().value()
                        + " " + response.getBody());
        assertTrue(response.getBody().getBooleanValue("success"));
    }
}
