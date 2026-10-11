package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSessionStore;
import org.frostnova.nova.core.config.ui.auth.LoginThrottle;
import org.frostnova.nova.core.config.ui.auth.LowIterationPasswordHash;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录失败的回话里说明这一趟是不是密码或动态码被拒。
 * <p>
 * 抓的故障：登录页凡失败都把密码框标成「无效」。被锁、服务器正忙时这一趟根本没核密码，
 * 读屏也念「无效」，人会以为自己密码错了。界面只能照回话里这一位分，不能比对提示文字，
 * 也不能拿 lockedSeconds 分——第 N 次输错正好锁上的那一趟，lockedSeconds 已经大于 0。
 */
@DisplayName("登录失败回话里的「被拒」一位")
class LoginCredentialsRejectedTest {
    private static final String PASSWORD = "correct horse battery staple";

    private static final String IP = "203.0.113.71";

    @TempDir
    Path directory;

    private LoginThrottle throttle;

    private ConfigUiAuthController controller() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(directory.resolve("data.json").toString());
        NovaCoreProperties.ConfigUi.Auth auth = properties.getConfigUi().getAuth();
        // 这一组量的是回话里「被拒」一位，口令哈希只迭代 1 次，见 LowIterationPasswordHash
        auth.setPassword(LowIterationPasswordHash.of(PASSWORD));
        throttle = new LoginThrottle(auth.getMaxFailures(),
                Duration.ofMinutes(Math.max(1, auth.getLockoutMinutes())));
        ConfigUiAuthService service = new ConfigUiAuthService(auth,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                throttle, null);
        return new ConfigUiAuthController(service, null, properties);
    }

    private static JSONObject login(ConfigUiAuthController controller, String password) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(IP);
        JSONObject body = new JSONObject();
        body.put("password", password);
        ResponseEntity<JSONObject> response = controller.login(body, request);
        return response.getBody();
    }

    private int maxFailures() {
        return new NovaCoreProperties().getConfigUi().getAuth().getMaxFailures();
    }

    @Test
    @DisplayName("密码输错：回话说被拒")
    void wrongPasswordIsRejected() {
        JSONObject result = login(controller(), "wrong");

        assertEquals(false, result.getBoolean("success"));
        assertEquals(0L, result.getLongValue("lockedSeconds"), "前提：第一次输错还没锁");
        assertEquals(true, result.getBoolean("credentialsRejected"), "密码不对，这一趟算被拒");
    }

    @Test
    @DisplayName("第 N 次输错、这一趟正好锁上：仍说被拒")
    void wrongPasswordThatLocksIsRejected() {
        ConfigUiAuthController controller = controller();
        for (int i = 1; i < maxFailures(); i++) {
            login(controller, "wrong");
        }

        JSONObject result = login(controller, "wrong");

        assertTrue(result.getLongValue("lockedSeconds") > 0, "前提：这一趟已经锁上了");
        assertEquals(true, result.getBoolean("credentialsRejected"), "这一趟核过密码且不对，算被拒");
    }

    @Test
    @DisplayName("已经被锁：这一趟没核密码，不说被拒")
    void lockedOutIsNotRejected() {
        ConfigUiAuthController controller = controller();
        for (int i = 0; i < maxFailures(); i++) {
            login(controller, "wrong");
        }

        JSONObject result = login(controller, PASSWORD);

        assertEquals(false, result.getBoolean("success"));
        assertTrue(result.getString("message").startsWith("登录失败次数过多"), "前提：走的是被锁那一支");
        assertEquals(false, result.getBoolean("credentialsRejected"), "被锁时没核密码，不算被拒");
    }

    @Test
    @DisplayName("服务器正忙：这一趟没核密码，不说被拒")
    void busyIsNotRejected() {
        ConfigUiAuthController controller = controller();
        // 把校验名额占满，登录拿不到名额就回「服务器正忙」
        while (throttle.tryAcquireSlot()) {
            // 占着
        }

        JSONObject result = login(controller, PASSWORD);

        assertEquals(false, result.getBoolean("success"));
        assertEquals("服务器正忙，请稍后重试", result.getString("message"), "前提：走的是正忙那一支");
        assertEquals(false, result.getBoolean("credentialsRejected"), "正忙时没核密码，不算被拒");
    }
}
