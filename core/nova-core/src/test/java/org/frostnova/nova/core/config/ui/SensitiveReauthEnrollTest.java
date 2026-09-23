package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSession;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSessionStore;
import org.frostnova.nova.core.config.ui.auth.LoginThrottle;
import org.frostnova.nova.core.config.ui.auth.TotpGenerator;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 敏感操作前再核密码：绑定验证器这一路
 * <p>
 * 抓的用户故障：偷得会话 Cookie、不知道密码的人，能替主人开二次验证——
 * 主人下次登录就被自己的二次验证挡在门外，而他手上那把密码看起来只是「突然多了一步」。
 * <p>
 * 判定到回包的那段与改口令共用，连错次数记在同一把会话上；详见
 * {@link SensitiveReauthTest}（登记通行密钥）与那里的共用计次格。
 */
@DisplayName("绑定验证器前再核密码")
class SensitiveReauthEnrollTest {
    private static final String OLD = "correct horse battery staple";

    private static final String TEMPLATE = """
            novabot:
              core:
                config-ui:
                  enabled: true
                  auth:
                    password: %s
                    totp: false
            """.formatted(OLD);

    @TempDir
    Path dir;

    private ConfigUiAuthService authService;
    private ConfigUiAuthController controller;

    @BeforeEach
    void setUp() throws IOException {
        Path config = dir.resolve("application.yml");
        Files.writeString(config, TEMPLATE, StandardCharsets.UTF_8);
        ConfigurationFileService fileService = new ConfigurationFileService(config);

        NovaCoreProperties properties = new NovaCoreProperties();
        NovaCoreProperties.ConfigUi.Auth auth = properties.getConfigUi().getAuth();
        auth.setPassword(OLD);
        auth.setTotp(false);

        authService = new ConfigUiAuthService(auth,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(auth.getMaxFailures(), Duration.ofMinutes(15)), fileService);
        controller = new ConfigUiAuthController(authService, fileService, properties);
    }

    private MockHttpServletRequest loggedIn() {
        ConfigUiSession session = authService.login(OLD.toCharArray(), null, "127.0.0.1").session();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.setCookies(new Cookie(ConfigUiSecurityFilter.SESSION_COOKIE, session.getId()));
        return request;
    }

    private JSONObject body(String current, String totpCode) {
        JSONObject body = new JSONObject();
        if (current != null) {
            body.put("current", current);
        }
        if (totpCode != null) {
            body.put("code", totpCode);
        }
        return body;
    }

    private String pendingSecret(MockHttpServletRequest request) {
        JSONObject setup = controller.totpSetup(request);
        assertTrue(setup.getBooleanValue("success"), "台面没搭起来: " + setup.toJSONString());
        return setup.getString("secret");
    }

    @Test
    @DisplayName("先过阳性对照：密码对时绑得上，绑完才要码")
    void baselineEnrollWithPassword() {
        MockHttpServletRequest mine = loggedIn();
        String pending = pendingSecret(mine);

        ResponseEntity<JSONObject> enrolled = controller.totpEnroll(
                body(OLD, TotpGenerator.currentCode(pending, Instant.now())), mine);

        assertEquals(200, enrolled.getStatusCode().value(), enrolled.getBody().toJSONString());
        assertTrue(enrolled.getBody().getBooleanValue("success"), enrolled.getBody().toJSONString());
        assertTrue(authService.totpRequired(), "阳性对照：绑上之后登录该要码，下面几条才说明得了事");
    }

    @Test
    @DisplayName("🔴 绑定验证器不带现在的密码：应 400、不绑定")
    void enrollWithoutPasswordIsRefused() {
        MockHttpServletRequest mine = loggedIn();
        String pending = pendingSecret(mine);

        ResponseEntity<JSONObject> denied = controller.totpEnroll(
                body(null, TotpGenerator.currentCode(pending, Instant.now())), mine);

        // 偷到 Cookie 的人拿不出密码。少了这一步，他能替主人开二次验证，
        // 主人下次登录被挡在自己的验证器外头
        assertEquals(400, denied.getStatusCode().value(), denied.getBody().toJSONString());
        assertFalse(denied.getBody().getBooleanValue("success"), denied.getBody().toJSONString());
        assertEquals("请填现在的密码", denied.getBody().getString("message"),
                "没填要说请填: " + denied.getBody().toJSONString());
        assertTrue(authService.canEnrollTotp(), "拒了就不该绑上");
        assertFalse(authService.totpRequired(), "拒了就不该要码");
    }

    @Test
    @DisplayName("🔴 绑定验证器密码错：应 400、不绑定，并说清还剩几次")
    void enrollWithWrongPasswordIsRefused() {
        MockHttpServletRequest mine = loggedIn();
        String pending = pendingSecret(mine);

        ResponseEntity<JSONObject> denied = controller.totpEnroll(
                body("这不是我的密码", TotpGenerator.currentCode(pending, Instant.now())), mine);

        assertEquals(400, denied.getStatusCode().value(), denied.getBody().toJSONString());
        assertFalse(denied.getBody().getBooleanValue("success"), denied.getBody().toJSONString());
        assertTrue(String.valueOf(denied.getBody().getString("message")).contains("再输错 4 次"),
                "错一次要说清还剩几次: " + denied.getBody().toJSONString());
        assertTrue(authService.canEnrollTotp(), "拒了就不该绑上");
        assertFalse(authService.totpRequired(), "拒了就不该要码");
    }

    @Test
    @DisplayName("🔴 认不出会话时绑定验证器：应 401，文案点名绑定")
    void enrollWithoutSessionIsUnauthorised() {
        // 待绑密钥挂在会话上，无会话的请求根本到不了验码那步；这里借一把会话只为凑个像样的码
        String pending = pendingSecret(loggedIn());
        MockHttpServletRequest bare = new MockHttpServletRequest();
        bare.setRemoteAddr("127.0.0.1");

        ResponseEntity<JSONObject> denied = controller.totpEnroll(
                body(OLD, TotpGenerator.currentCode(pending, Instant.now())), bare);

        assertEquals(401, denied.getStatusCode().value(), denied.getBody().toJSONString());
        assertTrue(String.valueOf(denied.getBody().getString("message")).contains("再绑定验证器"),
                "文案要按场合说，不能还写着改密码: " + denied.getBody().toJSONString());
        assertTrue(authService.canEnrollTotp(), "拒了就不该绑上");
    }

    @Test
    @DisplayName("🔴 没填密码不算猜：连点几次提交不把人推出登录")
    void missingPasswordIsNotCounted() {
        MockHttpServletRequest mine = loggedIn();
        String pending = pendingSecret(mine);

        for (int i = 1; i <= ConfigUiAuthService.CURRENT_PASSWORD_MISSES_BEFORE_SIGN_OUT; i++) {
            ResponseEntity<JSONObject> denied = controller.totpEnroll(
                    body("", TotpGenerator.currentCode(pending, Instant.now())), mine);
            assertEquals(400, denied.getStatusCode().value(), denied.getBody().toJSONString());
        }

        String id = mine.getCookies()[0].getValue();
        assertTrue(authService.validate(id).isPresent(), "没填不是猜错：连点几次提交就被退出登录，手滑的人吃不消");
    }
}
