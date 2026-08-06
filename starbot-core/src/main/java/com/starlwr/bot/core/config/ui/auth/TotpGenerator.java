package com.starlwr.bot.core.config.ui.auth;

import lombok.NonNull;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * 基于时间的一次性口令（RFC 6238）
 * <p>
 * 与各类验证器 App（Google Authenticator、Authy、1Password 等）通用的那一套：
 * HMAC-SHA1、30 秒步长、6 位数字。<b>这三个参数不能随手改</b>——
 * 它们是事实标准，改了之后主流 App 都算不出对得上的码。
 * <p>
 * 同样不引第三方库：HMAC 与 Base32 都是几十行的事，而依赖是要长期维护的。
 *
 * <h2>为什么允许前后各一个时间窗</h2>
 * 手机与服务器的时钟总有偏差，只认当前窗口会让一部分人永远登不上，
 * 且这种失败毫无提示价值——用户看到的只是「验证码错误」。
 * 放宽一个窗口把容忍度提到 ±30 秒，代价是验证码的有效期从 30 秒变成最多 90 秒，
 * 对这个场景完全可以接受。
 */
public final class TotpGenerator {
    private static final String ALGORITHM = "HmacSHA1";

    /**
     * 时间步长（秒）
     */
    private static final long STEP_SECONDS = 30;

    /**
     * 验证码位数
     */
    private static final int DIGITS = 6;

    /**
     * 前后各容忍几个时间窗
     */
    private static final int WINDOW = 1;

    /**
     * 密钥字节数。RFC 4226 要求至少 128 位，推荐 160 位
     */
    private static final int SECRET_BYTES = 20;

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private static final SecureRandom RANDOM = new SecureRandom();

    private TotpGenerator() {
    }

    /**
     * 生成一个新密钥
     * @return Base32 编码的密钥，可直接给验证器 App 扫或手工录入
     */
    public static String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return base32Encode(bytes);
    }

    /**
     * 校验验证码
     * @param secret Base32 密钥
     * @param code 用户输入的验证码
     * @param now 当前时刻
     * @return 是否有效
     */
    public static boolean verify(String secret, String code, @NonNull Instant now) {
        if (secret == null || secret.isBlank() || code == null) {
            return false;
        }

        String normalized = code.replaceAll("\\s", "");
        if (normalized.length() != DIGITS) {
            return false;
        }

        byte[] key;
        try {
            key = base32Decode(secret);
        } catch (IllegalArgumentException e) {
            return false;
        }

        long counter = now.getEpochSecond() / STEP_SECONDS;
        for (int offset = -WINDOW; offset <= WINDOW; offset++) {
            if (constantTimeEquals(generate(key, counter + offset), normalized)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 构造验证器 App 用的 otpauth 链接，可直接编码成二维码
     * @param secret Base32 密钥
     * @param account 账号显示名
     * @param issuer 服务名
     * @return otpauth://totp/... 链接
     */
    public static String provisioningUri(@NonNull String secret, @NonNull String account, @NonNull String issuer) {
        String label = urlEncode(issuer) + ":" + urlEncode(account);
        return "otpauth://totp/" + label
                + "?secret=" + secret
                + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    /**
     * 按 RFC 4226 的动态截断算出验证码
     */
    static String generate(byte[] key, long counter) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());

            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);

            return String.format("%0" + DIGITS + "d", binary % (int) Math.pow(10, DIGITS));
        } catch (Exception e) {
            throw new IllegalStateException("当前 JRE 不支持 " + ALGORITHM + "，无法校验两步验证码", e);
        }
    }

    /**
     * 定长比较，避免通过耗时泄漏「前几位对了」
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    static String base32Encode(byte[] data) {
        StringBuilder text = new StringBuilder();
        int buffer = 0;
        int bits = 0;

        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                text.append(BASE32.charAt((buffer >> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) {
            text.append(BASE32.charAt((buffer << (5 - bits)) & 0x1F));
        }
        return text.toString();
    }

    static byte[] base32Decode(String text) {
        String normalized = text.trim().replace("=", "").replaceAll("\\s", "").toUpperCase();
        int buffer = 0;
        int bits = 0;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

        for (char c : normalized.toCharArray()) {
            int index = BASE32.indexOf(c);
            if (index < 0) {
                throw new IllegalArgumentException("不是合法的 Base32 字符: " + c);
            }
            buffer = (buffer << 5) | index;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }
}
