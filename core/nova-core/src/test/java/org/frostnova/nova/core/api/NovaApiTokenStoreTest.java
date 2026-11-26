package org.frostnova.nova.core.api;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.service.NovaStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 访问令牌的签发、落盘与上限
 */
class NovaApiTokenStoreTest {
    @TempDir
    Path directory;

    private NovaStateStore state;

    private NovaApiTokenStore tokens;

    private final TestClock clock = new TestClock();

    @BeforeEach
    void store() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(directory.resolve("live.json").toString());
        state = new NovaStateStore(properties);
        tokens = new NovaApiTokenStore(state, clock);
    }

    // 抓的故障：使用者签发之后，有人拿走数据目录里的运行状态文件，就能读出令牌明文直接调接口。
    @Test
    void diskHoldsOnlyTheDigestNeverThePlaintext() throws Exception {
        var issued = tokens.issue("local-admin", "客厅的脚本", List.of(), null);
        String disk = Files.readString(directory.resolve("state.json"), StandardCharsets.UTF_8);

        assertTrue(disk.contains(NovaApiTokenStore.NAMESPACE), "令牌没写进运行状态文件");
        assertFalse(disk.contains(issued.plaintext()), "运行状态文件里出现了令牌明文");
        String secret = issued.plaintext().substring(NovaApiTokenStore.PREFIX.length());
        assertFalse(disk.contains(secret), "运行状态文件里出现了去掉前缀的令牌明文");
        assertTrue(disk.contains(NovaApiTokenStore.digest(issued.plaintext())), "落盘的应是 SHA-256 摘要");
    }

    // 抓的故障：令牌看不出是什么东西，或者短到能猜。
    @Test
    void plaintextHasPrefixAndThirtyTwoRandomBytes() {
        String plaintext = tokens.issue("local-admin", "检查格式", List.of(), null).plaintext();
        assertTrue(plaintext.startsWith(NovaApiTokenStore.PREFIX));
        // 32 字节按 base64url 不补齐是 43 个字符
        assertEquals(NovaApiTokenStore.PREFIX.length() + 43, plaintext.length());
        assertTrue(plaintext.substring(NovaApiTokenStore.PREFIX.length()).matches("[A-Za-z0-9_-]{43}"));
    }

    // 抓的故障：两台机器上的脚本起了同一个名字，第二把签不出来。
    @Test
    void sameNameIsAllowed() {
        var first = tokens.issue("local-admin", "备份脚本", List.of(), null);
        var second = tokens.issue("local-admin", "备份脚本", List.of(), null);
        assertNotEquals(first.token().id(), second.token().id());
        assertNotEquals(first.plaintext(), second.plaintext());
        assertEquals(2, tokens.list().size());
    }

    // 抓的故障：一个账号能无限签发，或者第 21 把签不出来却不说为什么。
    @Test
    void twentyFirstActiveTokenIsRefusedWithAReason() {
        for (int i = 0; i < NovaApiTokenStore.MAX_ACTIVE_PER_ACCOUNT; i++) {
            tokens.issue("member-1", "第 " + (i + 1) + " 把", List.of(), null);
        }
        var refused = assertThrows(NovaApiTokenStore.LimitReached.class,
                () -> tokens.issue("member-1", "第 21 把", List.of(), null));
        assertTrue(refused.getMessage().contains("20"), refused.getMessage());
        assertTrue(refused.getMessage().contains("吊销"), refused.getMessage());
        assertEquals(20, tokens.list().size(), "被拒的那一把不该留下记录");

        // 别的账号不受这个账号的上限影响
        tokens.issue("member-2", "另一个账号", List.of(), null);
    }

    // 抓的故障：吊销或过期了的令牌仍占着名额，账号永远签不出新的。
    @Test
    void revokedAndExpiredTokensFreeTheirSlots() {
        String first = null;
        for (int i = 0; i < NovaApiTokenStore.MAX_ACTIVE_PER_ACCOUNT; i++) {
            var issued = tokens.issue("member-1", "令牌", List.of(), i == 1 ? Duration.ofDays(30) : null);
            if (i == 0) first = issued.token().id();
        }
        assertTrue(tokens.revoke(first).orElseThrow().changed());
        tokens.issue("member-1", "顶上吊销的那把", List.of(), null);

        clock.now = clock.now.plus(Duration.ofDays(31));
        tokens.issue("member-1", "顶上过期的那把", List.of(), null);
        assertThrows(NovaApiTokenStore.LimitReached.class, () -> tokens.issue("member-1", "再多一把", List.of(), null));
    }

    // 抓的故障：吊销后同一把令牌仍能对上一条「有效」的记录。
    @Test
    void revokedTokenMatchesOnlyAsRevoked() {
        var issued = tokens.issue("local-admin", "要吊销的", List.of(), null);
        assertFalse(tokens.match(issued.plaintext()).orElseThrow().revoked());
        tokens.revoke(issued.token().id());
        assertTrue(tokens.match(issued.plaintext()).orElseThrow().revoked());
        assertTrue(tokens.revoke(issued.token().id()).isPresent(), "再吊销一次不算失败");
        assertFalse(tokens.revoke(issued.token().id()).orElseThrow().changed());
    }

    // 抓的故障：随便一串、空串、少一个字符的令牌被当成某一把有效令牌。
    @Test
    void onlyTheExactPlaintextMatches() {
        var issued = tokens.issue("local-admin", "对比", List.of(), null);
        assertTrue(tokens.match(null).isEmpty());
        assertTrue(tokens.match("").isEmpty());
        assertTrue(tokens.match(issued.plaintext().substring(1)).isEmpty());
        assertTrue(tokens.match(NovaApiTokenStore.digest(issued.plaintext())).isEmpty(), "摘要本身不能当令牌用");
        assertEquals(issued.token().id(), tokens.match(issued.plaintext()).orElseThrow().id());
    }

    // 抓的故障：名字留空签出一把日后认不出来的令牌。
    @Test
    void blankOrOverlongNameIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> tokens.issue("local-admin", "  ", List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> tokens.issue("local-admin", "名".repeat(41), List.of(), null));
        assertTrue(tokens.list().isEmpty());
    }

    // 抓的故障：控制台列表里看不出令牌最近有没有被用过；或者每个请求都写一次盘。
    @Test
    void lastUseIsRecordedAtMinuteGranularity() {
        var issued = tokens.issue("local-admin", "看最后使用", List.of(), null);
        assertNull(issued.token().lastUsedAt());
        tokens.used(tokens.match(issued.plaintext()).orElseThrow());
        Instant first = tokens.find(issued.token().id()).orElseThrow().lastUsedAt();
        assertNotNull(first);

        clock.now = clock.now.plusSeconds(20);
        tokens.used(tokens.match(issued.plaintext()).orElseThrow());
        assertEquals(first, tokens.find(issued.token().id()).orElseThrow().lastUsedAt(), "一分钟内不重记");

        clock.now = clock.now.plusSeconds(60);
        tokens.used(tokens.match(issued.plaintext()).orElseThrow());
        assertTrue(tokens.find(issued.token().id()).orElseThrow().lastUsedAt().isAfter(first));
    }

    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-26T05:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
