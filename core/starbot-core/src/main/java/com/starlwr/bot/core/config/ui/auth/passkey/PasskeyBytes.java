package com.starlwr.bot.core.config.ui.auth.passkey;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * 通行密钥这条路上共用的几件字节活
 * <p>
 * 只有三样：base64url 编解码与 SHA-256。集中在一处不是为了少写几行，
 * 而是为了让「用的是哪一种 base64」只有一个答案——WebAuthn 全程用的是
 * <b>不带填充的 URL 安全变体</b>，而标准变体的 {@code +} 与 {@code /} 一旦混进来，
 * 表现是某些凭据 ID 偶尔对不上，取决于随机字节里有没有恰好出现那两个字符。
 */
final class PasskeyBytes {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private PasskeyBytes() {
    }

    static String encode(byte[] value) {
        return ENCODER.encodeToString(value);
    }

    /**
     * 解 base64url
     * <p>
     * 解不动时抛 {@link IllegalArgumentException}，由调用方翻成一句「这次登记不完整」——
     * <b>不要返回空数组</b>：空的公钥、空的签名会一路走到验签才失败，
     * 报出来的原因与真实原因（客户端传上来的根本不是 base64）毫无关系。
     * @param value base64url 串
     * @return 原始字节
     */
    static byte[] decode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少必要的字段");
        }
        return DECODER.decode(value.strip());
    }

    static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，取不到只能是运行环境被人拆过
            throw new IllegalStateException("当前运行环境没有 SHA-256", e);
        }
    }

    /**
     * 拼接两段字节
     * <p>
     * 认证器签的是「认证器数据 ‖ 客户端数据的哈希」，顺序写反的表现同样是验签一律不过。
     */
    static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
