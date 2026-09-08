package com.starlwr.bot.core.config.ui.auth.passkey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 认证器数据的结构校验：不该收下的字节必须当场拒
 * <p>
 * 负例语料都在 {@link Corpus}。每一条都是「现在不该过」——少了这一面，
 * 一个什么都收下的解析器也能让既有阳性判据全绿。
 */
@DisplayName("认证器数据结构校验")
class AuthenticatorDataRejectionTest {
    @Test
    @DisplayName("备份中却标成不能备份，拒收")
    void rejectsBackupStateWithoutEligibility() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AuthenticatorData.parse(Corpus.backupStateWithoutEligibility(), false));
        assertTrue(error.getMessage().contains("备份"),
                () -> "应当点出备份标志不合法, 实际: " + error.getMessage());
    }

    @Test
    @DisplayName("登录用的认证器数据在旗标、计数器和扩展之后还有多余字节，拒收")
    void rejectsTrailingBytesOnAssertion() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AuthenticatorData.parse(Corpus.assertionWithTrailingByte(), false));
        assertTrue(error.getMessage().contains("多余"),
                () -> "应当点出多余字节, 实际: " + error.getMessage());
    }

    @Test
    @DisplayName("登记用的认证器数据在公钥之后还有未按结构读完的字节，拒收")
    void rejectsTrailingBytesAfterPublicKeyOnAttestation() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AuthenticatorData.parse(Corpus.attestationWithTrailingByte(), true));
        assertTrue(error.getMessage().contains("多余"),
                () -> "应当点出多余字节, 实际: " + error.getMessage());
    }

    @Test
    @DisplayName("COSE 整数超出 int 范围，即使截断后碰巧是合法算法，也拒收")
    void rejectsCoseIntegerOutsideIntRange() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AuthenticatorData.parse(Corpus.coseAlgTruncatesToEs256(), true));
        assertTrue(error.getMessage().contains("范围"),
                () -> "应当点出超出范围, 实际: " + error.getMessage());
    }

    @Test
    @DisplayName("对照：备份资格与备份中同时标上，登录数据照收")
    void acceptsBackupEligibleAndBackedUp() {
        assertDoesNotThrow(() -> AuthenticatorData.parse(Corpus.backupEligibleAndBackedUp(), false));
    }

    @Test
    @DisplayName("登录用的认证器数据带着扩展字典，照收")
    void acceptsAssertionWithExtensionMap() {
        AuthenticatorData parsed = assertDoesNotThrow(
                () -> AuthenticatorData.parse(Corpus.assertionWithExtensionMap(), false));
        assertEquals(1L, parsed.getSignCount());
    }

    @Test
    @DisplayName("登录用的认证器数据在扩展字典之后还有多余字节，拒收")
    void rejectsTrailingBytesAfterExtensionMap() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AuthenticatorData.parse(Corpus.assertionWithExtensionMapAndTrailingByte(), false));
        assertTrue(error.getMessage().contains("多余"),
                () -> "应当点出多余字节, 实际: " + error.getMessage());
    }

    /**
     * 负例语料。构造不走被测解析器，只借用判据用的认证器去编合法底座，再改一处。
     */
    static final class Corpus {
        private static final String RP_ID = PasskeyTestSupport.RP_ID;

        /**
         * 截断成 int 之后恰好等于 ES256（-7）的无符号 32 位数：0xFFFFFFF9
         */
        static final long ALG_THAT_TRUNCATES_TO_ES256 = 4294967289L;

        static byte[] backupStateWithoutEligibility() {
            TestAuthenticator authenticator = new TestAuthenticator(TestAuthenticator.ES256);
            int flags = TestAuthenticator.FLAG_USER_PRESENT | TestAuthenticator.FLAG_BACKUP_STATE;
            return authenticator.rawAuthenticatorData(RP_ID, flags, 1, false);
        }

        static byte[] backupEligibleAndBackedUp() {
            TestAuthenticator authenticator = new TestAuthenticator(TestAuthenticator.ES256);
            int flags = TestAuthenticator.FLAG_USER_PRESENT
                    | TestAuthenticator.FLAG_BACKUP_ELIGIBLE
                    | TestAuthenticator.FLAG_BACKUP_STATE;
            return authenticator.rawAuthenticatorData(RP_ID, flags, 1, false);
        }

        static byte[] assertionWithTrailingByte() {
            TestAuthenticator authenticator = new TestAuthenticator(TestAuthenticator.ES256);
            int flags = TestAuthenticator.FLAG_USER_PRESENT | TestAuthenticator.FLAG_USER_VERIFIED;
            byte[] wellFormed = authenticator.rawAuthenticatorData(RP_ID, flags, 8, false);
            return TestAuthenticator.withTrailing(wellFormed, (byte) 0x00);
        }

        static byte[] attestationWithTrailingByte() {
            TestAuthenticator authenticator = new TestAuthenticator(TestAuthenticator.ES256);
            int flags = TestAuthenticator.FLAG_USER_PRESENT
                    | TestAuthenticator.FLAG_USER_VERIFIED
                    | TestAuthenticator.FLAG_ATTESTED;
            byte[] wellFormed = authenticator.rawAuthenticatorData(RP_ID, flags, 5, true);
            return TestAuthenticator.withTrailing(wellFormed, (byte) 0x00);
        }

        static byte[] coseAlgTruncatesToEs256() {
            TestAuthenticator authenticator = new TestAuthenticator(TestAuthenticator.ES256);
            int flags = TestAuthenticator.FLAG_USER_PRESENT
                    | TestAuthenticator.FLAG_USER_VERIFIED
                    | TestAuthenticator.FLAG_ATTESTED;
            return authenticator.attestedWithCoseAlg(RP_ID, flags, 5, ALG_THAT_TRUNCATES_TO_ES256);
        }

        static byte[] assertionWithExtensionMap() {
            TestAuthenticator authenticator = new TestAuthenticator(TestAuthenticator.ES256);
            int flags = TestAuthenticator.FLAG_USER_PRESENT | TestAuthenticator.FLAG_EXTENSION_DATA;
            byte[] wellFormed = authenticator.rawAuthenticatorData(RP_ID, flags, 1, false);
            Map<Object, Object> extensions = new LinkedHashMap<>();
            extensions.put(1L, 2L);
            byte[] encoded = TestAuthenticator.Cbor.map(extensions);
            byte[] out = java.util.Arrays.copyOf(wellFormed, wellFormed.length + encoded.length);
            System.arraycopy(encoded, 0, out, wellFormed.length, encoded.length);
            return out;
        }

        static byte[] assertionWithExtensionMapAndTrailingByte() {
            return TestAuthenticator.withTrailing(assertionWithExtensionMap(), (byte) 0x00);
        }
    }
}
