package com.starlwr.bot.core.config.ui.auth;

import lombok.NonNull;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 配置界面登录口令的哈希
 * <p>
 * <b>为什么是 PBKDF2 而不是 Argon2id。</b>Argon2 抗 GPU 更好，但本项目至今没有引入
 * 任何加密库，为一个单用户登录口令新增一条依赖并不划算。PBKDF2-HMAC-SHA256 是 JDK 自带的，
 * 迭代次数拉满之后对「一台机器上的单用户面板」这个威胁模型足够，
 * 且不引入任何供应链风险。
 * <p>
 * <b>绝不能退化成 SHA-256 之类的快哈希</b>：那类算法就是为了算得快而设计的，
 * 拿来存口令等于把暴力破解的成本从「几年」降到「几分钟」。
 * <p>
 * 编码格式为 {@code pbkdf2$<迭代次数>$<盐 Base64>$<哈希 Base64>}，
 * 迭代次数写进串里，将来调高不会让已有口令失效。
 */
public final class PasswordHash {
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";

    private static final String PREFIX = "pbkdf2";

    /**
     * 迭代次数
     * <p>
     * OWASP 对 PBKDF2-HMAC-SHA256 的建议值是 60 万次。登录是低频操作，
     * 单次的代价（开发机实测约 570 毫秒）换来的是离线爆破慢六个数量级。
     * <p>
     * 这个代价也是有副作用的：它同样落在服务端，因此登录接口必须限流，
     * 否则未登录的请求就成了消耗 CPU 的杠杆，见 {@link LoginThrottle}。
     */
    private static final int ITERATIONS = 600_000;

    private static final int SALT_BYTES = 16;

    private static final int KEY_BITS = 256;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHash() {
    }

    /**
     * 计算口令哈希
     * @param password 明文口令
     * @return 可直接落盘的编码串
     */
    public static String hash(@NonNull char[] password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] key = derive(password, salt, ITERATIONS);

        Base64.Encoder encoder = Base64.getEncoder().withoutPadding();
        return PREFIX + "$" + ITERATIONS + "$" + encoder.encodeToString(salt) + "$" + encoder.encodeToString(key);
    }

    /**
     * 校验口令
     * <p>
     * 比对走 {@link MessageDigest#isEqual}——<b>逐字节短路比较会通过耗时泄漏出
     * 「前几位对了」这个信息</b>，攻击者可据此逐位试出哈希。
     * @param password 明文口令
     * @param encoded 已存储的编码串
     * @return 是否匹配。编码串损坏时返回 false 而不是抛异常
     */
    public static boolean verify(char[] password, String encoded) {
        if (password == null || encoded == null) {
            return false;
        }

        String[] parts = encoded.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }

        try {
            int iterations = Integer.parseInt(parts[1]);
            Base64.Decoder decoder = Base64.getDecoder();
            byte[] salt = decoder.decode(parts[2]);
            byte[] expected = decoder.decode(parts[3]);

            return MessageDigest.isEqual(derive(password, salt, iterations), expected);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判断一个串是否是本类产出的格式
     */
    public static boolean isHashed(String value) {
        return value != null && value.startsWith(PREFIX + "$");
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            // 算法是 JDK 自带的，取不到说明运行环境被裁剪过，这种情况下不能静默放行
            throw new IllegalStateException("当前 JRE 不支持 " + ALGORITHM + "，无法校验配置界面口令", e);
        } finally {
            spec.clearPassword();
        }
    }
}
