package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSessionStore;
import org.frostnova.nova.core.config.ui.auth.LoginThrottle;
import org.frostnova.nova.core.config.ui.auth.LowIterationPasswordHash;
import org.frostnova.nova.core.config.ui.auth.oidc.OidcTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OidcCallbackNoticeTest extends OidcTestSupport {
    private ConfigUiAuthService auth;
    private ConfigUiAuthController controller;

    @BeforeEach
    void prepareConsole() {
        var properties = new NovaCoreProperties();
        var settings = properties.getConfigUi().getAuth();
        settings.setPassword(LowIterationPasswordHash.of("example console password"));
        settings.setTotp(false);
        auth = new ConfigUiAuthService(settings,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(5, Duration.ofMinutes(15)), null);
        controller = new ConfigUiAuthController(auth, null, properties);
    }

    private void restartClient() {
        rebuildClient();
        if (client.isEnabled()) {
            var request = new MockHttpServletRequest();
            request.setServerName("localhost");
            client.startLogin(request);
        }
        auth.setOidcClient(client);
    }

    private JSONObject state(ConfigUiAccount account) {
        var request = new MockHttpServletRequest();
        if (account != null) request.setAttribute(ConfigUiAccess.ACCOUNT, account);
        return controller.state(request);
    }

    private ConfigUiAccount admin() {
        return ConfigUiAccount.local(Instant.EPOCH);
    }

    @Test
    void administratorNoticeFollowsRestartedCallbackSetting() {
        config.setRedirectUri("");
        restartClient();
        assertTrue(state(admin()).getBooleanValue("oidcCallbackSetupNeeded"));
        config.setRedirectUri(CALLBACK);
        assertTrue(state(admin()).getBooleanValue("oidcCallbackSetupNeeded"));
        restartClient();
        assertFalse(state(admin()).getBooleanValue("oidcCallbackSetupNeeded"));
    }

    @Test
    void noticeIsOnlyIncludedForAdministrators() {
        config.setRedirectUri("  ");
        restartClient();
        assertTrue(state(admin()).getBooleanValue("oidcCallbackSetupNeeded"));
        var member = new ConfigUiAccount("member-example", ConfigUiAccount.Role.MEMBER,
                ConfigUiAccount.Status.ENABLED, "Example Member", "member@example.com",
                null, null, List.of(), Instant.EPOCH, null, true);
        assertFalse(state(member).containsKey("oidcCallbackSetupNeeded"));
        assertFalse(state(null).containsKey("oidcCallbackSetupNeeded"));
    }

    @Test
    void noticeIsAbsentWhenUnifiedLoginIsOff() {
        config.setRedirectUri("");
        config.setEnabled(false);
        restartClient();
        assertFalse(state(admin()).getBooleanValue("oidcCallbackSetupNeeded"));
        config.setEnabled(true);
        config.setIssuer("");
        restartClient();
        assertFalse(state(admin()).getBooleanValue("oidcCallbackSetupNeeded"));
    }

    @Test
    void consoleRendersCallbackNoticeFromLoginState() throws IOException, InterruptedException {
        var states = new JSONArray();
        config.setRedirectUri("");
        restartClient();
        states.add(state(admin()));
        config.setRedirectUri(CALLBACK);
        restartClient();
        states.add(state(admin()));
        states.add(state(null));
        FrontendFixture.run(Path.of("src/test/resources/frontend/oidc-callback-notice-fixture.mjs").toAbsolutePath().toString(),
                "统一登录回调地址提醒", states.toJSONString());
    }
}
