package org.frostnova.nova.core.config.ui.auth;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigUiAuthController;
import org.frostnova.nova.core.config.ui.auth.passkey.PasskeyRelyingParty;
import org.frostnova.nova.core.config.ui.auth.passkey.PasskeyService;
import org.frostnova.nova.core.config.ui.auth.passkey.PasskeyStore;
import org.frostnova.nova.core.service.NovaStateStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定提示里的分钟数。
 * <p>
 * 刚锁上往往还差不到一分钟才满整段锁定。提示若按整分钟向下砍，会少说一分钟，
 * 人照这个数等完再试，仍会被锁着。
 */
@DisplayName("锁定提示的分钟数")
class LockoutMinutesNoticeTest {
    private static final String PASSWORD = "correct horse battery staple";

    private static final String IP = "203.0.113.64";

    private static final Instant START = Instant.parse("2026-09-23T12:00:00Z");

    @TempDir
    Path directory;

    private final AtomicReference<Instant> now = new AtomicReference<>(START);

    /**
     * 按默认次数连错锁上，再把钟拨过一秒。
     * 还要等的时间少了一秒，不再是整分钟。
     */
    private ConfigUiAuthService justLocked(NovaCoreProperties properties) {
        NovaCoreProperties.ConfigUi.Auth auth = properties.getConfigUi().getAuth();
        auth.setPassword(PASSWORD);
        auth.setTotp(true);
        auth.setTotpSecret("JBSWY3DPEHPK3PXP");

        ConfigUiAuthService service = new ConfigUiAuthService(auth,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(auth.getMaxFailures(),
                        Duration.ofMinutes(Math.max(1, auth.getLockoutMinutes()))),
                null, now::get);

        for (int i = 0; i < auth.getMaxFailures(); i++) {
            service.recordFailedAttempt(IP);
        }
        now.set(now.get().plusSeconds(1));
        return service;
    }

    private NovaCoreProperties properties() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(directory.resolve("data.json").toString());
        return properties;
    }

    private void assertJustShortOfFullLock(ConfigUiAuthService service, int lockMinutes) {
        Duration left = service.remainingLockout(IP);
        assertTrue(left.compareTo(Duration.ofMinutes(lockMinutes - 1L)) > 0
                        && left.compareTo(Duration.ofMinutes(lockMinutes)) < 0,
                "刚锁上应还差不到一分钟才满 " + lockMinutes + " 分钟，实际还要等 " + left);
    }

    @Test
    @DisplayName("密码登录刚被锁上：用户照提示的分钟数等完再试，不会仍被锁")
    void passwordLoginRoundsMinutesUp() {
        NovaCoreProperties properties = properties();
        int lockMinutes = properties.getConfigUi().getAuth().getLockoutMinutes();
        ConfigUiAuthService service = justLocked(properties);

        assertJustShortOfFullLock(service, lockMinutes);
        ConfigUiAuthService.LoginResult result = service.login(PASSWORD.toCharArray(), null, IP);

        assertEquals("登录失败次数过多，请在 " + lockMinutes + " 分钟后重试", result.message(),
                "默认锁 " + lockMinutes + " 分钟，刚锁上应说 " + lockMinutes);
    }

    @Test
    @DisplayName("二次验证试太多次被锁：用户照提示的分钟数等完再试，不会仍被锁")
    void sensitiveTotpRoundsMinutesUp() {
        NovaCoreProperties properties = properties();
        int lockMinutes = properties.getConfigUi().getAuth().getLockoutMinutes();
        ConfigUiAuthService service = justLocked(properties);
        ConfigUiAuthController controller = new ConfigUiAuthController(service, null, properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(IP);
        JSONObject body = new JSONObject();
        body.put("code", "000000");

        assertJustShortOfFullLock(service, lockMinutes);
        ResponseEntity<JSONObject> locked = controller.totpDisable(body, request);

        assertEquals("尝试次数过多，请在 " + lockMinutes + " 分钟后重试",
                locked.getBody().getString("message"),
                "默认锁 " + lockMinutes + " 分钟，刚锁上应说 " + lockMinutes);
    }

    @Test
    @DisplayName("通行密钥登录刚被锁上：用户照提示的分钟数等完再试，不会仍被锁")
    void passkeyLoginRoundsMinutesUp() {
        NovaCoreProperties properties = properties();
        int lockMinutes = properties.getConfigUi().getAuth().getLockoutMinutes();
        ConfigUiAuthService service = justLocked(properties);
        PasskeyService passkeys = new PasskeyService(new PasskeyStore(new NovaStateStore(properties)), service);

        assertJustShortOfFullLock(service, lockMinutes);
        PasskeyService.PasskeyLogin outcome = passkeys.loginVerify(new JSONObject(),
                new PasskeyRelyingParty("localhost", "http://localhost:7827"), IP);

        assertEquals("登录失败次数过多，请在 " + lockMinutes + " 分钟后重试", outcome.message(),
                "默认锁 " + lockMinutes + " 分钟，刚锁上应说 " + lockMinutes);
    }
}
