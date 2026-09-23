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
 * 敏感操作前再核密码：关闭二次验证这一路
 * <p>
 * 抓的用户故障：偷得会话 Cookie、又看到主人登录刚用的那个码的人，能替主人关掉二次验证——
 * 主人以为自己还有第二道防线，其实已经被人卸掉了。
 * <p>
 * 验证码与登录那条路共用「已用过的时间步」那一格：登录用过的码不能再拿来关。
 * 密码错的那一趟不得把码烧掉，否则主人再试会被说成验证码不正确。
 */
@DisplayName("关二次验证前再核密码，登录用过的验证码不能再拿来关")
class SensitiveReauthDisableTest {
    private static final String PASSWORD = "correct horse battery staple";
    private static final String SECRET = "JBSWY3DPEHPK3PXP";

    private static final String TEMPLATE = """
            novabot:
              core:
                config-ui:
                  enabled: true
                  auth:
                    password: %s
                    totp: true
                    totp-secret: %s
            """.formatted(PASSWORD, SECRET);

    @TempDir
    Path dir;

    private Path config;
    private ConfigUiAuthService authService;
    private ConfigUiAuthController controller;

    @BeforeEach
    void setUp() throws IOException {
        config = dir.resolve("application.yml");
        Files.writeString(config, TEMPLATE, StandardCharsets.UTF_8);
        ConfigurationFileService fileService = new ConfigurationFileService(config);

        NovaCoreProperties properties = new NovaCoreProperties();
        NovaCoreProperties.ConfigUi.Auth auth = properties.getConfigUi().getAuth();
        auth.setPassword(PASSWORD);
        auth.setTotp(true);
        auth.setTotpSecret(SECRET);

        authService = new ConfigUiAuthService(auth,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(auth.getMaxFailures(), Duration.ofMinutes(15)), fileService);
        controller = new ConfigUiAuthController(authService, fileService, properties);
    }

    private String totpNow() {
        return TotpGenerator.currentCode(SECRET, Instant.now());
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

    private MockHttpServletRequest withSession(ConfigUiSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.setCookies(new Cookie(ConfigUiSecurityFilter.SESSION_COOKIE, session.getId()));
        return request;
    }

    /**
     * 用启动令牌通道签发一把会话，不动动态码那一格——
     * 同一窗口的动态码登录只能用一次，需要「对而且没用过」的码时不能走登录。
     */
    private MockHttpServletRequest stolenSession() {
        return withSession(authService.issueForOperator("127.0.0.1"));
    }

    @Test
    @DisplayName("🔴 偷到会话、又看到主人登录刚用的那个码：拿这个码关二次验证应被拒，二次验证仍开着，配置文件不变")
    void usedCodeCannotDisable() throws IOException {
        String before = Files.readString(config, StandardCharsets.UTF_8);
        String used = totpNow();
        // 登录这一趟把码烧掉；带上对的密码让下一趟走到验码那步——钉的是码能不能重用，不是密码闸
        ConfigUiAuthService.LoginResult login = authService.login(PASSWORD.toCharArray(), used, "127.0.0.1");
        assertTrue(login.success(), "台面没搭起来: " + login.message());

        ResponseEntity<JSONObject> denied = controller.totpDisable(body(PASSWORD, used), withSession(login.session()));

        assertFalse(denied.getBody().getBooleanValue("success"),
                "登录用过的码不得再拿来关二次验证: " + denied.getBody().toJSONString());
        assertTrue(authService.totpRequired(), "拒了就得照旧要码");
        assertEquals(before, Files.readString(config, StandardCharsets.UTF_8), "拒了就不该动配置文件");
    }

    @Test
    @DisplayName("🔴 偷到会话、手里验证码对而且没用过、但不知道密码：关不掉，配置文件不变")
    void withoutPasswordCannotDisable() throws IOException {
        String before = Files.readString(config, StandardCharsets.UTF_8);

        ResponseEntity<JSONObject> denied = controller.totpDisable(body(null, totpNow()), stolenSession());

        assertEquals(400, denied.getStatusCode().value(), denied.getBody().toJSONString());
        assertFalse(denied.getBody().getBooleanValue("success"), denied.getBody().toJSONString());
        assertEquals("请填现在的密码", denied.getBody().getString("message"),
                "没填要说请填: " + denied.getBody().toJSONString());
        assertTrue(authService.totpRequired(), "拒了就得照旧要码");
        assertEquals(before, Files.readString(config, StandardCharsets.UTF_8), "拒了就不该动配置文件");
    }

    @Test
    @DisplayName("🔴 主人输错一次密码后，用同一个码配对的密码再试：能关掉（密码错的那一趟不得把码烧了）")
    void wrongPasswordDoesNotBurnCode() {
        MockHttpServletRequest stolen = stolenSession();
        String code = totpNow();

        ResponseEntity<JSONObject> denied = controller.totpDisable(body("这不是我的密码", code), stolen);
        assertFalse(denied.getBody().getBooleanValue("success"),
                "密码错应被拒: " + denied.getBody().toJSONString());
        assertTrue(authService.totpRequired(), "拒了就得照旧要码");

        ResponseEntity<JSONObject> disabled = controller.totpDisable(body(PASSWORD, code), stolen);
        assertTrue(disabled.getBody().getBooleanValue("success"),
                "同一个码配对的密码再试应能关掉，密码错的那一趟不得把码烧了: " + disabled.getBody().toJSONString());
        assertFalse(authService.totpRequired(), "关掉之后登录不该再要码");
    }

    @Test
    @DisplayName("先过阳性对照：主人密码对、码是新的：关成")
    void correctPasswordAndFreshCodeSucceeds() {
        ResponseEntity<JSONObject> disabled = controller.totpDisable(body(PASSWORD, totpNow()), stolenSession());

        assertEquals(200, disabled.getStatusCode().value(), disabled.getBody().toJSONString());
        assertTrue(disabled.getBody().getBooleanValue("success"), disabled.getBody().toJSONString());
        assertFalse(authService.totpRequired(), "关掉之后登录不该再要码");
    }
}
