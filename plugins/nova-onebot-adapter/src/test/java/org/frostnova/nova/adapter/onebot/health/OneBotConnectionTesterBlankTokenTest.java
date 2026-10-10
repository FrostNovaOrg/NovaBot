package org.frostnova.nova.adapter.onebot.health;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.adapter.onebot.config.OneBotAdapterPluginProperties;
import org.frostnova.nova.adapter.onebot.http.OneBotHttpAdapter;
import org.frostnova.nova.adapter.onebot.model.OneBotSender;
import org.frostnova.nova.core.account.BotConnectionTester;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试连接时 HTTP Token 留空
 * <p>
 * 连接页与初始设置都不回填 Token，提示「留空保持原值」，保存那一侧也照此办。
 * 测试那一侧若照空的发，机器人配了 Token 时必回 403，使用者只想改个端口也存不上。
 * <ul>
 *   <li>地址与已存那一台相同：用已存的 Token 去测，测的正是存下去会用的那一份</li>
 *   <li>地址不同：不代用，照空的测——测试接口常常正指向一个填错的地址，
 *       已存的凭据不能发到一台没确认过的机器上</li>
 * </ul>
 * 量的是发出去的那台临时机器人身上带的 Token。
 */
@DisplayName("测试连接时 HTTP Token 留空")
class OneBotConnectionTesterBlankTokenTest {
    private static final String SAVED_TOKEN = "fake-saved-token-not-real";

    private OneBotAdapterPluginProperties properties;

    private final List<OneBotSender> sent = new ArrayList<>();

    @BeforeEach
    void setUp() {
        properties = new OneBotAdapterPluginProperties();
        OneBotSender saved = new OneBotSender();
        saved.setOneBotAddress("10.0.0.1");
        saved.setOneBotHttpPort(3000);
        saved.setOneBotWebsocketPort(3001);
        saved.setOneBotHttpToken(SAVED_TOKEN);
        properties.getSenders().add(saved);
    }

    /** 记下每次调用带的那台机器人；answer 决定回什么或抛什么 */
    private OneBotConnectionTester tester(Function<OneBotSender, JSONObject> answer) {
        OneBotHttpAdapter http = (OneBotHttpAdapter) Proxy.newProxyInstance(
                OneBotHttpAdapter.class.getClassLoader(), new Class<?>[]{OneBotHttpAdapter.class},
                (proxy, method, args) -> {
                    OneBotSender sender = (OneBotSender) args[0];
                    sent.add(sender);
                    return answer.apply(sender);
                });
        return new OneBotConnectionTester(http, properties, null);
    }

    private static JSONObject ok(OneBotSender sender) {
        return new JSONObject().fluentPut("app_version", "1.0").fluentPut("nickname", "bot")
                .fluentPut("user_id", 1L);
    }

    /** 机器人那边配了 SAVED_TOKEN：别的一律 403 */
    private static JSONObject guarded(OneBotSender sender) {
        if (!SAVED_TOKEN.equals(sender.getOneBotHttpToken())) {
            throw HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden",
                    HttpHeaders.EMPTY, new byte[0], null);
        }
        return ok(sender);
    }

    @Test
    @DisplayName("地址相同、Token 留空：用已存的 Token 去测，只改端口也能测通")
    void sameAddressBlankTokenUsesSavedToken() {
        BotConnectionTester.Result result = tester(OneBotConnectionTesterBlankTokenTest::guarded)
                .test("10.0.0.1", 3005, "");

        assertFalse(sent.isEmpty());
        sent.forEach(s -> assertEquals(SAVED_TOKEN, s.getOneBotHttpToken()));
        assertEquals(3005, sent.get(0).getOneBotHttpPort());
        assertTrue(result.ok(), result.detail());
        assertNoSavedToken(result);
    }

    @Test
    @DisplayName("地址不同、Token 留空：不把已存的 Token 发到新地址")
    void otherAddressBlankTokenDoesNotSendSavedToken() {
        BotConnectionTester.Result result = tester(OneBotConnectionTesterBlankTokenTest::guarded)
                .test("10.0.0.2", 3000, "");

        assertFalse(sent.isEmpty());
        sent.forEach(s -> assertNotEquals(SAVED_TOKEN, s.getOneBotHttpToken()));
        assertFalse(result.ok());
        assertNoSavedToken(result);
    }

    @Test
    @DisplayName("地址不同、Token 留空且被拒：建议写明留空不会沿用已存的 Token")
    void otherAddressForbiddenAdviceExplainsBlankToken() {
        BotConnectionTester.Result result = tester(OneBotConnectionTesterBlankTokenTest::guarded)
                .test("10.0.0.2", 3000, "");

        assertEquals("Token 校验未通过", result.detail());
        assertTrue(result.advice().contains("地址换了"), result.advice());
        assertTrue(result.advice().contains("请把 HTTP Token 填上再测"), result.advice());
        assertNoSavedToken(result);
    }

    @Test
    @DisplayName("填了 Token 就用填的那一份")
    void typedTokenIsUsedAsIs() {
        tester(OneBotConnectionTesterBlankTokenTest::ok).test("10.0.0.1", 3000, "fake-typed-token");

        sent.forEach(s -> assertEquals("fake-typed-token", s.getOneBotHttpToken()));
    }

    @Test
    @DisplayName("还没存过任何一台：留空就是空")
    void nothingSavedBlankStaysBlank() {
        properties.getSenders().clear();
        tester(OneBotConnectionTesterBlankTokenTest::ok).test("10.0.0.1", 3000, "");

        assertFalse(sent.isEmpty());
        sent.forEach(s -> assertTrue(s.getOneBotHttpToken() == null || s.getOneBotHttpToken().isEmpty()));
    }

    private static void assertNoSavedToken(BotConnectionTester.Result result) {
        assertFalse(String.valueOf(result.detail()).contains(SAVED_TOKEN));
        assertFalse(String.valueOf(result.advice()).contains(SAVED_TOKEN));
    }
}
