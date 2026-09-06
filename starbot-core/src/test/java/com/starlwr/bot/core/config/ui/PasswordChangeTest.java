package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.config.ui.auth.ConfigUiAuthService;
import com.starlwr.bot.core.config.ui.auth.ConfigUiSession;
import com.starlwr.bot.core.config.ui.auth.ConfigUiSessionStore;
import com.starlwr.bot.core.config.ui.auth.LoginThrottle;
import com.starlwr.bot.core.config.ui.auth.PasswordHash;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 改口令与「用启动令牌重设口令」
 * <p>
 * 三件事各守一条：
 * <ul>
 *   <li><b>旧口令要对</b>——一枚被偷走的会话 Cookie 若能直接换掉口令，
 *       真正的主人就被锁在了门外，而他手上那把口令看起来只是「突然不对了」</li>
 *   <li><b>改完当场生效</b>——旧的登不上、新的登得上。只写文件不改内存的话，
 *       界面说「已改」而门上认的还是旧那把，且这件事要到下次重启才暴露</li>
 *   <li><b>免旧口令那条路只给令牌会话</b>——放开给每一把会话，
 *       等于把「偷一枚 Cookie」升级成「拿走这台面板」</li>
 * </ul>
 */
@DisplayName("改口令")
class PasswordChangeTest {
    private static final String OLD = "correct horse battery staple";

    private static final String NEW = "another horse another staple";

    private static final String TEMPLATE = """
            starbot:
              core:
                config-ui:
                  enabled: true
                  auth:
                    password: %s
                    totp: false
            """.formatted(OLD);

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
        auth.setPassword(OLD);
        auth.setTotp(false);

        authService = new ConfigUiAuthService(auth,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(auth.getMaxFailures(), Duration.ofMinutes(15)), fileService);
        controller = new ConfigUiAuthController(authService, fileService, properties);
    }

    /**
     * 造一个带会话 Cookie 的请求
     * @param channel 这把会话是从哪条通道来的，null 表示不带会话
     */
    private MockHttpServletRequest request(ConfigUiSession.Channel channel) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                ConfigUiController.BASE_PATH + "/api/auth/password/change");
        request.setRemoteAddr("127.0.0.1");

        if (channel == ConfigUiSession.Channel.OPERATOR_TOKEN) {
            ConfigUiSession session = authService.issueForOperator("127.0.0.1");
            request.setCookies(new Cookie(ConfigUiSecurityFilter.SESSION_COOKIE, session.getId()));
        } else if (channel != null) {
            ConfigUiSession session = authService.login(OLD.toCharArray(), null, "127.0.0.1").session();
            request.setCookies(new Cookie(ConfigUiSecurityFilter.SESSION_COOKIE, session.getId()));
        }

        return request;
    }

    private JSONObject body(String current, String next) {
        JSONObject body = new JSONObject();
        if (current != null) {
            body.put("current", current);
        }
        if (next != null) {
            body.put("next", next);
        }
        return body;
    }

    @Test
    @DisplayName("先过阳性对照：改之前，旧口令登得上、新口令登不上")
    void baselineBeforeChange() {
        assertTrue(authService.login(OLD.toCharArray(), null, "1.2.3.4").success(),
                "阳性对照：这一条不成立，下面几条都说明不了任何事");
        assertFalse(authService.login(NEW.toCharArray(), null, "1.2.3.5").success());
    }

    @Test
    @DisplayName("🔴 旧口令不对就拒，配置文件一个字不动")
    void wrongCurrentPasswordIsRejected() throws IOException {
        String before = Files.readString(config, StandardCharsets.UTF_8);

        ResponseEntity<JSONObject> response =
                controller.changePassword(body("这不是我的口令", NEW), request(ConfigUiSession.Channel.PASSWORD));

        assertEquals(401, response.getStatusCode().value());
        assertFalse(response.getBody().getBooleanValue("success"));
        assertEquals(before, Files.readString(config, StandardCharsets.UTF_8), "拒了就不该动配置文件");
        assertTrue(authService.login(OLD.toCharArray(), null, "1.2.3.4").success(), "旧口令仍然管用");
    }

    @Test
    @DisplayName("🔴 改完之后：旧口令登不上，新口令登得上")
    void changedPasswordTakesEffectAtOnce() throws IOException {
        ResponseEntity<JSONObject> response =
                controller.changePassword(body(OLD, NEW), request(ConfigUiSession.Channel.PASSWORD));

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().getBooleanValue("success"), response.getBody().toJSONString());

        assertFalse(authService.login(OLD.toCharArray(), null, "1.2.3.4").success(), "旧口令必须失效");
        assertTrue(authService.login(NEW.toCharArray(), null, "1.2.3.5").success(), "新口令必须当场可用");

        // 盘上落的是哈希而不是明文：改口令这条路与启动时那条路要写下同一种东西，
        // 否则重启一次这台机器会把新口令当明文再哈希一遍，而它此刻已经是哈希了
        String stored = fileService.read().get(ConfigUiAuthService.PASSWORD_PROPERTY);
        assertTrue(PasswordHash.isHashed(stored), "配置里应是哈希: " + stored);
        assertFalse(Files.readString(config, StandardCharsets.UTF_8).contains(NEW), "文件里不该有明文");
        assertNotEquals(OLD, stored);
    }

    @Test
    @DisplayName("🔴 别处的会话一并注销，当前这一把留着")
    void otherSessionsAreRevoked() {
        ConfigUiSession elsewhere = authService.login(OLD.toCharArray(), null, "10.0.0.9").session();
        MockHttpServletRequest request = request(ConfigUiSession.Channel.PASSWORD);
        String mine = request.getCookies()[0].getValue();

        controller.changePassword(body(OLD, NEW), request);

        assertTrue(authService.validate(elsewhere.getId()).isEmpty(),
                "旧口令下建立的会话仍然畅通的话，改口令就没能把可能泄漏的访问权收回来");
        assertTrue(authService.validate(mine).isPresent(),
                "把刚改完口令的人当场踢出去，他只会以为改失败了");
    }

    @Test
    @DisplayName("不带会话 Cookie 改口令：注销数为 0，既有两把会话仍有效")
    void changeWithoutCookieLeavesEverySession() {
        ConfigUiSession first = authService.login(OLD.toCharArray(), null, "10.0.0.1").session();
        ConfigUiSession second = authService.login(OLD.toCharArray(), null, "10.0.0.2").session();

        ResponseEntity<JSONObject> response =
                controller.changePassword(body(OLD, NEW), request(null));

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().getBooleanValue("success"), response.getBody().toJSONString());
        assertEquals(0, response.getBody().getIntValue("revoked"),
                "认不出当前这一把时把别处一并踢掉，刚办完的人会以为没办成");
        assertTrue(authService.validate(first.getId()).isPresent(),
                "不带 Cookie 不得注销第一把既有会话");
        assertTrue(authService.validate(second.getId()).isPresent(),
                "不带 Cookie 不得注销第二把既有会话");
    }

    @Test
    @DisplayName("新口令太短就拒")
    void shortPasswordIsRejected() {
        ResponseEntity<JSONObject> response =
                controller.changePassword(body(OLD, "1234"), request(ConfigUiSession.Channel.PASSWORD));

        assertFalse(response.getBody().getBooleanValue("success"));
        assertTrue(authService.login(OLD.toCharArray(), null, "1.2.3.4").success(), "拒了就不该改动口令");
    }

    @Test
    @DisplayName("🔴 免旧口令那条路只认令牌会话：口令会话走它必须被拒")
    void resetIsOperatorOnly() {
        ResponseEntity<JSONObject> denied =
                controller.resetPassword(body(null, NEW), request(ConfigUiSession.Channel.PASSWORD));

        assertEquals(403, denied.getStatusCode().value());
        assertTrue(authService.login(OLD.toCharArray(), null, "1.2.3.4").success(), "拒了就不该改动口令");

        // 阳性对照：同一条路，令牌会话走得通——否则上面那条只是「这个接口不管用」
        ResponseEntity<JSONObject> allowed =
                controller.resetPassword(body(null, NEW), request(ConfigUiSession.Channel.OPERATOR_TOKEN));

        assertEquals(200, allowed.getStatusCode().value(), allowed.getBody().toJSONString());
        assertTrue(authService.login(NEW.toCharArray(), null, "1.2.3.6").success());
    }
}
