package com.starlwr.bot.core.config.ui.auth.passkey;

import lombok.Getter;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;

/**
 * 认证器数据
 * <p>
 * 注册与登录两趟里，认证器签名覆盖的都是这一段字节。它的排布是写死的：
 * <pre>
 *   rpIdHash   32 字节   —— 认证器认为自己在为哪个域服务
 *   flags       1 字节   —— 第 0 位「人在场」、第 2 位「验过身份」、第 6 位「带着凭据」
 *   signCount   4 字节   —— 大端计数器，防重放
 *   （注册时随后是凭据数据：aaguid 16、凭据 ID 长度 2、凭据 ID、COSE 公钥）
 * </pre>
 * <p>
 * 🔴 <b>rpIdHash 是哈希不是明文</b>，因此只能拿「我方期望的 rpId 的哈希」去比，
 * 比不过就是这把钥匙不是为这个站点建的。这件事必须在这里判：跳过它的话，
 * 攻击者站点上建的一把通行密钥可以直接拿来登本站。
 */
@Getter
final class AuthenticatorData {
    private static final int RP_ID_HASH_LENGTH = 32;

    private static final int FLAGS_LENGTH = 1;

    private static final int SIGN_COUNT_LENGTH = 4;

    private static final int AAGUID_LENGTH = 16;

    private static final int CREDENTIAL_ID_LENGTH_BYTES = 2;

    /**
     * 凭据 ID 的字节数上限，取自 WebAuthn 规范
     */
    private static final int MAX_CREDENTIAL_ID_LENGTH = 1023;

    private static final int FLAG_USER_PRESENT = 0x01;

    private static final int FLAG_USER_VERIFIED = 0x04;

    private static final int FLAG_ATTESTED_CREDENTIAL_DATA = 0x40;

    private final byte[] rpIdHash;

    private final int flags;

    /**
     * 签名计数器
     * <p>
     * 用 long 存一个 32 位<b>无符号</b>数：用 int 的话，计数器过 21 亿之后会变成负数，
     * 而「单调递增」这条判断会当场反过来——正常使用的那把钥匙从此一直被拒。
     */
    private final long signCount;

    /**
     * 凭据 ID，仅注册时的认证器数据里有
     */
    private final byte[] credentialId;

    /**
     * 凭据公钥，仅注册时的认证器数据里有
     */
    private final CoseKey credentialPublicKey;

    private AuthenticatorData(byte[] rpIdHash, int flags, long signCount, byte[] credentialId, CoseKey credentialPublicKey) {
        this.rpIdHash = rpIdHash;
        this.flags = flags;
        this.signCount = signCount;
        this.credentialId = credentialId;
        this.credentialPublicKey = credentialPublicKey;
    }

    /**
     * 解析认证器数据
     * @param raw 原始字节
     * @param expectAttestedCredential 是否要求带着凭据数据（注册时要求，登录时不要求）
     * @return 解析结果
     */
    static AuthenticatorData parse(byte[] raw, boolean expectAttestedCredential) {
        int fixedLength = RP_ID_HASH_LENGTH + FLAGS_LENGTH + SIGN_COUNT_LENGTH;
        if (raw.length < fixedLength) {
            throw new IllegalArgumentException("认证器数据过短: " + raw.length + " 字节");
        }

        ByteBuffer buffer = ByteBuffer.wrap(raw);
        byte[] rpIdHash = new byte[RP_ID_HASH_LENGTH];
        buffer.get(rpIdHash);
        int flags = buffer.get() & 0xff;
        long signCount = buffer.getInt() & 0xffffffffL;

        boolean attested = (flags & FLAG_ATTESTED_CREDENTIAL_DATA) != 0;
        if (expectAttestedCredential != attested) {
            throw new IllegalArgumentException(expectAttestedCredential
                    ? "认证器数据里没有凭据信息，这一趟登记不完整"
                    : "登录用的认证器数据里不该带凭据信息");
        }

        if (!attested) {
            return new AuthenticatorData(rpIdHash, flags, signCount, null, null);
        }

        if (buffer.remaining() < AAGUID_LENGTH + CREDENTIAL_ID_LENGTH_BYTES) {
            throw new IllegalArgumentException("认证器数据里的凭据信息不完整");
        }

        buffer.position(buffer.position() + AAGUID_LENGTH);
        int credentialIdLength = buffer.getShort() & 0xffff;
        if (credentialIdLength == 0 || credentialIdLength > MAX_CREDENTIAL_ID_LENGTH || buffer.remaining() < credentialIdLength) {
            throw new IllegalArgumentException("认证器数据里的凭据 ID 长度不合法: " + credentialIdLength);
        }

        byte[] credentialId = new byte[credentialIdLength];
        buffer.get(credentialId);

        // 公钥之后可能还跟着扩展数据，因此这里不要求「剩下的全是公钥」——
        // CBOR 那一侧自己知道读到哪里为止，多出来的字节归扩展，不归我们判
        byte[] rest = new byte[buffer.remaining()];
        buffer.get(rest);
        Map<Object, Object> cose = CborReader.readLeadingMap(rest);

        return new AuthenticatorData(rpIdHash, flags, signCount, credentialId, CoseKey.parse(cose));
    }

    boolean userPresent() {
        return (flags & FLAG_USER_PRESENT) != 0;
    }

    boolean userVerified() {
        return (flags & FLAG_USER_VERIFIED) != 0;
    }

    /**
     * 认证器声称的 rpId 是不是我方期望的那个
     * <p>
     * 用 {@link MessageDigest#isEqual} 而不是 {@link Arrays#equals}：这是一次凭据比对，
     * 与令牌比对同理，不给耗时差异留口子。
     * @param expectedRpId 期望的 rpId
     * @return 是否一致
     */
    boolean matchesRpId(String expectedRpId) {
        return MessageDigest.isEqual(rpIdHash, PasskeyBytes.sha256(expectedRpId.getBytes(StandardCharsets.UTF_8)));
    }
}
