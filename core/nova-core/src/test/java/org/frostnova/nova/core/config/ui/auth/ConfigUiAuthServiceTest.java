package org.frostnova.nova.core.config.ui.auth;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

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
        NovaCoreProperties.ConfigUi.Auth properties = new NovaCoreProperties.ConfigUi.Auth();
        properties.setPassword(password);
        properties.setTotpSecret(totpSecret);
        properties.setTotp(totp);

        // fileService 传 null：这些用例只关心校验逻辑，不需要把哈希写回配置文件
        return new ConfigUiAuthService(properties,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(properties.getMaxFailures(), Duration.ofMinutes(15)), null);
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

    /**
     * 全局桶的容量，与 {@link LoginThrottle} 里的常量对齐
     */
    private static final int GLOBAL_BURST = 20;

    private LoginThrottle throttle;

    /**
     * 判据自己的时钟。<b>停着不走</b>，除非判据自己拨。
     * <p>
     * 🔴 这个东西存在的理由：全局桶按传进来的时刻线性回满，而口令校验走 PBKDF2，
     * 三次就是一两秒——那一两秒足够让桶悄悄回上半个令牌。用真实时钟的话，
     * 下面那条「三次失败扣三个令牌」就成了<b>「这台机器有多快」的函数</b>：
     * 快机器上绿，慢机器上红。停住时钟之后它量的才是它声称要量的东西。
     */
    private final java.util.concurrent.atomic.AtomicReference<Instant> now =
            new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-08-20T00:00:00Z"));

    /**
     * 造一个共用同一个限流器的服务，好让判据能从外面观察那个全局桶
     */
    private ConfigUiAuthService serviceSharingThrottle() {
        NovaCoreProperties.ConfigUi.Auth properties = new NovaCoreProperties.ConfigUi.Auth();
        properties.setPassword(PASSWORD);
        properties.setTotp(false);

        throttle = new LoginThrottle(properties.getMaxFailures(), Duration.ofMinutes(15));
        return new ConfigUiAuthService(properties,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)), throttle, null,
                now::get);
    }

    /**
     * 把全局桶抽干，返回抽出来的令牌数
     */
    private int drainGlobal(Instant at) {
        int taken = 0;
        while (throttle.tryAcquireGlobal(at)) {
            taken++;
            if (taken > GLOBAL_BURST * 10) {
                throw new IllegalStateException("桶取不完, 说明全局速率限制根本没生效");
            }
        }
        return taken;
    }

    /**
     * 🔴 全局速率限制得真的被这条路径问到（M3）
     * <p>
     * {@link LoginThrottleTest} 证的是桶本身会拦；这条证的是<b>校验这条路真的去问了那个桶</b>。
     * 两件事分开写，是因为「桶实现得很对，但没人调它」在功能上完全看不出来——
     * 面板照样能登录，攻击者照样能猜。
     * <p>
     * 这里用的是<b>正确的口令</b>：额度用完时连对的口令也得被拒，
     * 否则这道限制就只在「猜错」时生效，而攻击者并不知道自己猜没猜对。
     */
    @Test
    @DisplayName("全局额度用尽时，口令正确也先被挡回")
    void globalRateLimitAppliesToCredentialChecks() {
        ConfigUiAuthService service = serviceSharingThrottle();

        // 先由别处（换着 IP 来的爆破）把全局额度抽干
        drainGlobal(now.get());

        ConfigUiAuthService.CredentialCheck check = service.checkCredentials(PASSWORD.toCharArray(), null, IP);

        assertEquals(ConfigUiAuthService.Verdict.BUSY, check.verdict(),
                "额度用尽时应答复瞬时的「忙」, 而不是放行, 也不是说成锁定");
        assertFalse(check.retryAfter().isZero(), "应告知稍后重试");

        // 换个说法再验一次「不是锁定」：退还一个额度后，同一把口令立刻就能过
        throttle.refundGlobal();
        assertTrue(service.checkCredentials(PASSWORD.toCharArray(), null, IP).ok(),
                "额度一回来就该放行, 不该留下任何惩罚");
    }

    /**
     * 🔴 猜错的那些尝试必须真的把额度花掉
     * <p>
     * 上一条判据只证明「桶空了会拦」。若把退还写成无条件退还，桶就永远不会空——
     * 那时上一条照样绿（判据自己把桶抽干了），而这道限制对真正的爆破<b>一次也没生效过</b>。
     * <p>
     * 用三个不同的来源地址，避开按 IP 的锁定：这条要量的是全局那个桶，不是锁定。
     */
    @Test
    @DisplayName("猜错的尝试会消耗全局额度")
    void failedChecksSpendTheGlobalBudget() {
        ConfigUiAuthService service = serviceSharingThrottle();

        // 时钟停着（见 now 字段），所以三次 PBKDF2 花掉多少真实时间都不影响读数：
        // 桶按传进来的时刻补充，而这里从头到尾都是同一刻
        for (int i = 0; i < 3; i++) {
            ConfigUiAuthService.CredentialCheck check =
                    service.checkCredentials("猜的".toCharArray(), null, "203.0.113." + i);
            assertEquals(ConfigUiAuthService.Verdict.BAD_CREDENTIALS, check.verdict());
        }

        assertEquals(GLOBAL_BURST - 3, drainGlobal(now.get()),
                "三次失败的尝试应当从全局桶里扣掉三个令牌");
    }

    @Test
    @DisplayName("专用口键是闭集：现有四项以外，同前缀下的机密键不得自动算进去")
    void dedicatedAuthKeysAreAClosedSet() throws IOException {
        Set<String> expected = Set.of(
                ConfigUiAuthService.PASSWORD_PROPERTY,
                ConfigUiAuthService.TOTP_PROPERTY,
                ConfigUiAuthService.TOTP_SECRET_PROPERTY,
                ConfigUiAuthService.OPERATOR_TOKEN_PROPERTY);

        Set<String> actual = new TreeSet<>();
        for (String name : authKeysOnTheSurface()) {
            if (ConfigUiAuthService.isDedicatedAuthKey(name)) {
                actual.add(name);
            }
        }

        assertEquals(new TreeSet<>(expected), actual,
                "专用口键变多或变少都要改这一格，不能靠名字规则自动扩");
        assertFalse(ConfigUiAuthService.isDedicatedAuthKey(
                        "novabot.core.config-ui.auth.recovery-secret"),
                "尚未列入闭集的新机密键不得自动算专用口");
    }

    private static Set<String> authKeysOnTheSurface() throws IOException {
        String content;
        try (InputStream in = new ClassPathResource("configuration-baseline/config-keys.txt")
                .getInputStream()) {
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        Set<String> keys = new LinkedHashSet<>();
        for (String line : content.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String name = line.split("\\|")[0];
            if (name.startsWith("novabot.core.config-ui.auth.")) {
                keys.add(name);
            }
        }
        return keys;
    }
}
