package com.starlwr.bot.adapter.onebot.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("推送接口 Token 存储")
class PushApiTokenStoreTest {
    @Test
    @DisplayName("正确的 Token 校验通过，错误的被拒绝")
    void verify() {
        PushApiTokenStore store = new PushApiTokenStore();
        store.register("/onebot/send", "s3cret-token-value-1234");

        assertTrue(store.verify("/onebot/send", "s3cret-token-value-1234"));
        assertFalse(store.verify("/onebot/send", "wrong-token"));
        assertFalse(store.verify("/onebot/send", null));
        assertFalse(store.verify("/onebot/send", ""));
    }

    @Test
    @DisplayName("Token 前缀相同但长度不足时不通过")
    void verifyRejectsPrefix() {
        PushApiTokenStore store = new PushApiTokenStore();
        store.register("/onebot/send", "abcdefghijklmnop");

        assertFalse(store.verify("/onebot/send", "abcdefghijklmno"));
        assertFalse(store.verify("/onebot/send", "abcdefghijklmnopq"));
    }

    @Test
    @DisplayName("未注册的路径不视为受保护，且校验一律不通过")
    void unregisteredPath() {
        PushApiTokenStore store = new PushApiTokenStore();
        store.register("/onebot/send", "s3cret-token-value-1234");

        assertFalse(store.isProtected("/onebot/other"));
        assertFalse(store.verify("/onebot/other", "s3cret-token-value-1234"));
        assertTrue(store.isProtected("/onebot/send"));
    }

    @Test
    @DisplayName("多个推送平台各自持有独立 Token")
    void tokensAreIsolatedPerPath() {
        PushApiTokenStore store = new PushApiTokenStore();
        store.register("/onebot/send", "token-for-sender-one-x");
        store.register("/onebot/send2", "token-for-sender-two-y");

        assertTrue(store.verify("/onebot/send", "token-for-sender-one-x"));
        assertFalse(store.verify("/onebot/send", "token-for-sender-two-y"));
        assertTrue(store.verify("/onebot/send2", "token-for-sender-two-y"));
    }

    @Test
    @DisplayName("自动生成的 Token 足够长且互不重复")
    void generate() {
        Set<String> generated = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String token = PushApiTokenStore.generate();
            assertTrue(token.length() >= PushApiTokenStore.MIN_TOKEN_LENGTH);
            assertFalse(PushApiTokenStore.isWeak(token));
            generated.add(token);
        }

        assertEquals(200, generated.size());
    }

    @Test
    @DisplayName("弱 Token 能被识别")
    void isWeak() {
        assertTrue(PushApiTokenStore.isWeak(null));
        assertTrue(PushApiTokenStore.isWeak(""));
        assertTrue(PushApiTokenStore.isWeak("short"));
        assertTrue(PushApiTokenStore.isWeak("123456789012345678"));
        assertTrue(PushApiTokenStore.isWeak("my-starbot-token-value"));
        assertTrue(PushApiTokenStore.isWeak("aaaaaaaaaaaaaaaaaaaa"));
        assertTrue(PushApiTokenStore.isWeak("ababababababababab"));

        assertFalse(PushApiTokenStore.isWeak("Xq7-Rt2_Kd9vLm4Zp0Ns"));
    }

    @Test
    @DisplayName("指纹不泄露 Token 本身且同值稳定")
    void fingerprint() {
        String token = "Xq7-Rt2_Kd9vLm4Zp0Ns";
        String fingerprint = PushApiTokenStore.fingerprint(token);

        assertEquals(fingerprint, PushApiTokenStore.fingerprint(token));
        assertNotEquals(fingerprint, PushApiTokenStore.fingerprint(token + "!"));
        assertFalse(fingerprint.contains(token));
        assertEquals("none", PushApiTokenStore.fingerprint(null));
    }
}
