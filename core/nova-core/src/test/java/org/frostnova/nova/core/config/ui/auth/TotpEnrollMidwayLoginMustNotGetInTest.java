package org.frostnova.nova.core.config.ui.auth;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 绑定办成那一步中途插进来的登录，拿核过的那枚码登不进来
 * <p>
 * 抓的用户故障：绑定成功的那一瞬间，拿同一枚码登录能进来一次。启用那一步此前先换上新密钥、
 * 最后才记「核中的那一格已用过」——这中间这几微秒里，登录那条路看得见新密钥
 * （{@code totpSecret} 是 volatile，一读就用上），而这一格还没记成用过，
 * 于是绑定核过的那枚码正好登进一次。那枚码正躺在截图、验证器的输入框与自动填充里。
 * <p>
 * 确定的次序复现：把一次登录插在「换上新密钥」与「记下这一格」之间。产品码在换上密钥之后、
 * 拨开开关之前清本会话的待绑密钥，覆写那一步就拿到了插登录的时机——
 * 不靠两线程去撞那几微秒：撞出来的绿不算绿，撞不出来的红也不算红。
 * <p>
 * 前提自查写在插桩里：那一刻新密钥必须已经在位（{@code totpRequired()} 为真）。
 * 将来若有人把清待绑密钥挪到换密钥之前，本格会因前提不成立而红，而不是量了个空。
 */
@DisplayName("绑定中途插进来的登录拿核过的那枚码登不进来")
class TotpEnrollMidwayLoginMustNotGetInTest {
    private static final String PASSWORD = "correct horse battery staple";

    /**
     * 判据自己的时钟，停着不走，要下一格自己拨：不靠真实窗口，也不量这台机器有多快
     */
    private final AtomicReference<Instant> clock = new AtomicReference<>(Instant.parse("2026-09-28T00:00:00Z"));

    @Test
    @DisplayName("🔴 换上新密钥之后、记下这一格之前插进来的登录：核过的那枚码必须登不进来")
    void loginInsertedBeforeTheVerifiedStepIsRecordedMustFail() {
        ConfigUiAuthService service = service();

        AtomicReference<String> code = new AtomicReference<>();
        MidwayLoginSession session = new MidwayLoginSession("probe-1", service, code);
        // 核中的码取下一格：构造服务时就把当格标成用过，当格的码走不到认中那步；
        // 校验窗口前后各容一格，下一格的码此刻照样认得出
        String secret = service.issuePendingSecret(session);
        code.set(TotpGenerator.currentCode(secret, clock.get().plusSeconds(30)));

        ConfigUiAuthService.PendingEnroll match = service.verifyPending(session, code.get()).orElse(null);
        assertNotNull(match, "台面：核过的那枚码要先认得出来，认不出来本格什么也没量");

        service.activateTotp(session, match.secret(), match.step());

        assertTrue(Boolean.TRUE.equals(session.newSecretVisible.get()),
                "前提：插进来那一刻新密钥必须已经在位，否则这趟没量到换密钥与记格之间的缝");
        ConfigUiAuthService.LoginResult sneaked = session.sneaked.get();
        assertNotNull(sneaked, "台面：插进来的那趟登录要有回值");
        assertFalse(sneaked.success(),
                "绑定中途插进来的登录拿核过的那枚码登进来了: " + sneaked.message()
                        + " session=" + (sneaked.session() == null ? "null" : sneaked.session().getId()));

        // 阴性对照：不是登录这条路坏了——拨到下一格，绑上的那把照常登得进
        clock.set(clock.get().plusSeconds(30));
        String next = TotpGenerator.currentCode(match.secret(), clock.get().plusSeconds(30));
        ConfigUiAuthService.LoginResult later = service.login(PASSWORD.toCharArray(), next, "192.0.2.2");
        assertTrue(later.success(), "绑完之后下一格的码要照常登得进: " + later.message());
    }

    private ConfigUiAuthService service() {
        NovaCoreProperties.ConfigUi.Auth properties = new NovaCoreProperties.ConfigUi.Auth();
        properties.setPassword(PASSWORD);
        properties.setTotp(true);
        properties.setTotpSecret(null);
        return new ConfigUiAuthService(properties,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(properties.getMaxFailures(), Duration.ofMinutes(15)), null,
                clock::get);
    }

    /**
     * 插桩点：包可见的 {@code clearPendingSecrets} 覆写
     * <p>
     * 产品码在绑定那一步里清本会话的待绑密钥。在现码里那正是「已换上新密钥、还没记下这一格」
     * 的时机，覆写它就等于把一次登录插在换密钥与记格之间，单线程就能跑出那道缝。
     * 刻意不在这儿加锁：要量的正是产品码自己那几步的次序，插桩再串一把会把次序掩过去。
     */
    private static final class MidwayLoginSession extends ConfigUiSession {
        final AtomicReference<ConfigUiAuthService.LoginResult> sneaked = new AtomicReference<>();
        final AtomicReference<Boolean> newSecretVisible = new AtomicReference<>();

        private final ConfigUiAuthService service;
        private final AtomicReference<String> code;

        MidwayLoginSession(String id, ConfigUiAuthService service, AtomicReference<String> code) {
            super(id, "csrf-" + id,
                    Instant.parse("2026-09-28T00:00:00Z"),
                    Instant.parse("2099-01-01T00:00:00Z"),
                    "192.0.2.1", Channel.PASSWORD, null);
            this.service = service;
            this.code = code;
        }

        @Override
        void clearPendingSecrets() {
            super.clearPendingSecrets();
            newSecretVisible.set(service.totpRequired());
            sneaked.set(service.login(PASSWORD.toCharArray(), code.get(), "192.0.2.1"));
        }
    }
}
