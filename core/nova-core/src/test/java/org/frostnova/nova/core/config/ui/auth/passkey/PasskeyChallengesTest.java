package org.frostnova.nova.core.config.ui.auth.passkey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 挑战表本身的规矩：分来源限额之后，总量仍有上限，过期、一次一用、用途不串照旧
 */
@DisplayName("通行密钥挑战表")
class PasskeyChallengesTest {
    private static final Instant NOW = Instant.parse("2026-09-27T05:00:00Z");

    private final PasskeyChallenges challenges = new PasskeyChallenges();

    @Test
    @DisplayName("换着地址要一千次，表也不超过登录上限")
    void totalStaysBoundedAcrossManyAddresses() {
        for (int i = 0; i < 1000; i++) {
            challenges.issue(PasskeyChallenges.Purpose.LOGIN, "198.51.100." + (i % 250) + "/" + i, NOW);
        }
        assertTrue(challenges.size() <= 64, "内存不能跟着换地址无限涨, 实有 " + challenges.size());
    }

    @Test
    @DisplayName("一个地址占满之后，淘汰的是它自己最早的那条，最新的仍能用")
    void sameAddressEvictsItsOwnOldest() {
        String first = challenges.issue(PasskeyChallenges.Purpose.LOGIN, "203.0.113.9", NOW);
        String last = null;
        for (int i = 0; i < 100; i++) {
            last = challenges.issue(PasskeyChallenges.Purpose.LOGIN, "203.0.113.9", NOW.plusMillis(i + 1));
        }

        assertTrue(challenges.size() <= 8, "一个地址只占得住自己那几条, 实有 " + challenges.size());
        assertFalse(challenges.consume(first, PasskeyChallenges.Purpose.LOGIN, NOW), "它自己最早那条该被挤掉");
        assertTrue(challenges.consume(last, PasskeyChallenges.Purpose.LOGIN, NOW), "它刚要的那条该还能用");
    }

    @Test
    @DisplayName("许多地址把总数占满时，先淘汰占得最多的，只占一条的主人不动")
    void fullTableEvictsFromHeaviestAddress() {
        String mine = challenges.issue(PasskeyChallenges.Purpose.LOGIN, "127.0.0.1", NOW);
        for (int source = 0; source < 8; source++) {
            for (int i = 0; i < 8; i++) {
                challenges.issue(PasskeyChallenges.Purpose.LOGIN, "203.0.113." + source,
                        NOW.plusMillis(source * 8L + i + 1));
            }
        }

        assertTrue(challenges.size() <= 64, "实有 " + challenges.size());
        assertTrue(challenges.consume(mine, PasskeyChallenges.Purpose.LOGIN, NOW), "主人那条最早, 但不该轮到它");
    }

    @Test
    @DisplayName("IPv4 映射的 IPv6 地址与原 IPv4 地址算同一个来源")
    void mappedIpv4CountsAsTheSameSource() {
        for (int i = 0; i < 100; i++) {
            String source = i % 2 == 0 ? "203.0.113.9" : "::ffff:203.0.113.9";
            challenges.issue(PasskeyChallenges.Purpose.LOGIN, source, NOW.plusMillis(i));
        }
        assertTrue(challenges.size() <= 8, "两种写法轮着要, 也只占得住一个地址那几条, 实有 " + challenges.size());
    }

    @Test
    @DisplayName("过了五分钟就不认")
    void expiresAfterTtl() {
        String alive = challenges.issue(PasskeyChallenges.Purpose.LOGIN, "127.0.0.1", NOW);
        assertTrue(challenges.consume(alive, PasskeyChallenges.Purpose.LOGIN, NOW.plus(Duration.ofMinutes(5)).minusSeconds(1)),
                "阳性对照：差一秒到期该还认");

        String stale = challenges.issue(PasskeyChallenges.Purpose.LOGIN, "127.0.0.1", NOW);
        assertFalse(challenges.consume(stale, PasskeyChallenges.Purpose.LOGIN, NOW.plus(Duration.ofMinutes(5))),
                "到期那一刻就不该再认");
    }

    @Test
    @DisplayName("登录挑战走登记那条路不认，而且就此作废")
    void purposeMismatchRejectsAndBurns() {
        String login = challenges.issue(PasskeyChallenges.Purpose.LOGIN, "127.0.0.1", NOW);

        assertFalse(challenges.consume(login, PasskeyChallenges.Purpose.REGISTER, NOW),
                "拿登录挑战去登记, 产物会是一把新钥匙, 该拒");
        assertFalse(challenges.consume(login, PasskeyChallenges.Purpose.LOGIN, NOW), "拒过一次之后也不再认");
    }
}
