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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 绑定时核过的那枚码不能再拿来登录
 * <p>
 * 抓的用户故障：看到或截到绑定时那枚码的人，口令也对时，拿它在同一格里再登录一次。
 * 绑定时核码只比对、不记用过的格，而绑成那一步把已用过的格全清了——
 * 于是那枚码在它那一格（含漂移窗口）里还登得进一次，与「同一格只能用一次」的规矩相反。
 * <p>
 * 记格只在绑定真正办成之后：码不对、存盘失败那两趟不烧码，
 * 否则用户在同一格里连重试的机会都没有，只能干等半分钟。
 * 被挡下的码照旧回「密码或验证码不正确」、照旧记一次失败——
 * 另说「码已用过」等于告诉对方口令是对的，剩下的就只是六位数字。
 */
@DisplayName("绑定验证器核过的那枚码不能再拿来登录")
class TotpEnrollCodeMustNotLogInAgainTest {
    private static final String PASSWORD = "correct horse battery staple";

    @TempDir
    Path dir;

    private Path config;

    private ConfigurationFileService fileService;

    private ConfigUiAuthService authService;

    private ConfigUiAuthController controller;

    private NovaCoreProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        config = dir.resolve("application.yml");
        Files.writeString(config, """
                novabot:
                  core:
                    config-ui:
                      enabled: true
                      auth:
                        password: %s
                        totp: false
                """.formatted(PASSWORD), StandardCharsets.UTF_8);
        fileService = new ConfigurationFileService(config);
        properties = new NovaCoreProperties();
        properties.getConfigUi().getAuth().setPassword(PASSWORD);
        properties.getConfigUi().getAuth().setTotp(false);

        authService = new ConfigUiAuthService(properties.getConfigUi().getAuth(),
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(properties.getConfigUi().getAuth().getMaxFailures(), Duration.ofMinutes(15)),
                fileService);
        controller = new ConfigUiAuthController(authService, fileService, properties);
    }

    private MockHttpServletRequest loggedIn() {
        ConfigUiSession session = authService.login(PASSWORD.toCharArray(), null, "127.0.0.1").session();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.setCookies(new Cookie(ConfigUiSecurityFilter.SESSION_COOKIE, session.getId()));
        return request;
    }

    private JSONObject enrollBody(String code) {
        JSONObject body = new JSONObject();
        body.put("current", PASSWORD);
        body.put("code", code);
        return body;
    }

    private String pendingSecret(MockHttpServletRequest request) {
        JSONObject setup = controller.totpSetup(request);
        assertTrue(setup.getBooleanValue("success"), "台面没搭起来: " + setup.toJSONString());
        return setup.getString("secret");
    }

    private void assertEnrolled(ResponseEntity<JSONObject> response) {
        assertEquals(200, response.getStatusCode().value(), response.getBody().toJSONString());
        assertTrue(response.getBody().getBooleanValue("success"), response.getBody().toJSONString());
        assertTrue(authService.totpRequired(), "绑上了就该当场要码");
    }

    @Test
    @DisplayName("🔴 绑定核过的那枚码再拿来登录：必须被拒，提示句与口令错一字不差")
    void sameCodeAfterEnrollMustNotLogIn() {
        String wrongPasswordMessage =
                authService.login("猜的".toCharArray(), null, "203.0.113.10").message();
        MockHttpServletRequest mine = loggedIn();
        String secret = pendingSecret(mine);
        String used = TotpGenerator.currentCode(secret, Instant.now());
        assertEnrolled(controller.totpEnroll(enrollBody(used), mine));

        ConfigUiAuthService.LoginResult replay = authService.login(PASSWORD.toCharArray(), used, "192.0.2.1");

        assertFalse(replay.success(),
                "绑定时核过的那枚码还能再登一次: " + replay.message()
                        + " session=" + (replay.session() == null ? "null" : replay.session().getId()));
        assertEquals(wrongPasswordMessage, replay.message(),
                "被挡下的码与口令错得说成同一句话，否则对方知道口令已经猜对了");
    }

    @Test
    @DisplayName("🔴 被挡下的那一趟照旧记一次失败：堵死的那格不该是不计次的猜测口")
    void blockedCodeStillCountsAsAFailure() {
        int max = new NovaCoreProperties.ConfigUi.Auth().getMaxFailures();
        MockHttpServletRequest mine = loggedIn();
        String secret = pendingSecret(mine);
        String used = TotpGenerator.currentCode(secret, Instant.now());
        assertEnrolled(controller.totpEnroll(enrollBody(used), mine));

        for (int i = 0; i < max; i++) {
            ConfigUiAuthService.LoginResult attempt = authService.login(PASSWORD.toCharArray(), used, "192.0.2.1");
            assertFalse(attempt.success(), "第 " + (i + 1) + " 次拿核过的那枚码登录必须失败: " + attempt.message());
        }

        // 换一枚没用过的码也进不来：上面几趟若不记失败，这里就成了不计次的猜测口
        String fresh = TotpGenerator.currentCode(secret, Instant.now().plusSeconds(30));
        ConfigUiAuthService.LoginResult after = authService.login(PASSWORD.toCharArray(), fresh, "192.0.2.1");
        assertFalse(after.success(), "被挡下的几趟必须记成失败，否则堵死的那格成了猜口令的免计数口");
        assertFalse(after.retryAfter().isZero(), "锁没锁、还要等多久得说清");
    }

    @Test
    @DisplayName("绑定之后下一格的码照常登得进，不能把刚绑好验证器的人挡在门外")
    void nextStepCodeStillLogsIn() {
        MockHttpServletRequest mine = loggedIn();
        String secret = pendingSecret(mine);
        String used = TotpGenerator.currentCode(secret, Instant.now());
        assertEnrolled(controller.totpEnroll(enrollBody(used), mine));

        String next = TotpGenerator.currentCode(secret, Instant.now().plusSeconds(30));
        ConfigUiAuthService.LoginResult login = authService.login(PASSWORD.toCharArray(), next, "192.0.2.2");
        assertTrue(login.success(), "下一格的码照常登得进: " + login.message());
    }

    @Test
    @DisplayName("🔴 码不对那一趟不烧码：同一格里重试绑得成")
    void wrongEnrollCodeMustNotConsumeTheStep() {
        MockHttpServletRequest mine = loggedIn();
        String secret = pendingSecret(mine);
        String code = TotpGenerator.currentCode(secret, Instant.now());

        ResponseEntity<JSONObject> wrong = controller.totpEnroll(enrollBody("000000"), mine);
        assertEquals(400, wrong.getStatusCode().value(), wrong.getBody().toJSONString());

        assertEnrolled(controller.totpEnroll(enrollBody(code), mine));
    }

    @Test
    @DisplayName("🔴 存盘失败那一趟不烧码：同一格里拿同一枚码重试绑得成")
    void saveFailureMustNotConsumeTheStep() throws IOException {
        ConfigurationFileService broken = new ConfigurationFileService(config) {
            @Override
            public synchronized List<String> write(Map<String, String> changes) throws IOException {
                throw new IOException("磁盘满了");
            }
        };
        ConfigUiAuthController brokenController = new ConfigUiAuthController(authService, broken, properties);
        MockHttpServletRequest mine = loggedIn();
        String secret = pendingSecret(mine);
        String code = TotpGenerator.currentCode(secret, Instant.now());

        ResponseEntity<JSONObject> failed = brokenController.totpEnroll(enrollBody(code), mine);
        assertEquals(500, failed.getStatusCode().value(), failed.getBody().toJSONString());

        assertEnrolled(controller.totpEnroll(enrollBody(code), mine));
    }
}
