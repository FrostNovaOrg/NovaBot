package org.frostnova.nova.adapter.qqbot;

import com.alibaba.fastjson2.JSON;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

/** 识别回包中反射的 AppSecret，调用接口时无需再次解密。 */
final class QqBotSecretFingerprint {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DigestMatcher plain;
    private final DigestMatcher escaped;

    QqBotSecretFingerprint(String secret) {
        plain = new DigestMatcher(secret);
        String json = jsonStringContents(secret);
        escaped = json.equals(secret) ? plain : new DigestMatcher(json);
    }

    boolean appearsIn(String text) {
        if (text.length() < plain.length) {
            return false;
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try {
            return plain.matches(bytes) || (escaped != plain && escaped.matches(bytes));
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    static String jsonStringContents(String value) {
        String json = JSON.toJSONString(value);
        return json.substring(1, json.length() - 1);
    }

    private static final class DigestMatcher {
        private final byte[] salt = new byte[16];
        private final byte[] fingerprint;
        private final int length;
        private final int hash;
        private final int leadingFactor;

        DigestMatcher(String value) {
            RANDOM.nextBytes(salt);
            byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
            try {
                length = bytes.length;
                fingerprint = digest(newDigest(), bytes, 0);
                // 短值只保留带盐摘要。长值用滚动哈希减少需要算摘要的位置。
                hash = length > 8 ? hash(bytes, length) : 0;
                int factor = 1;
                for (int i = 1; i < length; i++) {
                    factor *= 31;
                }
                leadingFactor = factor;
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        }

        boolean matches(byte[] bytes) {
            int last = bytes.length - length;
            if (last < 0) {
                return false;
            }
            boolean prefilter = length > 8;
            int candidate = prefilter ? hash(bytes, length) : 0;
            MessageDigest verifier = null;
            for (int offset = 0; offset <= last; offset++) {
                if (candidate == hash) {
                    if (verifier == null) {
                        verifier = newDigest();
                    }
                    if (MessageDigest.isEqual(fingerprint, digest(verifier, bytes, offset))) {
                        return true;
                    }
                }
                if (prefilter && offset < last) {
                    candidate = (candidate - bytes[offset] * leadingFactor) * 31 + bytes[offset + length];
                }
            }
            return false;
        }

        private static int hash(byte[] bytes, int length) {
            int value = 0;
            for (int i = 0; i < length; i++) {
                value = value * 31 + bytes[i];
            }
            return value;
        }

        private byte[] digest(MessageDigest digest, byte[] bytes, int offset) {
            digest.update(salt);
            digest.update(bytes, offset, length);
            return digest.digest();
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JRE 不支持 SHA-256");
        }
    }
}
