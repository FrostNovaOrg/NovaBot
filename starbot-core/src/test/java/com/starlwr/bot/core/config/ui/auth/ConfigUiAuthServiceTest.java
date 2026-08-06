package com.starlwr.bot.core.config.ui.auth;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置界面登录校验测试
 */
@DisplayName("配置界面登录校验")
class ConfigUiAuthServiceTest {
    private static final String PASSWORD = "correct horse battery staple";

    private static final String IP = "1.2.3.4";

    private ConfigUiAuthService service(String password, String totpSecret) {
        StarBotCoreProperties.ConfigUi.Auth properties = new StarBotCoreProperties.ConfigUi.Auth();
        properties.setPassword(password);
        properties.setTotpSecret(totpSecret);

        return new ConfigUiAuthService(properties,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(properties.getMaxFailures(), Duration.ofMinutes(15)));
    }

    @Test
    @DisplayName("未配置口令时不启用登录")
    void disabledWithoutPassword() {
        assertFalse(service("", "").isEnabled(), "默认不该逼着单机用户设口令");
        assertFalse(service("   ", "").isEnabled(), "只填了空白等于没填");
    }

    @Test
    @DisplayName("配置里可以直接写明文口令")
    void acceptsPlainTextPassword() {
        ConfigUiAuthService service = service(PASSWORD, "");

        assertTrue(service.isEnabled());
        assertTrue(service.login(PASSWORD.toCharArray(), null, IP).success());
    }

    @Test
    @DisplayName("配置里也可以写哈希串")
    void acceptsHashedPassword() {
        ConfigUiAuthService service = service(PasswordHash.hash(PASSWORD.toCharArray()), "");

        assertTrue(service.login(PASSWORD.toCharArray(), null, IP).success());
    }

    @Test
    @DisplayName("口令错误时登录失败")
    void rejectsWrongPassword() {
        ConfigUiAuthService service = service(PASSWORD, "");

        assertFalse(service.login("猜的".toCharArray(), null, IP).success());
    }

    @Test
    @DisplayName("登录成功签发的会话可以校验通过")
    void issuesUsableSession() {
        ConfigUiAuthService service = service(PASSWORD, "");
        ConfigUiSession session = service.login(PASSWORD.toCharArray(), null, IP).session();

        assertTrue(service.validate(session.getId()).isPresent());

        service.logout(session.getId());
        assertTrue(service.validate(session.getId()).isEmpty(), "注销后应立即失效");
    }

    @Test
    @DisplayName("启用二次验证后，口令对了但验证码不对也进不来")
    void requiresTotpWhenConfigured() {
        String secret = TotpGenerator.generateSecret();
        ConfigUiAuthService service = service(PASSWORD, secret);

        assertTrue(service.totpRequired());
        assertFalse(service.login(PASSWORD.toCharArray(), "000000", IP).success(),
                "二次验证若能被绕过，它就只是个摆设");
        assertFalse(service.login(PASSWORD.toCharArray(), null, IP).success(), "不填验证码同样不行");
    }

    @Test
    @DisplayName("口令与验证码都正确时通过")
    void acceptsValidTotp() {
        String secret = TotpGenerator.generateSecret();
        ConfigUiAuthService service = service(PASSWORD, secret);
        Instant now = Instant.now();
        String code = TotpGenerator.generate(TotpGenerator.base32Decode(secret), now.getEpochSecond() / 30);

        assertTrue(service.login(PASSWORD.toCharArray(), code, IP).success());
    }

    @Test
    @DisplayName("口令错与验证码错的提示必须一模一样")
    void failureMessageDoesNotLeakWhichPartWasWrong() {
        String secret = TotpGenerator.generateSecret();
        ConfigUiAuthService service = service(PASSWORD, secret);
        Instant now = Instant.now();
        String code = TotpGenerator.generate(TotpGenerator.base32Decode(secret), now.getEpochSecond() / 30);

        String wrongPassword = service.login("猜的".toCharArray(), code, IP).message();
        String wrongCode = service.login(PASSWORD.toCharArray(), "000000", "5.6.7.8").message();

        assertEquals(wrongPassword, wrongCode,
                "分开提示等于告诉攻击者口令已经猜对了，二次验证就只剩六位数字要试");
    }

    @Test
    @DisplayName("连续失败到阈值后即使口令正确也被挡在门外")
    void lockedOutAfterRepeatedFailures() {
        ConfigUiAuthService service = service(PASSWORD, "");

        for (int i = 0; i < 5; i++) {
            service.login("猜的".toCharArray(), null, IP);
        }

        ConfigUiAuthService.LoginResult result = service.login(PASSWORD.toCharArray(), null, IP);
        assertFalse(result.success(), "锁定期内不该再受理任何尝试");
        assertFalse(result.retryAfter().isZero(), "应告知还要等多久");
    }
}
