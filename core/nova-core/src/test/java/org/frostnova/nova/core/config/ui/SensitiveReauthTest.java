package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSession;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSessionStore;
import org.frostnova.nova.core.config.ui.auth.LoginThrottle;
import org.frostnova.nova.core.config.ui.auth.passkey.PasskeyService;
import org.frostnova.nova.core.config.ui.auth.passkey.PasskeyStore;
import org.frostnova.nova.core.service.NovaStateStore;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 敏感操作前再核密码：登记通行密钥这一路，以及三路共用连错计次
 * <p>
 * 抓的用户故障：偷得会话 Cookie、不知道密码的人，能给自己登记通行密钥——
 * 这把钥匙下次直接开门，主人那把密码再也挡不住他。
 * <p>
 * 与改密码、绑定验证器共用同一把会话上的连错计次：分开记的话，
 * 偷到 Cookie 的人能在三个口子上各猜 5 次，等于把可猜次数悄悄乘了三。
 */
@DisplayName("登记通行密钥前再核密码")
class SensitiveReauthTest {
    private static final String OLD = "correct horse battery staple";

    private static final String HOST = "localhost:7827";

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
    private ConfigUiAuthController authController;
    private ConfigUiPasskeyController passkeyController;

    @BeforeEach
    void setUp() throws IOException {
        Path config = dir.resolve("application.yml");
        Files.writeString(config, TEMPLATE, StandardCharsets.UTF_8);
        ConfigurationFileService fileService = new ConfigurationFileService(config);

        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        NovaCoreProperties.ConfigUi.Auth auth = properties.getConfigUi().getAuth();
        auth.setPassword(OLD);
        auth.setTotp(false);

        authService = new ConfigUiAuthService(auth,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(auth.getMaxFailures(), Duration.ofMinutes(15)), fileService);
        authController = new ConfigUiAuthController(authService, fileService, properties);

        PasskeyStore store = new PasskeyStore(new NovaStateStore(properties));
        // 再核密码这段与 RP 无关，但登记要发得出挑战就得让 RP 说得上话
        passkeyController = new ConfigUiPasskeyController(
                new PasskeyService(store, authService), properties, authService);
    }

    private MockHttpServletRequest loggedIn() {
        ConfigUiSession session = authService.login(OLD.toCharArray(), null, "127.0.0.1").session();
        return request(session.getId());
    }

    private MockHttpServletRequest request(String sessionId) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/config/api/auth/passkey");
        request.addHeader("Host", HOST);
        request.setRemoteAddr("127.0.0.1");
        if (sessionId != null) {
            request.setCookies(new Cookie(ConfigUiSecurityFilter.SESSION_COOKIE, sessionId));
        }
        return request;
    }

    private JSONObject body(String current) {
        JSONObject body = new JSONObject();
        if (current != null) {
            body.put("current", current);
        }
        return body;
    }

    private String sessionId(MockHttpServletRequest request) {
        for (Cookie cookie : request.getCookies()) {
            if (ConfigUiSecurityFilter.SESSION_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    @Test
    @DisplayName("先过阳性对照：密码对时发得出登记参数")
    void baselineRegisterOptionsWithPassword() {
        ResponseEntity<JSONObject> options = passkeyController.registerOptions(body(OLD), loggedIn());

        assertEquals(200, options.getStatusCode().value(), String.valueOf(options.getBody()));
        JSONObject payload = options.getBody();
        assertNotNull(payload);
        assertTrue(payload.getBooleanValue("success"),
                "阳性对照：发得出挑战，下面几条才说明得了事: " + payload.toJSONString());
        assertTrue(payload.containsKey("challenge"), "阳性对照没发挑战: " + payload.toJSONString());
    }

    @Test
    @DisplayName("🔴 登记通行密钥不带现在的密码：应 400、不发挑战")
    void registerOptionsWithoutPasswordIsRefused() {
        ResponseEntity<JSONObject> denied = passkeyController.registerOptions(body(null), loggedIn());

        // 偷到 Cookie 的人拿不出密码。少了这一步，他能给自己配一把永久钥匙，
        // 主人改掉密码也赶不走他
        assertEquals(400, denied.getStatusCode().value(), String.valueOf(denied.getBody()));
        JSONObject payload = denied.getBody();
        assertNotNull(payload);
        assertFalse(payload.getBooleanValue("success"), payload.toJSONString());
        assertEquals("请填现在的密码", payload.getString("message"), payload.toJSONString());
        assertFalse(payload.containsKey("challenge"), "拒了就不该发挑战: " + payload.toJSONString());
    }

    @Test
    @DisplayName("🔴 登记通行密钥密码错：应 400、不发挑战，并说清还剩几次")
    void registerOptionsWithWrongPasswordIsRefused() {
        ResponseEntity<JSONObject> denied = passkeyController.registerOptions(body("这不是我的密码"), loggedIn());

        assertEquals(400, denied.getStatusCode().value(), String.valueOf(denied.getBody()));
        JSONObject payload = denied.getBody();
        assertNotNull(payload);
        assertFalse(payload.getBooleanValue("success"), payload.toJSONString());
        assertTrue(String.valueOf(payload.getString("message")).contains("再输错 4 次"),
                "错一次要说清还剩几次: " + payload.toJSONString());
        assertFalse(payload.containsKey("challenge"), "拒了就不该发挑战: " + payload.toJSONString());
    }

    @Test
    @DisplayName("🔴 认不出会话时登记通行密钥：应 401，文案点名登记")
    void registerOptionsWithoutSessionIsUnauthorised() {
        ResponseEntity<JSONObject> denied = passkeyController.registerOptions(body(OLD), request(null));

        assertEquals(401, denied.getStatusCode().value(), String.valueOf(denied.getBody()));
        JSONObject payload = denied.getBody();
        assertNotNull(payload);
        assertTrue(String.valueOf(payload.getString("message")).contains("再登记"),
                "文案要按场合说，不能还写着改密码: " + payload.toJSONString());
        assertFalse(payload.containsKey("challenge"), "拒了就不该发挑战: " + payload.toJSONString());
    }

    @Test
    @DisplayName("🔴 三路混着输错共用连错计次：累计第 5 次 401、会话注销")
    void missCountsSharedAcrossRegisterEnrollAndChange() {
        MockHttpServletRequest mine = loggedIn();
        String id = sessionId(mine);
        assertNotNull(id, "台面没搭起来");

        // 两趟改密码、一趟登记、一趟绑定，各错一次
        ResponseEntity<JSONObject> miss1 = authController.changePassword(body("错一"), mine);
        assertEquals(400, miss1.getStatusCode().value(), String.valueOf(miss1.getBody()));
        assertTrue(String.valueOf(miss1.getBody().getString("message")).contains("再输错 4 次"),
                "第一趟错要说还剩 4 次: " + miss1.getBody().toJSONString());

        ResponseEntity<JSONObject> miss2 = authController.changePassword(body("错二"), mine);
        assertEquals(400, miss2.getStatusCode().value(), String.valueOf(miss2.getBody()));
        assertTrue(String.valueOf(miss2.getBody().getString("message")).contains("再输错 3 次"),
                "第二趟错要说还剩 3 次: " + miss2.getBody().toJSONString());

        ResponseEntity<JSONObject> miss3 = passkeyController.registerOptions(body("错三"), mine);
        assertEquals(400, miss3.getStatusCode().value(), String.valueOf(miss3.getBody()));
        assertTrue(String.valueOf(miss3.getBody().getString("message")).contains("再输错 2 次"),
                "登记这一路要记进同一把次数: " + miss3.getBody().toJSONString());

        ResponseEntity<JSONObject> miss4 = authController.totpEnroll(enrollBody("错四"), mine);
        assertEquals(400, miss4.getStatusCode().value(), String.valueOf(miss4.getBody()));
        assertTrue(String.valueOf(miss4.getBody().getString("message")).contains("再输错 1 次"),
                "绑定这一路要记进同一把次数: " + miss4.getBody().toJSONString());

        // 第五次随便走哪一路：满 5 次，这次登录退出
        ResponseEntity<JSONObject> miss5 = passkeyController.registerOptions(body("错五"), mine);
        assertEquals(401, miss5.getStatusCode().value(), String.valueOf(miss5.getBody()));
        assertTrue(String.valueOf(miss5.getBody().getString("message")).contains("连续输错"),
                "满 5 次要说清是连错退出: " + miss5.getBody().toJSONString());

        // 三路分开记的话这里还能再猜一轮；共用计次则当场没了
        assertTrue(authService.validate(id).isEmpty(), "连错满 5 次该注销这把会话");
    }

    private JSONObject enrollBody(String current) {
        JSONObject body = body(current);
        body.put("code", "000000");
        return body;
    }
}
