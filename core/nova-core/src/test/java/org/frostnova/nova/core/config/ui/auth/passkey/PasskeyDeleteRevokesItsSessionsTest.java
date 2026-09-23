package org.frostnova.nova.core.config.ui.auth.passkey;

import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.http.Cookie;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigUiPasskeyController;
import org.frostnova.nova.core.config.ui.ConfigUiSecurityFilter;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSessionStore;
import org.frostnova.nova.core.config.ui.auth.LoginThrottle;
import org.frostnova.nova.core.service.NovaStateStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 删掉一把通行密钥时，用它签发的会话必须跟着作废
 * <p>
 * 典型场景：手机丢了，主人在电脑上把手机那把钥匙删掉。删完之后手机上已经登着的控制台
 * 必须当即进不来——不然删了也白删。发起删除的那一把留下：删的人在场、刚过了登录，
 * 不把自己踢下线。换会话换出来的新会话带着原钥匙标识，删钥时照样跟着走。
 */
@DisplayName("删通行密钥时注销用它登录的会话")
class PasskeyDeleteRevokesItsSessionsTest extends PasskeyTestSupport {
    @Test
    @DisplayName("删掉丢了的设备上的通行密钥后，用它登录的会话仍能操作控制台")
    void deleteRevokesSessionsIssuedByThatKey() {
        TestAuthenticator lostPhone = new TestAuthenticator(TestAuthenticator.ES256);
        TestAuthenticator laptop = new TestAuthenticator(TestAuthenticator.EDDSA);
        register(lostPhone, "丢了的手机", 3);
        register(laptop, "电脑", 3);
        String stolenByLostPhone = passkeyLogin(lostPhone, 4);
        String laptopSession = passkeyLogin(laptop, 4);

        ResponseEntity<JSONObject> deleted = deleteAs(sessionId, lostPhone.credentialId());

        assertEquals(200, deleted.getStatusCode().value(), deleted.getBody().toJSONString());
        assertTrue(authService.validate(stolenByLostPhone).isEmpty(),
                "删掉一把钥匙之后，用它登录的会话仍然有效：手机丢了也照样进得来控制台");
        assertTrue(authService.validate(laptopSession).isPresent(),
                "别的钥匙登录的会话被顺手踢掉了");
        assertTrue(authService.validate(sessionId).isPresent(),
                "发起删除的会话被顺手踢掉了：删的人刚办完就被退出登录");
    }

    @Test
    @DisplayName("在那把钥匙登录的会话里删它自己：这一把照常用，同一把钥匙的另一把会话失效")
    void deleteFromItsOwnSessionKeepsTheCaller() {
        TestAuthenticator lostPhone = new TestAuthenticator(TestAuthenticator.ES256);
        register(lostPhone, "丢了的手机", 3);
        String caller = passkeyLogin(lostPhone, 4);
        String alsoFromLostPhone = passkeyLogin(lostPhone, 5);

        ResponseEntity<JSONObject> deleted = deleteAs(caller, lostPhone.credentialId());

        assertEquals(200, deleted.getStatusCode().value(), deleted.getBody().toJSONString());
        assertTrue(authService.validate(caller).isPresent(),
                "发起删除的那一把把自己也踢了：删的人刚办完就被退出登录");
        assertTrue(authService.validate(alsoFromLostPhone).isEmpty(),
                "同一把钥匙的另一把会话仍在：手机那边照样进得来");
    }

    @Test
    @DisplayName("换过会话之后删钥：换出来的那一把照样跟着原钥匙走")
    void deleteAlsoRevokesRotatedSession() {
        TestAuthenticator lostPhone = new TestAuthenticator(TestAuthenticator.ES256);
        register(lostPhone, "丢了的手机", 3);
        String beforeRotate = passkeyLogin(lostPhone, 4);
        ConfigUiAuthService.SessionRotation rotation = authService.rotateSession(beforeRotate);
        assertNotNull(rotation.session(), "台面没搭起来, 换会话就失败了");

        ResponseEntity<JSONObject> deleted = deleteAs(sessionId, lostPhone.credentialId());

        assertEquals(200, deleted.getStatusCode().value(), deleted.getBody().toJSONString());
        assertTrue(authService.validate(rotation.session().getId()).isEmpty(),
                "换会话没把钥匙标识带过来：换一把标识就躲开了删钥时的连带注销");
    }

    @Test
    @DisplayName("登录途中钥匙被删掉：不得签出一把带着已删钥匙、又没人注销的会话")
    void loginMustNotLeaveOrphanSessionWhenKeyVanishesMidLogin() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(directory.resolve("race-data.json").toString());
        NovaCoreProperties.ConfigUi.Auth auth = properties.getConfigUi().getAuth();
        auth.setPassword(PasskeyTestSupport.PASSWORD);
        auth.setTotp(false);

        RemoveAfterUpdateStore racingStore = new RemoveAfterUpdateStore(new NovaStateStore(properties));
        ConfigUiAuthService racingAuth = new ConfigUiAuthService(auth,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(auth.getMaxFailures(), Duration.ofMinutes(15)), null);
        ConfigUiPasskeyController racingController = new ConfigUiPasskeyController(
                new PasskeyService(racingStore, racingAuth), properties, racingAuth);

        TestAuthenticator authenticator = new TestAuthenticator(TestAuthenticator.ES256);
        String setupSession = racingAuth.login(PASSWORD.toCharArray(), null, CLIENT_IP).session().getId();
        MockHttpServletRequest setupRequest = bareRequest();
        setupRequest.setCookies(new Cookie(ConfigUiSecurityFilter.SESSION_COOKIE, setupSession));
        JSONObject body = new JSONObject();
        body.put("current", PASSWORD);
        JSONObject registerOptions = racingController.registerOptions(body, setupRequest).getBody();
        JSONObject registered = racingController.registerVerify(
                authenticator.register(registerOptions.getString("challenge"),
                        ORIGIN, RP_ID, "我的手机", 7),
                setupRequest);
        assertTrue(registered.getBooleanValue("success"),
                "台面没搭起来, 登记就失败了: " + registered.getString("message"));

        JSONObject loginOptions = racingController.loginOptions(bareRequest());
        assertTrue(loginOptions.getBooleanValue("success"),
                "台面没搭起来, 取不到登录挑战: " + loginOptions.getString("message"));
        JSONObject assertion = authenticator.assertion(loginOptions.getString("challenge"),
                ORIGIN, RP_ID, 8);

        racingStore.arm();
        ResponseEntity<JSONObject> response = racingController.loginVerify(assertion, bareRequest());

        assertEquals(401, response.getStatusCode().value(),
                "钥匙在签发会话前被删掉，登录却成了：签出来的会话带着已删钥匙，漏过删钥时的注销");
        assertTrue(response.getBody() == null || !response.getBody().getBooleanValue("success"),
                "返回值必须为假");
    }

    /**
     * 拿一把通行密钥登一次，回包 Set-Cookie 里那枚就是新会话
     */
    private String passkeyLogin(TestAuthenticator authenticator, long signCount) {
        JSONObject options = controller.loginOptions(request());
        assertTrue(options.getBooleanValue("success"),
                "台面没搭起来, 取不到登录挑战: " + options.getString("message"));
        JSONObject assertion = authenticator.assertion(options.getString("challenge"), ORIGIN, RP_ID, signCount);
        ResponseEntity<JSONObject> response = controller.loginVerify(assertion, request());

        assertEquals(200, response.getStatusCode().value(), response.getBody().toJSONString());
        String id = sessionIdOf(response);
        assertNotNull(id, "登录成功却没交回会话 Cookie");
        return id;
    }

    /**
     * 以某一把会话的身份删一把钥匙
     */
    private ResponseEntity<JSONObject> deleteAs(String callerSessionId, String credentialId) {
        return controller.delete(credentialId, withSession(callerSessionId));
    }

    private MockHttpServletRequest withSession(String sessionId) {
        MockHttpServletRequest request = bareRequest();
        if (sessionId != null) {
            request.setCookies(new Cookie(ConfigUiSecurityFilter.SESSION_COOKIE, sessionId));
        }
        return request;
    }

    private static MockHttpServletRequest bareRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/config/api/auth/passkeys");
        request.addHeader("Host", PasskeyTestSupport.HOST);
        request.setRemoteAddr(PasskeyTestSupport.CLIENT_IP);
        return request;
    }

    /**
     * 回包 Set-Cookie 里下发的会话标识，没下发时为 null
     */
    private static String sessionIdOf(ResponseEntity<JSONObject> response) {
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        if (setCookie == null) {
            return null;
        }

        String prefix = ConfigUiSecurityFilter.SESSION_COOKIE + "=";
        for (String part : setCookie.split(";")) {
            String piece = part.strip();
            if (piece.startsWith(prefix)) {
                return piece.substring(prefix.length());
            }
        }
        return null;
    }

    /**
     * 计数器更新成功之后立刻删掉这把钥匙，把「更新完到签发会话」那条缝敞开放出来。
     * 查到钥匙时删会被 {@link PasskeyStore#updateIfSignCount} 当场拒掉；那条路已经有格了。
     */
    private static final class RemoveAfterUpdateStore extends PasskeyStore {
        private volatile boolean armed;

        private RemoveAfterUpdateStore(NovaStateStore state) {
            super(state);
        }

        private void arm() {
            armed = true;
        }

        @Override
        public boolean updateIfSignCount(String id, long expectedSignCount, PasskeyCredential credential) {
            boolean updated = super.updateIfSignCount(id, expectedSignCount, credential);
            if (armed && updated) {
                super.remove(id);
            }
            return updated;
        }
    }
}
