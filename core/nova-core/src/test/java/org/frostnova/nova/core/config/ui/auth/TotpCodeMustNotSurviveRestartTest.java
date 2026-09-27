package org.frostnova.nova.core.config.ui.auth;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigurationFileService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重启不得把已经用过的动态码救活
 * <p>
 * 抓的用户故障：已用过的时间步只记在内存里，重启后从「没用过」起——
 * 重启前 60～90 秒内刚登过一次的那枚码，重启后还能再登一次，
 * 而这枚码此刻还躺在截图、远程协助录像和浏览器自动填充里。
 * <p>
 * 启动时按服务自己那把钟把当格记成已用过的：当格与更早的格一律拒，
 * 下一格的码照常登得进（不能一刀切到下一格，那样刚重启的人要干等半分钟）。
 * 被挡下的码照旧回「密码或验证码不正确」、照旧记一次失败——
 * 另说「码已用过」等于告诉对方口令是对的，剩下的就只是六位数字。
 */
@DisplayName("重启后刚用过的那一格不能再登")
class TotpCodeMustNotSurviveRestartTest {
    private static final String PASSWORD = "correct horse battery staple";

    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");

    private static final String IP = "192.0.2.1";

    /**
     * 同一把钟：重启只换服务对象，不换钟
     */
    private final AtomicReference<Instant> clock = new AtomicReference<>(NOW);

    private ConfigUiAuthService service() {
        NovaCoreProperties.ConfigUi.Auth properties = new NovaCoreProperties.ConfigUi.Auth();
        properties.setPassword(PASSWORD);
        properties.setTotp(true);
        properties.setTotpSecret(SECRET);

        return new ConfigUiAuthService(properties,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(properties.getMaxFailures(), Duration.ofMinutes(15)),
                (ConfigurationFileService) null,
                clock::get);
    }

    /**
     * 某一格的码
     * @param now 这一格的时刻，往后 30 秒就是下一格
     */
    private String codeAt(Instant now) {
        return TotpGenerator.currentCode(SECRET, now);
    }

    @Test
    @DisplayName("🔴 重启前刚用过的那一格，重启后登不进；下一格的码照常登得进")
    void usedStepIsDeadAfterRestart() {
        // 重启前那一趟：当格在构造时已标成用过，能用的只剩下一格——它就是「重启前用过的码」
        String used = codeAt(NOW.plusSeconds(30));
        ConfigUiAuthService.LoginResult before = service().login(PASSWORD.toCharArray(), used, IP);
        assertTrue(before.success(), "台面没搭起来: " + before.message());

        // 重启＝新构造一个服务、同一把钟。时间自己走到了下一格，重启时的当格正是用过的那一格
        clock.set(NOW.plusSeconds(30));
        ConfigUiAuthService restarted = service();

        ConfigUiAuthService.LoginResult replay = restarted.login(PASSWORD.toCharArray(), used, "192.0.2.2");
        assertFalse(replay.success(), "重启前用过的码重启后还能再登一次: " + replay.message());

        // 被挡下的码不许另说「码已用过」：那等于告诉对方口令是对的
        ConfigUiAuthService.LoginResult guessed = restarted.login("猜的".toCharArray(), used, "192.0.2.3");
        assertEquals(replay.message(), guessed.message(),
                "被挡下的码与口令错得说成同一句话，否则对方知道口令已经猜对了");

        ConfigUiAuthService.LoginResult next = restarted.login(PASSWORD.toCharArray(), codeAt(NOW.plusSeconds(60)), "192.0.2.4");
        assertTrue(next.success(), "下一格的码照常登得进，不能把刚重启的人也堵在门外: " + next.message());
    }

    @Test
    @DisplayName("刚启动那一格里被挡下的码照旧记一次失败：堵死的那格不该是不计次的猜测口")
    void blockedStepStillCountsAsAFailure() {
        ConfigUiAuthService restarted = service();
        int max = new NovaCoreProperties.ConfigUi.Auth().getMaxFailures();
        String sameCell = codeAt(NOW);

        for (int i = 0; i < max; i++) {
            ConfigUiAuthService.LoginResult attempt = restarted.login(PASSWORD.toCharArray(), sameCell, IP);
            assertFalse(attempt.success(), "当格的码本就登不进: " + attempt.message());
        }

        // 换一枚没用过的码也进不来：上面几趟若不记失败，这里就成了不计次的猜测口
        ConfigUiAuthService.LoginResult after = restarted.login(PASSWORD.toCharArray(), codeAt(NOW.plusSeconds(30)), IP);
        assertFalse(after.success(), "被挡下的几趟必须记成失败，否则堵死的当格成了猜口令的免计数口: " + after.message());
        assertFalse(after.retryAfter().isZero(), "锁没锁、还要等多久得说清");
    }
}
