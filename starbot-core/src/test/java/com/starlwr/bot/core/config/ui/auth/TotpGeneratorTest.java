package com.starlwr.bot.core.config.ui.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两步验证码测试
 * <p>
 * <b>关键用例是 RFC 6238 的官方测试向量。</b>自己生成再自己校验只能证明「前后一致」，
 * 证明不了「与验证器 App 一致」——而后者才是这段代码唯一的用途。
 * 实现里任何一处偏差（字节序、截断方式、步长）都会让主流 App 算出来的码永远对不上，
 * 而自证式的用例对此毫无察觉。
 */
@DisplayName("两步验证码")
class TotpGeneratorTest {
    /**
     * RFC 6238 附录 B 的 SHA-1 测试密钥："12345678901234567890"
     */
    private static final byte[] RFC_KEY = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    @DisplayName("应与 RFC 6238 官方测试向量逐条对上")
    void matchesRfc6238Vectors() {
        // 附录 B 表格里的 SHA-1 行：时间戳 → 期望验证码（取后 6 位）
        assertEquals("287082", TotpGenerator.generate(RFC_KEY, 59L / 30));
        assertEquals("081804", TotpGenerator.generate(RFC_KEY, 1111111109L / 30));
        assertEquals("050471", TotpGenerator.generate(RFC_KEY, 1111111111L / 30));
        assertEquals("005924", TotpGenerator.generate(RFC_KEY, 1234567890L / 30));
        assertEquals("279037", TotpGenerator.generate(RFC_KEY, 2000000000L / 30));
    }

    @Test
    @DisplayName("Base32 编解码应可逆，且与标准字母表一致")
    void base32RoundTrip() {
        // RFC 4648 的标准向量
        assertEquals("MZXW6YTBOI", TotpGenerator.base32Encode("foobar".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("foobar", new String(TotpGenerator.base32Decode("MZXW6YTBOI"), StandardCharsets.US_ASCII));
        assertEquals("foobar", new String(TotpGenerator.base32Decode("mzxw6ytboi"), StandardCharsets.US_ASCII),
                "小写也应能解，用户手抄密钥时大小写不一定对");
    }

    @Test
    @DisplayName("当前时刻的验证码应通过校验")
    void verifiesCurrentCode() {
        String secret = TotpGenerator.generateSecret();
        Instant now = Instant.parse("2026-08-06T12:00:00Z");
        String code = TotpGenerator.generate(TotpGenerator.base32Decode(secret), now.getEpochSecond() / 30);

        assertTrue(TotpGenerator.verify(secret, code, now));
    }

    @Test
    @DisplayName("前后一个时间窗内的验证码也应通过，容忍手机与服务器的时钟偏差")
    void toleratesClockSkew() {
        String secret = TotpGenerator.generateSecret();
        Instant now = Instant.parse("2026-08-06T12:00:00Z");
        byte[] key = TotpGenerator.base32Decode(secret);
        long counter = now.getEpochSecond() / 30;

        assertTrue(TotpGenerator.verify(secret, TotpGenerator.generate(key, counter - 1), now), "慢 30 秒也应通过");
        assertTrue(TotpGenerator.verify(secret, TotpGenerator.generate(key, counter + 1), now), "快 30 秒也应通过");
    }

    @Test
    @DisplayName("超出容忍范围的验证码应被拒绝，否则等于没有时效")
    void rejectsCodeOutsideWindow() {
        String secret = TotpGenerator.generateSecret();
        Instant now = Instant.parse("2026-08-06T12:00:00Z");
        byte[] key = TotpGenerator.base32Decode(secret);
        long counter = now.getEpochSecond() / 30;

        assertFalse(TotpGenerator.verify(secret, TotpGenerator.generate(key, counter - 5), now));
        assertFalse(TotpGenerator.verify(secret, TotpGenerator.generate(key, counter + 5), now));
    }

    @Test
    @DisplayName("空密钥、错位数、非数字输入都应被拒绝而不是抛异常")
    void rejectsMalformedInput() {
        Instant now = Instant.now();
        String secret = TotpGenerator.generateSecret();

        assertFalse(TotpGenerator.verify(null, "123456", now));
        assertFalse(TotpGenerator.verify("", "123456", now));
        assertFalse(TotpGenerator.verify(secret, null, now));
        assertFalse(TotpGenerator.verify(secret, "12345", now), "位数不对");
        assertFalse(TotpGenerator.verify(secret, "1234567", now));
        assertFalse(TotpGenerator.verify("不是Base32!!", "123456", now), "密钥损坏时不该抛异常");
    }

    @Test
    @DisplayName("验证码里的空格应被容忍——App 常按 3+3 分组显示")
    void toleratesSpacesInCode() {
        String secret = TotpGenerator.generateSecret();
        Instant now = Instant.parse("2026-08-06T12:00:00Z");
        String code = TotpGenerator.generate(TotpGenerator.base32Decode(secret), now.getEpochSecond() / 30);

        assertTrue(TotpGenerator.verify(secret, code.substring(0, 3) + " " + code.substring(3), now));
    }

    @Test
    @DisplayName("每次生成的密钥都应不同")
    void secretsAreRandom() {
        assertNotEquals(TotpGenerator.generateSecret(), TotpGenerator.generateSecret());
    }

    @Test
    @DisplayName("otpauth 链接应带齐 App 需要的参数")
    void provisioningUriHasRequiredParameters() {
        String uri = TotpGenerator.provisioningUri("ABCDEFGH", "主播", "NovaBot");

        assertTrue(uri.startsWith("otpauth://totp/"));
        assertTrue(uri.contains("secret=ABCDEFGH"));
        assertTrue(uri.contains("issuer=NovaBot"));
        assertTrue(uri.contains("digits=6"));
        assertTrue(uri.contains("period=30"));
        assertFalse(uri.contains("主播"), "中文须转义，否则部分 App 扫不出来");
    }
}
