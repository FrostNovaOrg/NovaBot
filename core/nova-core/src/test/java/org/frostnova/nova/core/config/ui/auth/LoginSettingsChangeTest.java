package org.frostnova.nova.core.config.ui.auth;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigurationFileService;
import org.frostnova.nova.core.config.ui.auth.oidc.OidcClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一趟登录进行中登录设置被更换时的结果
 * <p>
 * 口令或二次验证在这趟登录签发会话之前被更换，这趟登录不成功，签出的会话被收回。
 * 更换放在核对完成时的回调里做，每次跑的次序都一样。
 * <p>
 * 每一格同时看两件事：登录回的是不是失败，以及这一趟签出过的会话还在不在。
 * 只看其中一件的话，另一件被改坏了照样是绿的。
 */
@DisplayName("登录设置更换后的登录结果")
class LoginSettingsChangeTest {
    private static final String PASSWORD = "correct horse battery staple";

    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");

    private static final String IP = "192.0.2.1";

    private static final String LOGIN_IP = "192.0.2.9";

    /**
     * 核对完成时回调一次
     */
    private static final class HookedThrottle extends LoginThrottle {
        private Runnable onSuccess = () -> { };

        HookedThrottle(int maxFailures) {
            super(maxFailures, Duration.ofMinutes(15));
        }

        @Override
        public void recordSuccess(String ip) {
            super.recordSuccess(ip);
            Runnable action = onSuccess;
            onSuccess = () -> { };
            action.run();
        }
    }

    /**
     * 记下从登录来源签出的每一把会话
     */
    private static final class RecordingSessions extends ConfigUiSessionStore {
        private final List<ConfigUiSession> issuedToLogin = new ArrayList<>();

        RecordingSessions() {
            super(Duration.ofHours(24), Duration.ofHours(2));
        }

        @Override
        public synchronized ConfigUiSession issueForAccount(String clientIp, Instant now, ConfigUiSession.Channel channel,
                                                            String passkeyCredentialId, String accountId,
                                                            OidcClient.Login oidcLogin) {
            ConfigUiSession session = super.issueForAccount(clientIp, now, channel, passkeyCredentialId, accountId, oidcLogin);
            if (LOGIN_IP.equals(clientIp)) {
                issuedToLogin.add(session);
            }
            return session;
        }
    }

    private final NovaCoreProperties.ConfigUi.Auth properties = new NovaCoreProperties.ConfigUi.Auth();

    private final HookedThrottle throttle = new HookedThrottle(properties.getMaxFailures());

    private final RecordingSessions sessions = new RecordingSessions();

    private ConfigUiAuthService service(boolean totp) {
        properties.setPassword(LowIterationPasswordHash.of(PASSWORD));
        if (totp) {
            properties.setTotp(true);
            properties.setTotpSecret(SECRET);
        }
        return new ConfigUiAuthService(properties, sessions, throttle, (ConfigurationFileService) null, () -> NOW);
    }

    /**
     * 当格在启动时已记成用过，下一格的码在窗口之内
     */
    private static String nextCode() {
        return TotpGenerator.currentCode(SECRET, NOW.plusSeconds(30));
    }

    private void assertNoSessionLeft(ConfigUiAuthService service, ConfigUiAuthService.LoginResult result) {
        assertFalse(result.success(), "设置更换之后这趟登录仍回成功");
        assertEquals(1, sessions.issuedToLogin.size(), "这一趟应当签出过一把会话");
        for (ConfigUiSession session : sessions.issuedToLogin) {
            assertTrue(service.validate(session.getId()).isEmpty(), "这一趟签出的会话仍然有效");
        }
    }

    @Test
    @DisplayName("口令更换后签出的会话被收回")
    void passwordReplaced() {
        ConfigUiAuthService service = service(false);
        ConfigUiSession owner = service.issueForPassword(IP);

        throttle.onSuccess = () -> {
            service.applyPasswordHash(LowIterationPasswordHash.of("a brand new passphrase"));
            service.rotateSession(owner.getId());
        };

        assertNoSessionLeft(service, service.login(PASSWORD.toCharArray(), null, LOGIN_IP));
    }

    @Test
    @DisplayName("开启二次验证后签出的会话被收回")
    void totpTurnedOn() {
        ConfigUiAuthService service = service(false);
        ConfigUiSession owner = service.issueForPassword(IP);

        throttle.onSuccess = () -> {
            service.activateTotp(owner, SECRET, TotpGenerator.currentStep(NOW));
            service.rotateSession(owner.getId());
        };

        assertNoSessionLeft(service, service.login(PASSWORD.toCharArray(), null, LOGIN_IP));
    }

    @Test
    @DisplayName("关闭二次验证后签出的会话被收回")
    void totpTurnedOff() {
        ConfigUiAuthService service = service(true);
        ConfigUiSession owner = service.issueForPassword(IP);

        throttle.onSuccess = () -> {
            service.disableTotp(owner);
            service.rotateSession(owner.getId());
        };

        assertNoSessionLeft(service, service.login(PASSWORD.toCharArray(), nextCode(), LOGIN_IP));
    }

    @Test
    @DisplayName("设置不变时照常登录")
    void unchangedSettingsLogIn() {
        ConfigUiAuthService service = service(false);

        ConfigUiAuthService.LoginResult result = service.login(PASSWORD.toCharArray(), null, LOGIN_IP);

        assertTrue(result.success(), "没有更换时应当照常登录: " + result.message());
        assertTrue(service.validate(result.session().getId()).isPresent());
    }

    @Test
    @DisplayName("开着二次验证、设置不变时照常登录")
    void unchangedSettingsWithTotpLogIn() {
        ConfigUiAuthService service = service(true);

        ConfigUiAuthService.LoginResult result = service.login(PASSWORD.toCharArray(), nextCode(), LOGIN_IP);

        assertTrue(result.success(), "没有更换时应当照常登录: " + result.message());
        assertTrue(service.validate(result.session().getId()).isPresent());
    }
}
