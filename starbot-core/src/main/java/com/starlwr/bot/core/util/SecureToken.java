package com.starlwr.bot.core.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 访问令牌工具
 * <p>
 * 推送接口与配置界面都需要生成、校验访问令牌，此处集中实现，避免多处各写一份加密逻辑。
 */
public final class SecureToken {
    /**
     * 令牌最小长度，低于此长度视为弱令牌
     */
    public static final int MIN_LENGTH = 16;

    /**
     * 自动生成令牌时使用的随机字节数
     */
    private static final int GENERATED_BYTES = 32;

    /**
     * 常见弱口令片段，令牌中包含这些片段即视为过弱
     */
    private static final String[] WEAK_FRAGMENTS = {"token", "password", "123456", "starbot", "admin", "test", "changeme"};

    private static final SecureRandom RANDOM = new SecureRandom();

    private SecureToken() {
    }

    /**
     * 生成一个高强度随机令牌
     * @return 令牌
     */
    public static String generate() {
        byte[] bytes = new byte[GENERATED_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 以恒定时间比对两个令牌
     * <p>
     * 使用 {@link MessageDigest#isEqual} 而非字符串相等比较，避免通过响应耗时差异逐字节爆破令牌。
     * @param expected 期望的令牌
     * @param presented 请求携带的令牌，可为 null
     * @return 是否一致
     */
    public static boolean verify(String expected, String presented) {
        if (expected == null || expected.isEmpty()) {
            return false;
        }

        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);

        if (presented == null || presented.isEmpty()) {
            // 仍然比对一次, 使「未携带令牌」与「令牌错误」的耗时保持一致
            MessageDigest.isEqual(expectedBytes, new byte[expectedBytes.length]);
            return false;
        }

        return MessageDigest.isEqual(expectedBytes, presented.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 判断令牌是否过弱
     * @param token 待判断的令牌
     * @return 是否过弱
     */
    public static boolean isWeak(String token) {
        if (token == null || token.strip().length() < MIN_LENGTH) {
            return true;
        }

        String normalized = token.strip().toLowerCase();
        for (String fragment : WEAK_FRAGMENTS) {
            if (normalized.contains(fragment)) {
                return true;
            }
        }

        // 仅由一两种字符重复构成的令牌同样视为弱令牌
        return normalized.chars().distinct().count() <= 2;
    }

    /**
     * 生成令牌指纹，用于在日志中标识令牌而不泄露其本身
     * @param token 令牌
     * @return 指纹字符串
     */
    public static String fingerprint(String token) {
        if (token == null || token.isEmpty()) {
            return "none";
        }

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 8);
        } catch (Exception e) {
            return "unavailable";
        }
    }
}
