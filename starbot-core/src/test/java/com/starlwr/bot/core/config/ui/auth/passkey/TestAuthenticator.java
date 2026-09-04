package com.starlwr.bot.core.config.ui.auth.passkey;

import com.alibaba.fastjson2.JSONObject;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 判据用的认证器：拿 JDK 自己造一把「手机上的通行密钥」
 * <p>
 * 它按 WebAuthn 规范生成注册与登录两趟的字节，好让判据能在没有真实设备的情况下走完整条路。
 * <p>
 * 🔴 <b>公钥的字节不从被测那一侧的解析逻辑倒推</b>。Ed25519 那 32 字节是小端序、还带一位奇偶标志，
 * 若这里也照被测的写法翻一遍字节序，两边错在一处就会互相抵消——判据全绿而真实设备一把也登不上。
 * 因此这里取的是 {@link PublicKey#getEncoded()} 里那段由 JDK 自己编码的原始密钥，
 * 与被测那一侧毫无来往；被测若把字节序弄反，还原出来的公钥就不是这一把，验签当场不过。
 */
final class TestAuthenticator {
    static final int ES256 = -7;

    static final int EDDSA = -8;

    static final int RS256 = -257;

    /**
     * 认证器数据里的标志位：人在场、验过身份、带着凭据
     */
    static final int FLAG_USER_PRESENT = 0x01;

    static final int FLAG_USER_VERIFIED = 0x04;

    static final int FLAG_BACKUP_ELIGIBLE = 0x08;

    static final int FLAG_BACKUP_STATE = 0x10;

    static final int FLAG_ATTESTED = 0x40;

    static final int FLAG_EXTENSION_DATA = 0x80;

    private static final int P256_COORDINATE_BYTES = 32;

    private static final int ED25519_KEY_BYTES = 32;

    private final int algorithm;

    private final KeyPair keyPair;

    private final byte[] credentialId;

    TestAuthenticator(int algorithm) {
        this.algorithm = algorithm;
        this.keyPair = generate(algorithm);
        this.credentialId = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, (byte) algorithm};
    }

    String credentialId() {
        return base64Url(credentialId);
    }

    /**
     * 造一份注册响应
     * @param challenge 服务端刚发的挑战
     * @param origin 写进客户端数据的来源地址
     * @param rpId 认证器认为自己在为哪个域服务
     * @param name 使用者给这把钥匙起的名字
     * @param signCount 初始计数器
     */
    JSONObject register(String challenge, String origin, String rpId, String name, long signCount) {
        return register(challenge, origin, rpId, name, signCount, "none");
    }

    /**
     * 造一份注册响应，并指定证明形式
     * @param format 证明形式。只有 {@code none} 该被收下，别的形式要去验一条证书链
     */
    JSONObject register(String challenge, String origin, String rpId, String name, long signCount, String format) {
        byte[] clientData = clientDataJson("webauthn.create", challenge, origin);
        byte[] authData = authenticatorData(rpId, FLAG_USER_PRESENT | FLAG_USER_VERIFIED | FLAG_ATTESTED, signCount, true);

        Map<Object, Object> attestation = new LinkedHashMap<>();
        attestation.put("fmt", format);
        attestation.put("attStmt", new LinkedHashMap<>());
        attestation.put("authData", authData);

        JSONObject response = new JSONObject();
        response.put("clientDataJSON", base64Url(clientData));
        response.put("attestationObject", base64Url(Cbor.map(attestation)));

        JSONObject body = new JSONObject();
        body.put("id", credentialId());
        body.put("name", name);
        body.put("response", response);
        return body;
    }

    /**
     * 造一份登录响应
     * @param challenge 服务端刚发的挑战
     * @param origin 写进客户端数据的来源地址
     * @param rpId 认证器认为自己在为哪个域服务
     * @param signCount 这一次报的计数器
     */
    JSONObject assertion(String challenge, String origin, String rpId, long signCount) {
        return assertion(challenge, origin, rpId, signCount, FLAG_USER_PRESENT | FLAG_USER_VERIFIED);
    }

    /**
     * 造一份登录响应，并指定认证器写下的标志位
     * @param flags 标志位。抹掉「人在场」那一位，就是一次没人按过的静默调用
     */
    JSONObject assertion(String challenge, String origin, String rpId, long signCount, int flags) {
        byte[] clientData = clientDataJson("webauthn.get", challenge, origin);
        byte[] authData = authenticatorData(rpId, flags, signCount, false);

        byte[] signed = new byte[authData.length + 32];
        System.arraycopy(authData, 0, signed, 0, authData.length);
        System.arraycopy(sha256(clientData), 0, signed, authData.length, 32);

        JSONObject response = new JSONObject();
        response.put("clientDataJSON", base64Url(clientData));
        response.put("authenticatorData", base64Url(authData));
        response.put("signature", base64Url(sign(signed)));

        JSONObject body = new JSONObject();
        body.put("id", credentialId());
        body.put("response", response);
        return body;
    }

    private byte[] sign(byte[] data) {
        try {
            Signature signature = Signature.getInstance(switch (algorithm) {
                case ES256 -> "SHA256withECDSA";
                case RS256 -> "SHA256withRSA";
                default -> "Ed25519";
            });
            signature.initSign(keyPair.getPrivate());
            signature.update(data);
            return signature.sign();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] clientDataJson(String type, String challenge, String origin) {
        JSONObject clientData = new JSONObject();
        clientData.put("type", type);
        clientData.put("challenge", challenge);
        clientData.put("origin", origin);
        clientData.put("crossOrigin", false);
        return clientData.toJSONString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] authenticatorData(String rpId, int flags, long signCount, boolean attested) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(sha256(rpId.getBytes(StandardCharsets.UTF_8)));
        out.write(flags);
        out.write((int) (signCount >> 24) & 0xff);
        out.write((int) (signCount >> 16) & 0xff);
        out.write((int) (signCount >> 8) & 0xff);
        out.write((int) signCount & 0xff);

        if (attested) {
            out.writeBytes(new byte[16]);
            out.write((credentialId.length >> 8) & 0xff);
            out.write(credentialId.length & 0xff);
            out.writeBytes(credentialId);
            out.writeBytes(cosePublicKey());
        }

        return out.toByteArray();
    }

    /**
     * 造一份认证器数据，给结构校验用
     */
    byte[] rawAuthenticatorData(String rpId, int flags, long signCount, boolean attested) {
        return authenticatorData(rpId, flags, signCount, attested);
    }

    /**
     * 在认证器数据末尾接上多余字节
     */
    static byte[] withTrailing(byte[] data, byte extra) {
        byte[] out = java.util.Arrays.copyOf(data, data.length + 1);
        out[data.length] = extra;
        return out;
    }

    /**
     * 造一份带着凭据的认证器数据，并指定 COSE 算法字段的整数值
     * <p>
     * 给「整数超出 int 范围」那一格用：其余字段仍是一把合法的 ES256 公钥，
     * 只把算法标识换成一个截断后恰好等于 ES256 的超大整数。
     */
    byte[] attestedWithCoseAlg(String rpId, int flags, long signCount, long alg) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(sha256(rpId.getBytes(StandardCharsets.UTF_8)));
        out.write(flags);
        out.write((int) (signCount >> 24) & 0xff);
        out.write((int) (signCount >> 16) & 0xff);
        out.write((int) (signCount >> 8) & 0xff);
        out.write((int) signCount & 0xff);
        out.writeBytes(new byte[16]);
        out.write((credentialId.length >> 8) & 0xff);
        out.write(credentialId.length & 0xff);
        out.writeBytes(credentialId);
        out.writeBytes(cosePublicKey(alg));
        return out.toByteArray();
    }

    /**
     * 把公钥编成 COSE_Key
     */
    private byte[] cosePublicKey() {
        return cosePublicKey(algorithm);
    }

    private byte[] cosePublicKey(long alg) {
        Map<Object, Object> cose = new LinkedHashMap<>();

        switch (algorithm) {
            case ES256 -> {
                ECPublicKey key = (ECPublicKey) keyPair.getPublic();
                cose.put(1L, 2L);
                cose.put(3L, alg);
                cose.put(-1L, 1L);
                cose.put(-2L, fixed(key.getW().getAffineX(), P256_COORDINATE_BYTES));
                cose.put(-3L, fixed(key.getW().getAffineY(), P256_COORDINATE_BYTES));
            }
            case RS256 -> {
                RSAPublicKey key = (RSAPublicKey) keyPair.getPublic();
                cose.put(1L, 3L);
                cose.put(3L, alg);
                cose.put(-1L, unsigned(key.getModulus()));
                cose.put(-2L, unsigned(key.getPublicExponent()));
            }
            default -> {
                cose.put(1L, 1L);
                cose.put(3L, alg);
                cose.put(-1L, 6L);
                cose.put(-2L, rawEd25519(keyPair.getPublic()));
            }
        }

        return Cbor.map(cose);
    }

    /**
     * 取 Ed25519 公钥那 32 个原始字节
     * <p>
     * X.509 编码（SubjectPublicKeyInfo）的末尾就是它，由 JDK 自己编出来——
     * 这正是不去自己拼小端序与奇偶位的原因，见类注释那一段。
     */
    private static byte[] rawEd25519(PublicKey key) {
        byte[] encoded = key.getEncoded();
        byte[] raw = new byte[ED25519_KEY_BYTES];
        System.arraycopy(encoded, encoded.length - ED25519_KEY_BYTES, raw, 0, ED25519_KEY_BYTES);
        return raw;
    }

    private static KeyPair generate(int algorithm) {
        try {
            return switch (algorithm) {
                case ES256 -> {
                    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
                    generator.initialize(new ECGenParameterSpec("secp256r1"));
                    yield generator.generateKeyPair();
                }
                case RS256 -> {
                    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                    generator.initialize(2048);
                    yield generator.generateKeyPair();
                }
                default -> KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            };
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] fixed(BigInteger value, int length) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[length];
        if (raw.length >= length) {
            System.arraycopy(raw, raw.length - length, out, 0, length);
        } else {
            System.arraycopy(raw, 0, out, length - raw.length, raw.length);
        }
        return out;
    }

    private static byte[] unsigned(BigInteger value) {
        byte[] raw = value.toByteArray();
        if (raw.length > 1 && raw[0] == 0) {
            byte[] out = new byte[raw.length - 1];
            System.arraycopy(raw, 1, out, 0, out.length);
            return out;
        }
        return raw;
    }

    static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    /**
     * 判据自备的 CBOR 编码器
     * <p>
     * 只写用得上的四种类型。<b>刻意不复用被测那一侧的解码器来自检</b>：
     * 编解码同出一源时，两边错在同一处会互相抵消，而那种判据全绿的时候什么也没证明。
     */
    static final class Cbor {
        private Cbor() {
        }

        static byte[] map(Map<Object, Object> entries) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            header(out, 5, entries.size());
            entries.forEach((key, value) -> {
                write(out, key);
                write(out, value);
            });
            return out.toByteArray();
        }

        private static void write(ByteArrayOutputStream out, Object value) {
            if (value instanceof Long number) {
                if (number >= 0) {
                    header(out, 0, number);
                } else {
                    header(out, 1, -1 - number);
                }
            } else if (value instanceof byte[] bytes) {
                header(out, 2, bytes.length);
                out.writeBytes(bytes);
            } else if (value instanceof String text) {
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                header(out, 3, bytes.length);
                out.writeBytes(bytes);
            } else if (value instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                Map<Object, Object> typed = (Map<Object, Object>) nested;
                out.writeBytes(map(typed));
            } else {
                throw new IllegalArgumentException("判据用的 CBOR 编码器不支持: " + value);
            }
        }

        private static void header(ByteArrayOutputStream out, int major, long value) {
            if (value < 24) {
                out.write((major << 5) | (int) value);
            } else if (value < 256) {
                out.write((major << 5) | 24);
                out.write((int) value);
            } else if (value < 65536) {
                out.write((major << 5) | 25);
                out.write((int) (value >> 8) & 0xff);
                out.write((int) value & 0xff);
            } else {
                out.write((major << 5) | 26);
                out.write((int) (value >> 24) & 0xff);
                out.write((int) (value >> 16) & 0xff);
                out.write((int) (value >> 8) & 0xff);
                out.write((int) value & 0xff);
            }
        }
    }
}
