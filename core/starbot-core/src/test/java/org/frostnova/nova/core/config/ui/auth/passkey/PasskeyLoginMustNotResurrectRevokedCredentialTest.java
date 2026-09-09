package org.frostnova.nova.core.config.ui.auth.passkey;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigUiPasskeyController;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSessionStore;
import org.frostnova.nova.core.config.ui.auth.LoginThrottle;
import org.frostnova.nova.core.service.NovaStateStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录途中把钥匙撤掉之后，完成登录不得把它写回去
 * <p>
 * 挑战已经发出、验签已经按登记时的公钥走完，这时存储里这把钥匙却没了。
 * 计数器更新若无条件覆盖，一次还在路上的登录会把刚撤销的设备当场写回去。
 * 闸在 {@link PasskeyStore#updateIfSignCount}：钥匙已经不在时回 false，调用方不得再当成登录成功。
 */
@DisplayName("通行密钥登录途中撤销不得复活")
class PasskeyLoginMustNotResurrectRevokedCredentialTest {
    @TempDir
    Path directory;

    @Test
    @DisplayName("登记后开始登录，验签途中撤掉这把钥匙，完成登录失败且库里不再出现它")
    void loginDoesNotResurrectCredentialRemovedAfterLookup() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(directory.resolve("data.json").toString());
        NovaCoreProperties.ConfigUi.Auth auth = properties.getConfigUi().getAuth();
        auth.setPassword(PasskeyTestSupport.PASSWORD);
        auth.setTotp(false);

        RemoveAfterFindStore store = new RemoveAfterFindStore(new NovaStateStore(properties));
        ConfigUiAuthService authService = new ConfigUiAuthService(auth,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(auth.getMaxFailures(), Duration.ofMinutes(15)), null);
        ConfigUiPasskeyController controller = new ConfigUiPasskeyController(
                new PasskeyService(store, authService), properties);

        TestAuthenticator authenticator = new TestAuthenticator(TestAuthenticator.ES256);
        MockHttpServletRequest request = request();
        JSONObject registerOptions = controller.registerOptions(request);
        JSONObject registered = controller.registerVerify(
                authenticator.register(registerOptions.getString("challenge"),
                        PasskeyTestSupport.ORIGIN, PasskeyTestSupport.RP_ID, "我的手机", 7),
                request);
        assertTrue(registered.getBooleanValue("success"),
                "台面没搭起来, 登记就失败了: " + registered.getString("message"));

        JSONObject loginOptions = controller.loginOptions(request());
        assertTrue(loginOptions.getBooleanValue("success"),
                "台面没搭起来, 取不到登录挑战: " + loginOptions.getString("message"));
        JSONObject assertion = authenticator.assertion(loginOptions.getString("challenge"),
                PasskeyTestSupport.ORIGIN, PasskeyTestSupport.RP_ID, 8);

        store.arm();
        ResponseEntity<JSONObject> response = controller.loginVerify(assertion, request());

        assertEquals(401, response.getStatusCode().value(),
                "钥匙在验签途中被撤掉之后，完成登录必须失败");
        assertFalse(response.getBody().getBooleanValue("success"),
                "返回值必须为假, 实际 message=" + response.getBody().getString("message"));
        assertTrue(store.find(authenticator.credentialId()).isEmpty(),
                "撤掉的钥匙不得被登录更新写回去");
        assertTrue(store.list().isEmpty(),
                "库里不该留下被复活的条目");
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/config/api/auth/passkey");
        request.addHeader("Host", PasskeyTestSupport.HOST);
        request.setRemoteAddr(PasskeyTestSupport.CLIENT_IP);
        return request;
    }

    /**
     * find 成功之后立刻删掉这把钥匙，把「读完再写」那条缝敞开放出来。
     * 先删再验会在 find 处就被拒，{@link PasskeyStore#updateIfSignCount} 的空记录枝走不到。
     */
    private static final class RemoveAfterFindStore extends PasskeyStore {
        private volatile boolean armed;

        private RemoveAfterFindStore(NovaStateStore state) {
            super(state);
        }

        private void arm() {
            armed = true;
        }

        @Override
        public Optional<PasskeyCredential> find(String id) {
            Optional<PasskeyCredential> found = super.find(id);
            if (armed && found.isPresent()) {
                super.remove(id);
            }
            return found;
        }
    }
}
