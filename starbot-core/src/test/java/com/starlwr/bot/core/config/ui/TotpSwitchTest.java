package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.config.ui.auth.ConfigUiAuthService;
import com.starlwr.bot.core.config.ui.auth.ConfigUiSessionStore;
import com.starlwr.bot.core.config.ui.auth.LoginThrottle;
import com.starlwr.bot.core.config.ui.auth.TotpGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 二次验证的开与关
 * <p>
 * 关掉的是一整道防线，而这个动作只需要一次点击。<b>必须先输一次现在的验证码</b>——
 * 一枚被偷走的会话 Cookie 若能直接把它卸掉，那道防线保护的其实只有「口令没泄漏」这一种情形，
 * 而那恰恰是它<b>不</b>负责的那一种。
 * <p>
 * 关掉时密钥一并清掉：留着一个谁也不再用的密钥躺在配置里，下次重新开启时它会被直接沿用，
 * 而使用者以为自己新绑了一把——那把「新」的其实是几个月前那把，中间它一直明文躺在盘上。
 */
@DisplayName("二次验证开关")
class TotpSwitchTest {
    private static final String PASSWORD = "correct horse battery staple";

    private static final String SECRET = "JBSWY3DPEHPK3PXP";

    private static final String TEMPLATE = """
            starbot:
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
    private ConfigurationFileService fileService;
    private ConfigUiAuthService authService;
    private ConfigUiAuthController controller;

    @BeforeEach
    void setUp() throws IOException {
        config = dir.resolve("application.yml");
        Files.writeString(config, TEMPLATE, StandardCharsets.UTF_8);
        fileService = new ConfigurationFileService(config);

        StarBotCoreProperties properties = new StarBotCoreProperties();
        StarBotCoreProperties.ConfigUi.Auth auth = properties.getConfigUi().getAuth();
        auth.setPassword(PASSWORD);
        auth.setTotp(true);
        auth.setTotpSecret(SECRET);

        authService = new ConfigUiAuthService(auth,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(auth.getMaxFailures(), Duration.ofMinutes(15)), fileService);
        controller = new ConfigUiAuthController(authService, fileService, properties);
    }

    private JSONObject code(String value) {
        JSONObject body = new JSONObject();
        body.put("code", value);
        return body;
    }

    @Test
    @DisplayName("先过阳性对照：一开始二次验证确实开着，且登录真的要码")
    void baselineRequiresCode() {
        assertTrue(authService.totpRequired(), "阳性对照：这一条不成立，下面几条都说明不了任何事");
        assertFalse(authService.login(PASSWORD.toCharArray(), "000000", "1.2.3.4").success(),
                "错码本来就该登不上");
        assertTrue(authService.login(PASSWORD.toCharArray(),
                        TotpGenerator.currentCode(SECRET, Instant.now()), "1.2.3.5").success(),
                "对码本来就该登得上");
    }

    @Test
    @DisplayName("🔴 关二次验证时验证码不对，一律拒；开关与密钥都不动")
    void wrongCodeCannotDisable() throws IOException {
        String before = Files.readString(config, StandardCharsets.UTF_8);

        ResponseEntity<JSONObject> response = controller.totpDisable(code("000000"));

        assertEquals(401, response.getStatusCode().value());
        assertFalse(response.getBody().getBooleanValue("success"));
        assertTrue(authService.totpRequired(), "拒了就得照旧要码");
        assertEquals(before, Files.readString(config, StandardCharsets.UTF_8), "拒了就不该动配置文件");
    }

    @Test
    @DisplayName("码不填、填成别的形状，同样拒")
    void malformedCodeCannotDisable() {
        assertEquals(401, controller.totpDisable(code(null)).getStatusCode().value());
        assertEquals(401, controller.totpDisable(code("")).getStatusCode().value());
        assertEquals(401, controller.totpDisable(code("abcdef")).getStatusCode().value());
        assertTrue(authService.totpRequired(), "三次都拒之后仍然要码");
    }

    @Test
    @DisplayName("填对现在的码才关得掉，密钥一并清干净")
    void correctCodeDisablesAndClearsSecret() throws IOException {
        ResponseEntity<JSONObject> response =
                controller.totpDisable(code(TotpGenerator.currentCode(SECRET, Instant.now())));

        assertEquals(200, response.getStatusCode().value(), response.getBody().toJSONString());
        assertFalse(authService.totpRequired(), "关掉之后登录不该再要码");
        assertFalse(authService.totpEnabled(), "开关这一位也要跟着落下去");
        assertTrue(authService.login(PASSWORD.toCharArray(), null, "1.2.3.7").success(),
                "关掉之后只凭口令就该进得来");

        String stored = fileService.read().get("starbot.core.config-ui.auth.totp-secret");
        assertTrue(stored == null || stored.isBlank(), "密钥该清干净，实为: " + stored);
        assertEquals("false", fileService.read().get("starbot.core.config-ui.auth.totp"));
        assertFalse(Files.readString(config, StandardCharsets.UTF_8).contains(SECRET),
                "文件里不该再留着那把密钥");
    }

    @Test
    @DisplayName("已经关着的时候再关一次，说清「本来就没开」而不是假装办成了")
    void disablingTwiceIsHonest() {
        controller.totpDisable(code(TotpGenerator.currentCode(SECRET, Instant.now())));

        ResponseEntity<JSONObject> again = controller.totpDisable(code("000000"));
        assertEquals(400, again.getStatusCode().value());
        assertFalse(again.getBody().getBooleanValue("success"));
    }

    @Test
    @DisplayName("关掉之后还能重新绑一把：绑定这条路不看开关那一位")
    void canEnrollAgainAfterDisabling() {
        controller.totpDisable(code(TotpGenerator.currentCode(SECRET, Instant.now())));

        // 关掉之后开关是 false，若绑定这条路以它为前提，人得先重启一次才绑得了，
        // 而重启会断开全部直播间长连接
        assertTrue(authService.canEnrollTotp(), "关掉之后应当能重新走一遍绑定");
        JSONObject setup = controller.totpSetup();
        assertTrue(setup.getBooleanValue("success"), setup.toJSONString());
        assertNull(setup.getString("message"));

        String pending = setup.getString("secret");
        JSONObject enrolled = controller.totpEnroll(code(TotpGenerator.currentCode(pending, Instant.now())));
        assertTrue(enrolled.getBooleanValue("success"), enrolled.toJSONString());
        assertTrue(authService.totpRequired(), "绑好之后应当当场要码");
        assertTrue(authService.totpEnabled(), "绑定本身就是「我要用二次验证」的意思");
    }
}
