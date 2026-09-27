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
 * 绑定验证器出错时的回码，与关掉那一路同一个口径
 * <p>
 * 抓的用户故障：绑定输错码回的是 200＋success:false，而关掉那一路同一种错回 400——
 * 同一类错误两个回码，照回码写的限流、告警与前端分支只会照到一半，出事那天才发现另一半没照。
 * <p>
 * 关掉那一路的四种失败是 400／400／400／500（无需开着、被锁、码不对、存盘失败），
 * 绑定这一路逐一对齐。码不对、被锁回 400 而不是 401：界面上凡 401 一律整页重载，
 * 提示句来不及显示。回包内容（success、message、lockedSeconds）一个字段都不动。
 */
@DisplayName("绑定验证器出错的回码与关闭那一路一致")
class TotpEnrollFailureStatusTest {
    private static final String PASSWORD = "correct horse battery staple";

    private static final String SECRET = "JBSWY3DPEHPK3PXP";

    @TempDir
    Path dir;

    private Path config;

    private ConfigurationFileService fileService;

    private ConfigUiAuthService authService;

    private ConfigUiAuthController controller;

    private NovaCoreProperties properties;

    /**
     * 没绑验证器的台面：绑定这条路走得通，量得到被锁、码不对、存盘失败三格
     */
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

    private JSONObject enrollBody(String current, String code) {
        JSONObject body = new JSONObject();
        body.put("current", current);
        body.put("code", code);
        return body;
    }

    /**
     * 待绑密钥与它那一格的码，配一个已经握着会话的请求
     */
    private String pendingSecret(MockHttpServletRequest request) {
        JSONObject setup = controller.totpSetup(request);
        assertTrue(setup.getBooleanValue("success"), "台面没搭起来: " + setup.toJSONString());
        return setup.getString("secret");
    }

    @Test
    @DisplayName("🔴 已经绑着验证器时再绑：应 400「无需绑定验证器」，与关着再关那一路同形")
    void alreadyBoundIsRefusedAsBadRequest() throws IOException {
        // 这一格的前提是「已经绑上了」，与上面那套没绑的台面相反，另起一个
        Files.writeString(config, """
                novabot:
                  core:
                    config-ui:
                      enabled: true
                      auth:
                        password: %s
                        totp: true
                        totp-secret: %s
                """.formatted(PASSWORD, SECRET), StandardCharsets.UTF_8);
        NovaCoreProperties bound = new NovaCoreProperties();
        bound.getConfigUi().getAuth().setPassword(PASSWORD);
        bound.getConfigUi().getAuth().setTotp(true);
        bound.getConfigUi().getAuth().setTotpSecret(SECRET);
        ConfigUiAuthService boundAuth = new ConfigUiAuthService(bound.getConfigUi().getAuth(),
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(bound.getConfigUi().getAuth().getMaxFailures(), Duration.ofMinutes(15)),
                fileService);
        ConfigUiAuthController boundController = new ConfigUiAuthController(boundAuth, fileService, bound);
        assertFalse(boundAuth.canEnrollTotp(), "台面：已经绑着就不该再走绑定这条路");

        MockHttpServletRequest mine = new MockHttpServletRequest();
        mine.setRemoteAddr("127.0.0.1");
        mine.setCookies(new Cookie(ConfigUiSecurityFilter.SESSION_COOKIE,
                boundAuth.login(PASSWORD.toCharArray(), TotpGenerator.currentCode(SECRET, Instant.now().plusSeconds(30)),
                        "127.0.0.1").session().getId()));

        ResponseEntity<JSONObject> denied = boundController.totpEnroll(enrollBody(PASSWORD, "000000"), mine);

        assertEquals(400, denied.getStatusCode().value(), denied.getBody().toJSONString());
        assertFalse(denied.getBody().getBooleanValue("success"), denied.getBody().toJSONString());
        assertEquals("无需绑定验证器", denied.getBody().getString("message"),
                "提示句一个字不动: " + denied.getBody().toJSONString());
        assertTrue(boundAuth.totpRequired(), "拒了就不该动开关");
    }

    @Test
    @DisplayName("🔴 绑定猜码猜到锁定：应 400，说清要等多久，绑不上")
    void lockedOutIsRefusedAsBadRequest() {
        MockHttpServletRequest mine = loggedIn();
        String pending = pendingSecret(mine);
        int max = new NovaCoreProperties.ConfigUi.Auth().getMaxFailures();

        for (int i = 0; i < max; i++) {
            ResponseEntity<JSONObject> wrong = controller.totpEnroll(enrollBody(PASSWORD, "000000"), mine);
            assertFalse(wrong.getBody().getBooleanValue("success"), "第 " + (i + 1) + " 次错码应拒: " + wrong.getBody());
        }

        ResponseEntity<JSONObject> locked = controller.totpEnroll(
                enrollBody(PASSWORD, TotpGenerator.currentCode(pending, Instant.now())), mine);

        // 与关掉那一路一样回 400：回 401 的话整页重载，「尝试次数过多」与还要等多久都来不及显示
        assertEquals(400, locked.getStatusCode().value(), locked.getBody().toJSONString());
        assertFalse(locked.getBody().getBooleanValue("success"), locked.getBody().toJSONString());
        assertTrue(String.valueOf(locked.getBody().getString("message")).contains("尝试次数过多"),
                "得说清是试太多次被锁了: " + locked.getBody().toJSONString());
        assertTrue(locked.getBody().getLongValue("lockedSeconds") > 0,
                "锁定期内应带剩余秒数, 实际 " + locked.getBody().toJSONString());
        assertFalse(authService.totpRequired(), "锁定期内不该把二次验证绑上");
    }

    @Test
    @DisplayName("🔴 绑定验证码不对：应 400，提示句照旧，还带剩余锁定秒数")
    void wrongCodeIsRefusedAsBadRequest() {
        MockHttpServletRequest mine = loggedIn();
        pendingSecret(mine);

        ResponseEntity<JSONObject> denied = controller.totpEnroll(enrollBody(PASSWORD, "000000"), mine);

        assertEquals(400, denied.getStatusCode().value(), denied.getBody().toJSONString());
        assertFalse(denied.getBody().getBooleanValue("success"), denied.getBody().toJSONString());
        assertEquals("验证码不正确，请确认手机时间是否准确后重试", denied.getBody().getString("message"),
                "提示句一个字不动: " + denied.getBody().toJSONString());
        assertTrue(denied.getBody().containsKey("lockedSeconds"), "照旧带剩余锁定秒数: " + denied.getBody().toJSONString());
        assertFalse(authService.totpRequired(), "拒了就不该把二次验证绑上");
    }

    @Test
    @DisplayName("🔴 密钥写不进文件：应 500，如实说保存失败，二次验证不绑")
    void saveFailureIsReportedAsServerError() throws IOException {
        ConfigurationFileService broken = new ConfigurationFileService(config) {
            @Override
            public synchronized List<String> write(Map<String, String> changes) throws IOException {
                throw new IOException("磁盘满了");
            }
        };
        ConfigUiAuthController brokenController = new ConfigUiAuthController(authService, broken, properties);
        MockHttpServletRequest mine = loggedIn();
        String pending = pendingSecret(mine);

        ResponseEntity<JSONObject> failed = brokenController.totpEnroll(
                enrollBody(PASSWORD, TotpGenerator.currentCode(pending, Instant.now())), mine);

        // 存盘失败回 500 而不是 200：回 200 会被当成功写进监控，而这一趟其实什么都没存下
        assertEquals(500, failed.getStatusCode().value(), failed.getBody().toJSONString());
        assertFalse(failed.getBody().getBooleanValue("success"), failed.getBody().toJSONString());
        assertTrue(String.valueOf(failed.getBody().getString("message")).contains("保存失败"),
                "得说清是保存失败: " + failed.getBody().toJSONString());
        assertFalse(authService.totpRequired(), "没存下就不该认这把密钥");
    }
}
