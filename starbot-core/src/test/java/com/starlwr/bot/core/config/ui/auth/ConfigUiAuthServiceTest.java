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
        return service(password, totpSecret, true);
    }

    private ConfigUiAuthService service(String password, String totpSecret, boolean totp) {
        StarBotCoreProperties.ConfigUi.Auth properties = new StarBotCoreProperties.ConfigUi.Auth();
        properties.setPassword(password);
        properties.setTotpSecret(totpSecret);
        properties.setTotp(totp);

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
    @DisplayName("设了口令却没绑验证器时，应提示去绑而不是把人拦在外面")
    void promptsForEnrollmentWhenSecretMissing() {
        ConfigUiAuthService service = service(PASSWORD, "");

        assertTrue(service.totpPending(), "默认要求二次验证，没绑就该提示");
        assertFalse(service.totpRequired(), "还没绑，登录时无从校验验证码");
        assertTrue(service.login(PASSWORD.toCharArray(), null, IP).success(),
                "没绑就不让登录的话，人根本进不到能绑定的界面里去");
    }

    @Test
    @DisplayName("绑定引导中的密钥在同一进程内不能变")
    void pendingSecretIsStable() {
        ConfigUiAuthService service = service(PASSWORD, "");

        // 每次刷新页面换一个密钥的话，先扫进验证器的那个就作废了，而用户毫不知情
        assertEquals(service.pendingSecret(), service.pendingSecret());
    }

    @Test
    @DisplayName("绑定确认要校验验证码，通过后登录才开始要验证码")
    void enrollmentActivatesTotp() {
        ConfigUiAuthService service = service(PASSWORD, "");
        String secret = service.pendingSecret();

        assertTrue(service.verifyPending("000000").isEmpty(), "验证码不对不能算绑定成功");

        String code = TotpGenerator.generate(TotpGenerator.base32Decode(secret), Instant.now().getEpochSecond() / 30);
        assertEquals(secret, service.verifyPending(code).orElse(null));

        service.activateTotp(secret);
        assertTrue(service.totpRequired());
        assertFalse(service.totpPending(), "绑好了就不该再提示");
        assertFalse(service.login(PASSWORD.toCharArray(), null, "9.9.9.9").success(), "从此登录必须带验证码");
    }

    @Test
    @DisplayName("显式关掉二次验证后既不提示也不校验")
    void totpCanBeTurnedOff() {
        ConfigUiAuthService service = service(PASSWORD, "", false);

        assertFalse(service.totpPending());
        assertFalse(service.totpRequired());
        assertTrue(service.login(PASSWORD.toCharArray(), null, IP).success());
    }

    @Test
    @DisplayName("关掉二次验证时，即使配置里还留着密钥也不再校验")
    void turningOffIgnoresExistingSecret() {
        // 否则「关掉了却还要输验证码」，而验证器可能早就被删了
        ConfigUiAuthService service = service(PASSWORD, TotpGenerator.generateSecret(), false);

        assertFalse(service.totpRequired());
        assertTrue(service.login(PASSWORD.toCharArray(), null, IP).success());
    }

    @Test
    @DisplayName("运维通道签发的会话与登录得来的一样可用")
    void operatorSessionIsUsable() {
        ConfigUiAuthService service = service(PASSWORD, TotpGenerator.generateSecret());
        ConfigUiSession session = service.issueForOperator("127.0.0.1");

        assertTrue(service.validate(session.getId()).isPresent(),
                "改了口令与二次验证之后，还得有办法从服务器上进得来");
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
