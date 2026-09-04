package com.starlwr.bot.core.config.ui.auth.passkey;

import lombok.Getter;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Map;

/**
 * 认证器交上来的那把公钥
 * <p>
 * 通行密钥的公钥按 COSE_Key（RFC 8152）编码在 CBOR 字典里，键全是小整数。这里把它翻成
 * JDK 认得的 {@link PublicKey}，验签就用 {@link java.security.Signature}，<b>不引任何密码学依赖</b>。
 *
 * <h2>支持哪三种，为什么是这三种</h2>
 * <ul>
 *   <li><b>ES256</b>（{@code -7}）——绝大多数平台认证器（手机、电脑上的系统钥匙串）给的就是它</li>
 *   <li><b>RS256</b>（{@code -257}）——一部分较早的安全密钥与 Windows Hello 用它</li>
 *   <li><b>Ed25519</b>（{@code -8}）——较新的安全密钥用它</li>
 * </ul>
 * 名单之外的算法一律拒绝，<b>而不是「先存下来，验签时再说」</b>：存进去的凭据日后会被当作
 * 一把能开门的钥匙，而「这把钥匙我们其实验不了」这件事要在登记的那一刻就说出来，
 * 不能等到使用者换了台设备、只剩这一把钥匙时才发现。
 */
@Getter
final class CoseKey {
    /**
     * COSE 里的算法标识
     */
    private static final int ES256 = -7;

    private static final int EDDSA = -8;

    private static final int RS256 = -257;

    /**
     * COSE_Key 的通用键
     */
    private static final int KEY_TYPE = 1;

    private static final int ALGORITHM = 3;

    /**
     * 椭圆曲线与 OKP 两族共用的键：曲线、x、y
     */
    private static final int CURVE = -1;

    private static final int X = -2;

    private static final int Y = -3;

    /**
     * RSA 的模数与指数，与上面几个键位重合但语义不同——kty 决定该按哪一套读
     */
    private static final int MODULUS = -1;

    private static final int EXPONENT = -2;

    private static final int KEY_TYPE_OKP = 1;

    private static final int KEY_TYPE_EC2 = 2;

    private static final int KEY_TYPE_RSA = 3;

    private static final int CURVE_P256 = 1;

    private static final int CURVE_ED25519 = 6;

    /**
     * P-256 坐标的字节数。少一个字节的坐标不是「前面补零就行」，而是这份编码不合规范
     */
    private static final int P256_COORDINATE_BYTES = 32;

    private static final int ED25519_KEY_BYTES = 32;

    /**
     * COSE 算法标识，与凭据一起存下来：验签时按它挑 {@link java.security.Signature} 的算法名
     */
    private final int algorithm;

    private final PublicKey publicKey;

    private CoseKey(int algorithm, PublicKey publicKey) {
        this.algorithm = algorithm;
        this.publicKey = publicKey;
    }

    /**
     * 把 COSE_Key 字典翻成公钥
     * @param cose CBOR 解出来的字典
     * @return 公钥与算法
     */
    static CoseKey parse(Map<Object, Object> cose) {
        int keyType = (int) integer(cose, KEY_TYPE, "kty");
        int algorithm = (int) integer(cose, ALGORITHM, "alg");

        return switch (algorithm) {
            case ES256 -> {
                expect(keyType == KEY_TYPE_EC2, "ES256 的密钥类型应为 EC2");
                expect(integer(cose, CURVE, "crv") == CURVE_P256, "ES256 只接受 P-256 曲线");
                yield new CoseKey(algorithm, ecPublicKey(
                        bytes(cose, X, "x", P256_COORDINATE_BYTES),
                        bytes(cose, Y, "y", P256_COORDINATE_BYTES)));
            }
            case RS256 -> {
                expect(keyType == KEY_TYPE_RSA, "RS256 的密钥类型应为 RSA");
                yield new CoseKey(algorithm, rsaPublicKey(bytes(cose, MODULUS, "n"), bytes(cose, EXPONENT, "e")));
            }
            case EDDSA -> {
                expect(keyType == KEY_TYPE_OKP, "EdDSA 的密钥类型应为 OKP");
                expect(integer(cose, CURVE, "crv") == CURVE_ED25519, "EdDSA 只接受 Ed25519 曲线");
                yield new CoseKey(algorithm, ed25519PublicKey(bytes(cose, X, "x", ED25519_KEY_BYTES)));
            }
            default -> throw new IllegalArgumentException("不支持的通行密钥算法: " + algorithm);
        };
    }

    /**
     * 按算法标识取回 JDK 里的签名算法名
     * @param algorithm COSE 算法标识
     * @return 签名算法名
     */
    static String signatureAlgorithm(int algorithm) {
        return switch (algorithm) {
            case ES256 -> "SHA256withECDSA";
            case RS256 -> "SHA256withRSA";
            case EDDSA -> "Ed25519";
            default -> throw new IllegalArgumentException("不支持的通行密钥算法: " + algorithm);
        };
    }

    /**
     * 按算法标识取回 {@link KeyFactory} 的算法名
     * <p>
     * 存下来的是 X.509 编码的公钥，重建时要先知道该用哪一族的 {@link KeyFactory}。
     * 这个映射与 {@link #signatureAlgorithm} 分开写：一个答「用什么验签」，
     * 一个答「用什么把字节变回公钥」，合成一处会在加第四种算法时逼着人同时改两件事。
     * @param algorithm COSE 算法标识
     * @return 密钥工厂算法名
     */
    static String keyFactoryAlgorithm(int algorithm) {
        return switch (algorithm) {
            case ES256 -> "EC";
            case RS256 -> "RSA";
            case EDDSA -> "Ed25519";
            default -> throw new IllegalArgumentException("不支持的通行密钥算法: " + algorithm);
        };
    }

    /**
     * 把存下来的 X.509 公钥字节重建成公钥
     * @param algorithm COSE 算法标识
     * @param encoded X.509 编码
     * @return 公钥
     * @throws GeneralSecurityException 编码与算法对不上时抛出
     */
    static PublicKey restore(int algorithm, byte[] encoded) throws GeneralSecurityException {
        return KeyFactory.getInstance(keyFactoryAlgorithm(algorithm)).generatePublic(new X509EncodedKeySpec(encoded));
    }

    private static PublicKey ecPublicKey(byte[] x, byte[] y) {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec spec = parameters.getParameterSpec(ECParameterSpec.class);

            ECPoint point = new ECPoint(new BigInteger(1, x), new BigInteger(1, y));
            return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, spec));
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 ES256 公钥: " + e.getMessage(), e);
        }
    }

    private static PublicKey rsaPublicKey(byte[] modulus, byte[] exponent) {
        try {
            return KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(new BigInteger(1, modulus), new BigInteger(1, exponent)));
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 RS256 公钥: " + e.getMessage(), e);
        }
    }

    /**
     * 还原 Ed25519 公钥
     * <p>
     * COSE 里给的是那 32 字节的压缩编码：小端序的 y 坐标，最高位那一位是 x 的奇偶。
     * JDK 要的 {@link EdECPoint} 恰好是反过来的一对（大端 {@link BigInteger} 加一个布尔），
     * 因此这里要翻一次字节序——<b>翻错的表现不是报错，而是验签一律不过</b>，
     * 与「使用者按错了指纹」长得一模一样，所以三种算法各有一条阳性判据钉着。
     */
    private static PublicKey ed25519PublicKey(byte[] compressed) {
        try {
            byte[] littleEndian = compressed.clone();
            boolean xOdd = (littleEndian[ED25519_KEY_BYTES - 1] & 0x80) != 0;
            littleEndian[ED25519_KEY_BYTES - 1] &= 0x7f;

            byte[] bigEndian = new byte[ED25519_KEY_BYTES];
            for (int i = 0; i < ED25519_KEY_BYTES; i++) {
                bigEndian[i] = littleEndian[ED25519_KEY_BYTES - 1 - i];
            }

            EdECPoint point = new EdECPoint(xOdd, new BigInteger(1, bigEndian));
            return KeyFactory.getInstance("Ed25519")
                    .generatePublic(new EdECPublicKeySpec(NamedParameterSpec.ED25519, point));
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 Ed25519 公钥: " + e.getMessage(), e);
        }
    }

    private static long integer(Map<Object, Object> cose, int key, String name) {
        Object value = cose.get((long) key);
        if (!(value instanceof Long number)) {
            throw new IllegalArgumentException("COSE 公钥缺少整数字段 " + name);
        }
        return number;
    }

    private static byte[] bytes(Map<Object, Object> cose, int key, String name) {
        Object value = cose.get((long) key);
        if (!(value instanceof byte[] bytes) || bytes.length == 0) {
            throw new IllegalArgumentException("COSE 公钥缺少字节串字段 " + name);
        }
        return bytes;
    }

    private static byte[] bytes(Map<Object, Object> cose, int key, String name, int expectedLength) {
        byte[] value = bytes(cose, key, name);
        expect(value.length == expectedLength,
                "COSE 公钥字段 " + name + " 应为 " + expectedLength + " 字节, 实为 " + value.length);
        return value;
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
