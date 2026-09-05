package com.starlwr.bot.adapter.onebot.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
    @DisplayName("坏百分号编码解析不了时 isProtected 静默答 false，不向调用方抛异常")
    void isProtectedSwallowsMalformedEncoding() {
        PushApiTokenStore store = new PushApiTokenStore();
        store.register("/onebot/send", "s3cret-token-value-1234");

        assertFalse(store.isProtected("/onebot/send%zz"));
        assertFalse(store.isProtected("/onebot/send%2"));
    }

    @Test
    @DisplayName("互含的登记模式同时命中时答最具体的那条，答案不随登记顺序漂")
    void resolvePrefersMostSpecificAmongOverlappingPatterns() {
        // 每组 {更具体者, 更宽者, 双命中的请求路径}。存储侧按登记先后保序迭代:
        // 若实现退化成「取首个命中」, 先登宽者的那份必答宽者——任何 JDK 下都必红, 不靠哈希迭代序的运气。
        // 断言逐条独立执行 (assertAll): 一组红了不遮蔽其余组, 突变下红几条就读得出几条
        String[][] cases = {
                {"/onebot/send", "/onebot/send/**", "/onebot/send"},
                {"/onebot/{a}", "/onebot/**", "/onebot/send"},
                {"/onebot/send/*", "/onebot/send/**", "/onebot/send/x"},
                {"/onebot/send/**", "/**", "/onebot/send/x"},
                {"/onebot/{id}/send", "/onebot/*/send", "/onebot/42/send"},
        };
        List<Executable> assertions = new ArrayList<>();
        for (String[] c : cases) {
            PushApiTokenStore specificFirst = new PushApiTokenStore();
            specificFirst.register(c[0], "token-AAAAAAAA-1");
            specificFirst.register(c[1], "token-BBBBBBBB-2");
            PushApiTokenStore wideFirst = new PushApiTokenStore();
            wideFirst.register(c[1], "token-BBBBBBBB-2");
            wideFirst.register(c[0], "token-AAAAAAAA-1");

            assertions.add(() -> assertEquals(c[0], specificFirst.resolve(c[2], ""),
                    c[0] + " + " + c[1] + ", 先登具体者: "));
            assertions.add(() -> assertEquals(c[0], wideFirst.resolve(c[2], ""),
                    c[0] + " + " + c[1] + ", 先登宽者: "));
        }
        assertAll("互含模式须答最具体者", assertions);
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
