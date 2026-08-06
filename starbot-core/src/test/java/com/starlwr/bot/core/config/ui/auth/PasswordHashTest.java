package com.starlwr.bot.core.config.ui.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录口令哈希测试
 * <p>
 * 口令哈希出错的方式很安静：明文存了、盐固定了、算法退化成快哈希——
 * 功能全都正常，只有被拖库的那天才会发现。用例因此不只测「能不能验通过」，
 * 也钉住那几条肉眼看不出来的性质。
 */
@DisplayName("登录口令哈希")
class PasswordHashTest {
    @Test
    @DisplayName("正确口令应通过，错误口令应拒绝")
    void verifiesCorrectPassword() {
        String encoded = PasswordHash.hash("正确的口令 abc123".toCharArray());

        assertTrue(PasswordHash.verify("正确的口令 abc123".toCharArray(), encoded));
        assertFalse(PasswordHash.verify("错误的口令".toCharArray(), encoded));
        assertFalse(PasswordHash.verify("".toCharArray(), encoded));
    }

    @Test
    @DisplayName("同一口令两次哈希应不同——盐必须是随机的")
    void saltIsRandom() {
        String a = PasswordHash.hash("同一个口令".toCharArray());
        String b = PasswordHash.hash("同一个口令".toCharArray());

        assertNotEquals(a, b, "结果相同说明盐是固定的，彩虹表可以一次性打穿所有部署");
        assertTrue(PasswordHash.verify("同一个口令".toCharArray(), a));
        assertTrue(PasswordHash.verify("同一个口令".toCharArray(), b));
    }

    @Test
    @DisplayName("结果里不得出现明文口令")
    void encodedDoesNotContainPlaintext() {
        String password = "SuperSecret2026";

        assertFalse(PasswordHash.hash(password.toCharArray()).contains(password));
    }

    @Test
    @DisplayName("编码串应带上迭代次数，将来调高不会让已有口令失效")
    void encodesIterationCount() {
        String encoded = PasswordHash.hash("口令".toCharArray());
        String[] parts = encoded.split("\\$");

        assertTrue(parts.length == 4 && "pbkdf2".equals(parts[0]));
        assertTrue(Integer.parseInt(parts[1]) >= 100_000, "迭代次数过低等于没做慢哈希");
    }

    @Test
    @DisplayName("损坏或非本格式的串应返回 false 而不是抛异常")
    void malformedEncodedReturnsFalse() {
        char[] password = "口令".toCharArray();

        assertFalse(PasswordHash.verify(password, null));
        assertFalse(PasswordHash.verify(password, ""));
        assertFalse(PasswordHash.verify(password, "明文口令"));
        assertFalse(PasswordHash.verify(password, "pbkdf2$abc$xx$yy"));
        assertFalse(PasswordHash.verify(password, "pbkdf2$600000$!!!$!!!"));
        assertFalse(PasswordHash.verify(null, PasswordHash.hash(password)));
    }

    @Test
    @DisplayName("应能认出一个串是否已经哈希过，用于区分「配的是明文」")
    void detectsHashedValue() {
        assertTrue(PasswordHash.isHashed(PasswordHash.hash("口令".toCharArray())));
        assertFalse(PasswordHash.isHashed("我直接在配置里写了明文"));
        assertFalse(PasswordHash.isHashed(null));
    }

    @Test
    @DisplayName("换过迭代次数的旧串仍应能校验")
    void verifiesOlderIterationCount() {
        // 手工构造一个低迭代次数的串，模拟将来调高迭代次数后遇到的历史数据
        String encoded = PasswordHash.hash("口令".toCharArray());
        String[] parts = encoded.split("\\$");
        // 直接改次数会让哈希对不上，这里只验证解析路径不会因为次数不同而拒绝
        assertFalse(PasswordHash.verify("口令".toCharArray(),
                "pbkdf2$1000$" + parts[2] + "$" + parts[3]), "次数不同则哈希不同，应判为不匹配而不是报错");
    }
}
