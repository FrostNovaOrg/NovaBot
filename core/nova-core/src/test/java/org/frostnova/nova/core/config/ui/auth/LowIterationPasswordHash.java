package org.frostnova.nova.core.config.ui.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 测试配置里当口令哈希用的低迭代编码串
 * <p>
 * 编码格式与 {@link PasswordHash} 产出的逐段一致（{@code pbkdf2$次数$盐$密钥}，盐 16 字节、
 * 密钥 32 字节），只有迭代次数是 1。校验按串里写的次数算，所以比对一瞬间完成：
 * 认证相关的判据要量的是口令对不对、动态码还能不能用、会话注没注销，不是哈希本身有多慢；
 * 一格判据里几趟真算 60 万次迭代就是好几秒，整套测试跟着机器快慢漂。
 * <p>
 * 用它换掉的是准备阶段填进配置的那份口令（明文改哈希），提交口令的那一侧照旧送明文。
 * 专测哈希与明文落盘的那几组（强度、启动时明文换哈希写回、改口令存新口令）不要用它，
 * 那些组量的正是它省掉的东西，照旧走 {@link PasswordHash}。
 * <p>
 * 自己用 JDK 的 SecretKeyFactory 算，不进 {@link PasswordHash} 的私有方法：
 * 正式代码不为测试留口。
 */
public final class LowIterationPasswordHash {
    private static final SecureRandom RANDOM = new SecureRandom();

    private LowIterationPasswordHash() {
    }

    /**
     * 给一段明文口令算出只迭代 1 次的编码串
     * @param plaintext 明文口令
     * @return 可直接填进配置的编码串，格式与正式哈希一致
     */
    public static String of(String plaintext) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);

        PBEKeySpec spec = new PBEKeySpec(plaintext.toCharArray(), salt, 1, 256);
        try {
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            Base64.Encoder encoder = Base64.getEncoder().withoutPadding();
            return "pbkdf2$1$" + encoder.encodeToString(salt) + "$" + encoder.encodeToString(key);
        } catch (Exception e) {
            throw new IllegalStateException("算不出测试用的口令哈希", e);
        } finally {
            spec.clearPassword();
        }
    }
}
