package com.starlwr.bot.core.config.ui.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录会话存储测试
 * <p>
 * 会话 Cookie 是面板对公网开放后唯一的凭据，这里要守住的是它的<b>有效期不能被使用行为无限延长</b>：
 * 一个被偷走的会话必须到点失效。
 */
@DisplayName("登录会话存储")
class ConfigUiSessionStoreTest {
    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");

    private static final Duration TTL = Duration.ofHours(24);

    private static final Duration IDLE = Duration.ofHours(2);

    private ConfigUiSessionStore store;

    @BeforeEach
    void setUp() {
        store = new ConfigUiSessionStore(TTL, IDLE);
    }

    @Test
    @DisplayName("签发的会话可以校验通过")
    void issuedSessionIsValid() {
        ConfigUiSession session = store.issue("127.0.0.1", NOW);

        assertTrue(store.validate(session.getId(), NOW).isPresent());
    }

    @Test
    @DisplayName("会话标识与 CSRF 令牌必须是两个不同的值")
    void csrfTokenDiffersFromSessionId() {
        ConfigUiSession session = store.issue("127.0.0.1", NOW);

        assertNotEquals(session.getId(), session.getCsrfToken(),
                "两者相同等于没有 CSRF 防护——浏览器会自动把 Cookie 送出去，那这个值就不再是「只有本站页面知道」");
    }

    @Test
    @DisplayName("不存在或空的会话标识一律不通过")
    void unknownSessionIsRejected() {
        assertTrue(store.validate("不存在", NOW).isEmpty());
        assertTrue(store.validate(null, NOW).isEmpty());
        assertTrue(store.validate("  ", NOW).isEmpty());
    }

    @Test
    @DisplayName("到达绝对期限后失效")
    void expiresAtAbsoluteDeadline() {
        // 闲置期限放到比绝对期限还长，让这个用例只受绝对期限支配
        ConfigUiSessionStore store = new ConfigUiSessionStore(TTL, TTL.multipliedBy(2));
        ConfigUiSession session = store.issue("127.0.0.1", NOW);

        assertTrue(store.validate(session.getId(), NOW.plus(TTL).minusSeconds(1)).isPresent());
        assertTrue(store.validate(session.getId(), NOW.plus(TTL)).isEmpty());
    }

    @Test
    @DisplayName("持续使用不能把绝对期限往后拖")
    void useDoesNotExtendAbsoluteDeadline() {
        ConfigUiSession session = store.issue("127.0.0.1", NOW);

        // 每小时用一次，一直用到绝对期限
        for (long hour = 1; hour < TTL.toHours(); hour++) {
            assertTrue(store.validate(session.getId(), NOW.plusSeconds(hour * 3600)).isPresent());
        }

        assertTrue(store.validate(session.getId(), NOW.plus(TTL)).isEmpty(),
                "绝对期限若能被使用顺延，被偷走的会话就可以一直续命");
    }

    @Test
    @DisplayName("闲置超过时限后失效，期间用过则重新计时")
    void expiresWhenIdle() {
        ConfigUiSession idle = store.issue("127.0.0.1", NOW);
        assertTrue(store.validate(idle.getId(), NOW.plus(IDLE)).isEmpty());

        ConfigUiSession active = store.issue("127.0.0.1", NOW);
        assertTrue(store.validate(active.getId(), NOW.plus(IDLE).minusSeconds(1)).isPresent());
        assertTrue(store.validate(active.getId(), NOW.plus(IDLE).plusSeconds(1)).isPresent(),
                "中途用过一次，闲置计时应从那一刻重新开始");
    }

    @Test
    @DisplayName("注销后立即失效")
    void revokedSessionIsRejected() {
        ConfigUiSession session = store.issue("127.0.0.1", NOW);
        store.revoke(session.getId());

        assertTrue(store.validate(session.getId(), NOW).isEmpty());
    }

    @Test
    @DisplayName("全部注销后没有会话能留下")
    void revokeAllClearsEverything() {
        ConfigUiSession a = store.issue("127.0.0.1", NOW);
        ConfigUiSession b = store.issue("10.0.0.2", NOW);

        assertEquals(2, store.revokeAll());
        assertTrue(store.validate(a.getId(), NOW).isEmpty());
        assertTrue(store.validate(b.getId(), NOW).isEmpty());
    }

    @Test
    @DisplayName("反复登录不会把会话表撑大")
    void doesNotGrowUnbounded() {
        for (int i = 0; i < 500; i++) {
            store.issue("127.0.0.1", NOW.plusSeconds(i));
        }

        // 撑爆之后新签发的仍要可用，说明淘汰的是旧的而不是刚发的
        ConfigUiSession fresh = store.issue("127.0.0.1", NOW.plusSeconds(600));
        assertTrue(store.validate(fresh.getId(), NOW.plusSeconds(600)).isPresent());

        assertTrue(store.revokeAll() <= 32, "会话数未受上限约束");
    }

    @Test
    @DisplayName("过期的会话不应一直占着内存")
    void expiredSessionsAreSweptAway() {
        for (int i = 0; i < 5; i++) {
            store.issue("127.0.0.1", NOW.plusSeconds(i));
        }

        // 新签发时会顺带把过期的扫掉
        store.issue("127.0.0.1", NOW.plus(TTL).plus(IDLE));

        assertEquals(1, store.revokeAll(), "过期会话未被清理");
    }
}
