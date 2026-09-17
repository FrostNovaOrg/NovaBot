package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSession;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSessionStore;
import org.frostnova.nova.core.config.ui.auth.LoginThrottle;
import org.frostnova.nova.core.config.ui.auth.PasswordHash;
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
 *   <li><b>旧口令连错有代价</b>——同一把会话连错 5 次就注销这一把。不设代价的话，
 *       偷到 Cookie 的人能借这个口一直猜下去，猜中就换掉口令；主人的登录与别处的会话不受牵连</li>
 * </ul>
 */
@DisplayName("改口令")
class PasswordChangeTest {
    private static final String OLD = "correct horse battery staple";

    private static final String NEW = "another horse another staple";

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

    private Path config;
    private ConfigurationFileService fileService;
    private ConfigUiAuthService authService;
    private ConfigUiAuthController controller;

    @BeforeEach
    void setUp() throws IOException {
        config = dir.resolve("application.yml");
        Files.writeString(config, TEMPLATE, StandardCharsets.UTF_8);
        fileService = new ConfigurationFileService(config);

        NovaCoreProperties properties = new NovaCoreProperties();
        NovaCoreProperties.ConfigUi.Auth auth = properties.getConfigUi().getAuth();
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
    @DisplayName("🔴 旧口令不对就拒并说清还剩几次，配置文件一个字不动")
    void wrongCurrentPasswordIsRejected() throws IOException {
        String before = Files.readString(config, StandardCharsets.UTF_8);

        ResponseEntity<JSONObject> response =
                controller.changePassword(body("这不是我的口令", NEW), request(ConfigUiSession.Channel.PASSWORD));

        // 回 400 而不是 401：界面上凡 401 一律整页重载，提示句来不及显示，
        // 人只看到页面闪了一下，不知道是旧口令输错了，也不知道再错几次会被退出
        assertEquals(400, response.getStatusCode().value(), response.getBody().toJSONString());
        assertFalse(response.getBody().getBooleanValue("success"));
        String message = response.getBody().getString("message");
        assertTrue(message != null && message.contains("再输错 4 次"), "错一次要说清还剩几次: " + message);
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
    @DisplayName("🔴 不带会话 Cookie 改口令：整个拒掉，口令与既有两把会话都不动")
    void changeWithoutCookieIsRefused() throws IOException {
        ConfigUiSession first = authService.login(OLD.toCharArray(), null, "10.0.0.1").session();
        ConfigUiSession second = authService.login(OLD.toCharArray(), null, "10.0.0.2").session();
        String before = Files.readString(config, StandardCharsets.UTF_8);

        ResponseEntity<JSONObject> response =
                controller.changePassword(body(OLD, NEW), request(null));

        // 认不出是哪一把会话，就没处记错了几次：放它过去，连错的代价在这一形里整个不存在；
        // 改成了也收不回别处的会话，因为认不出该留下哪一把
        assertEquals(401, response.getStatusCode().value(), response.getBody().toJSONString());
        assertFalse(response.getBody().getBooleanValue("success"));
        assertEquals(before, Files.readString(config, StandardCharsets.UTF_8), "拒了就不该动配置文件");
        assertTrue(authService.validate(first.getId()).isPresent(),
                "不带 Cookie 不得注销第一把既有会话");
        assertTrue(authService.validate(second.getId()).isPresent(),
                "不带 Cookie 不得注销第二把既有会话");
        assertTrue(authService.login(OLD.toCharArray(), null, "1.2.3.4").success(), "拒了就不该改动口令");
    }

    @Test
    @DisplayName("🔴 同一把会话连错 5 次旧口令：这一把当场注销，之后猜中了也改不了")
    void fifthMissSignsTheSessionOut() throws IOException {
        String before = Files.readString(config, StandardCharsets.UTF_8);
        MockHttpServletRequest stolen = request(ConfigUiSession.Channel.PASSWORD);
        String id = stolen.getCookies()[0].getValue();

        for (int i = 1; i <= 4; i++) {
            controller.changePassword(body("猜的第 " + i + " 个", NEW), stolen);
            assertTrue(authService.validate(id).isPresent(), "输错 " + i + " 次就被退出登录，手滑的人吃不消");
        }

        ResponseEntity<JSONObject> fifth = controller.changePassword(body("猜的第 5 个", NEW), stolen);
        assertTrue(authService.validate(id).isEmpty(),
                "连错 5 次后这把会话仍然有效：拿着它的人可以一直猜下去，猜中就把主人锁在门外");
        assertEquals(401, fifth.getStatusCode().value(), fifth.getBody().toJSONString());

        ResponseEntity<JSONObject> guessed = controller.changePassword(body(OLD, NEW), stolen);
        assertFalse(guessed.getBody().getBooleanValue("success"), "会话已注销，猜中了也不许改");
        assertEquals(before, Files.readString(config, StandardCharsets.UTF_8), "配置文件一个字不动");
        assertTrue(authService.login(OLD.toCharArray(), null, "1.2.3.4").success(), "口令仍是原来那一把");
    }

    @Test
    @DisplayName("连错 4 次后输对一次，计数从头算：再错 4 次这把会话仍在")
    void correctCurrentPasswordStartsTheCountOver() {
        MockHttpServletRequest mine = request(ConfigUiSession.Channel.PASSWORD);
        String id = mine.getCookies()[0].getValue();

        for (int i = 1; i <= 4; i++) {
            controller.changePassword(body("手滑第 " + i + " 次", NEW), mine);
        }

        // 旧口令对、新口令太短：旧口令这一关过了，口令本身没换
        ResponseEntity<JSONObject> tooShort = controller.changePassword(body(OLD, "1234"), mine);
        assertFalse(tooShort.getBody().getBooleanValue("success"), tooShort.getBody().toJSONString());
        assertTrue(String.valueOf(tooShort.getBody().getString("message")).contains("至少"),
                "阳性对照：这一趟得是过了旧口令、卡在新口令长度上: " + tooShort.getBody().toJSONString());

        for (int i = 1; i <= 4; i++) {
            controller.changePassword(body("又手滑第 " + i + " 次", NEW), mine);
        }

        assertTrue(authService.validate(id).isPresent(), "输对过一次，前面那几次就不该还算数");
    }

    @Test
    @DisplayName("🔴 注销的只是猜的那一把：主人别处的会话照旧有效，同一来源照样登得上")
    void signOutStaysWithTheGuessingSession() {
        ConfigUiSession owner = authService.login(OLD.toCharArray(), null, "10.0.0.9").session();
        MockHttpServletRequest stolen = request(ConfigUiSession.Channel.PASSWORD);

        for (int i = 1; i <= 5; i++) {
            controller.changePassword(body("猜的第 " + i + " 个", NEW), stolen);
        }

        assertTrue(authService.validate(stolen.getCookies()[0].getValue()).isEmpty(),
                "阳性对照：猜的那一把得先真被注销，下面几问才说明得了事");
        assertTrue(authService.validate(owner.getId()).isPresent(), "主人别处的会话不该被连累");
        assertEquals(Duration.ZERO, authService.remainingLockout("127.0.0.1"),
                "改口令时猜错不该把这个来源的登录锁住：那等于把锁主人的按钮递给了猜的人");
        assertTrue(authService.login(OLD.toCharArray(), null, "127.0.0.1").success(), "主人照样登得上");
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
