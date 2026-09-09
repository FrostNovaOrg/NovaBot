package org.frostnova.nova.core.lang;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 令牌生成与强度判定测试
 */
@DisplayName("安全令牌")
class SecureTokenTest {
    /**
     * ⚠️ 这条曾经是随机失败的来源
     * <p>
     * 随机的 Base64 串里恰好出现 {@code test} 这类弱片段并非不可能：43 个字符位置、
     * 每个位置命中一个指定四字母序列的概率是 {@code (2/64)^4}（弱片段比对会折叠大小写），
     * 一次生成约千分之一。2026-08-08 的一次构建就真的撞上了。
     * <p>
     * 后果不只是测试红：自动生成的令牌会被我们自己的弱令牌检查拒掉，
     * 而使用者对着一个看起来完全随机的串根本无从判断哪里弱。修法是生成时重掷。
     */
    @Test
    @DisplayName("⚠️ 自动生成的令牌绝不会撞上弱片段")
    void generatedTokenIsNeverWeak() {
        for (int i = 0; i < 3000; i++) {
            String token = SecureToken.generate();
            assertFalse(SecureToken.isWeak(token), "生成出了弱令牌: " + token);
        }
    }

    @Test
    @DisplayName("自动生成的令牌足够长且互不重复")
    void generatedTokensAreLongAndUnique() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String token = SecureToken.generate();
            assertTrue(token.length() >= SecureToken.MIN_LENGTH);
            tokens.add(token);
        }
        assertEquals(500, tokens.size());
    }

    @Test
    @DisplayName("弱令牌能被识别")
    void weakTokensAreRejected() {
        assertTrue(SecureToken.isWeak(null));
        assertTrue(SecureToken.isWeak(""));
        assertTrue(SecureToken.isWeak("short"));
        assertTrue(SecureToken.isWeak("my-secret-TOKEN-value"), "弱片段比对要折叠大小写");
        assertTrue(SecureToken.isWeak("aaaaaaaaaaaaaaaaaaaaaa"), "只由一两种字符构成的同样算弱");
        assertTrue(SecureToken.isWeak("   starbot-starbot   "), "首尾空白要先剥掉再判断");
    }

    @Test
    @DisplayName("指纹不泄露令牌本身")
    void fingerprintDoesNotLeakToken() {
        String token = SecureToken.generate();
        String fingerprint = SecureToken.fingerprint(token);

        assertEquals(8, fingerprint.length());
        assertFalse(token.contains(fingerprint), "指纹是令牌的一段就等于泄露了一段");
        assertEquals(fingerprint, SecureToken.fingerprint(token), "同一令牌的指纹必须稳定");
        assertEquals("none", SecureToken.fingerprint(null));
    }
}
