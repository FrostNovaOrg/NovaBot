package org.frostnova.nova.adapter.onebot.health;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.adapter.onebot.config.OneBotAdapterPluginProperties;
import org.frostnova.nova.adapter.onebot.exception.OneBotApiException;
import org.frostnova.nova.adapter.onebot.http.OneBotHttpAdapter;
import org.frostnova.nova.adapter.onebot.model.OneBotSender;
import org.frostnova.nova.core.account.BotConnectionTester;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试连接的失败提示写明是哪种故障
 * <p>
 * 这几种失败从前都落进「连接测试失败：英文类名」那一句，使用者看不出该去改哪里：
 * <ul>
 *   <li>OneBot 11 规定没带 access_token 回 401——实现配了 Token 而页面留空时就是它；</li>
 *   <li>有的实现不分 401 和 403，送来的 Token 不对时也回 401；</li>
 *   <li>实现回包 retcode 非 0 抛 {@link OneBotApiException}，
 *       它自带的 getMessage 把请求参数整个拼进去，实现给的说明也可能原样带着 Token；</li>
 *   <li>实现自己回 5xx。</li>
 * </ul>
 * 断言失败消息只引用 detail 与 advice 的内容，不引用异常本身。
 */
@DisplayName("测试连接的失败提示写明是哪种故障")
class OneBotConnectionTesterFailureAdviceTest {
    private static final String SAVED_ADDRESS = "127.0.0.1";

    private static final String SAVED_TOKEN = "fake-saved-token-4d7mz";

    private OneBotAdapterPluginProperties properties;

    private final List<OneBotSender> sent = new ArrayList<>();

    @BeforeEach
    void setUp() {
        properties = new OneBotAdapterPluginProperties();
    }

    /** 存下一台已配置的机器人，测试连接的「留空沿用已存」以它为准 */
    private void saveConfigured() {
        OneBotSender saved = new OneBotSender();
        saved.setOneBotAddress(SAVED_ADDRESS);
        saved.setOneBotHttpPort(3000);
        saved.setOneBotWebsocketPort(3001);
        saved.setOneBotHttpToken(SAVED_TOKEN);
        properties.getSenders().add(saved);
    }

    /** 每次调用都抛同一个异常：这几格只关心异常的类型，不关心调了几次 */
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

    private static Function<OneBotSender, JSONObject> throwing(RuntimeException e) {
        return sender -> {
            throw e;
        };
    }

    @Test
    @DisplayName("实现要求 Token 而页面留空（回 401）：说明要求 Token、这边没填")
    void unauthorizedWithBlankTokenSaysTokenRequired() {
        // 用户故障：实现那边配了 HTTP Token，页面上没填，从前只见「Unauthorized」
        HttpClientErrorException unauthorized = HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY, new byte[0], null);

        BotConnectionTester.Result result = tester(throwing(unauthorized))
                .test(SAVED_ADDRESS, 3000, "");

        assertFalse(result.ok());
        assertTrue(result.detail().contains("要求 HTTP Token"), result.detail());
        assertTrue(result.detail().contains("没有填"), result.detail());
        assertTrue(result.advice().contains("填上再测"), result.advice());
    }

    @Test
    @DisplayName("送了 Token 实现仍回 401：照 Token 不一致来说，不再只回类名")
    void unauthorizedWithTypedTokenSaysTokenMismatch() {
        // 用户故障：两侧 Token 不一致，有的实现不分 401 和 403，从前只见「Unauthorized」
        HttpClientErrorException unauthorized = HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY, new byte[0], null);

        BotConnectionTester.Result result = tester(throwing(unauthorized))
                .test(SAVED_ADDRESS, 3000, "fake-typed-token");

        assertFalse(result.ok());
        assertEquals("Token 校验未通过", result.detail());
        assertTrue(result.advice().contains("不一致"), result.advice());
    }

    @Test
    @DisplayName("地址换了、Token 留空又回 401：仍照「留空不沿用已存的」那句")
    void unauthorizedWithheldTokenKeepsWithheldAdvice() {
        // 用户故障：换了地址又把 Token 留空，已存的凭据不发去没确认过的机器，要被告知重新填
        saveConfigured();
        HttpClientErrorException unauthorized = HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY, new byte[0], null);

        BotConnectionTester.Result result = tester(throwing(unauthorized))
                .test("10.0.0.9", 3000, "");

        assertFalse(result.ok());
        assertEquals("Token 校验未通过", result.detail());
        assertTrue(result.advice().contains("地址换了"), result.advice());
        assertTrue(result.advice().contains("请把 HTTP Token 填上再测"), result.advice());
    }

    @Test
    @DisplayName("同一地址、Token 留空沿用已存又回 401：说 Token 不一致，不说这边没有填")
    void unauthorizedWithInheritedTokenSaysMismatchNotMissing() {
        // 用户故障：Token 明明存着（留空沿用已存的），却被提示这边没有填
        saveConfigured();
        HttpClientErrorException unauthorized = HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY, new byte[0], null);

        BotConnectionTester.Result result = tester(throwing(unauthorized))
                .test(SAVED_ADDRESS, 3000, "");

        assertFalse(result.ok());
        assertEquals("Token 校验未通过", result.detail());
        assertTrue(result.advice().contains("不一致"), result.advice());
        assertFalse(result.detail().contains("没有填"), result.detail());
        assertFalse(result.advice().contains("没有填"), result.advice());
    }

    @Test
    @DisplayName("实现回包 retcode 非 0：写明错误码与说明，Token 与请求参数都不进回包")
    void apiExceptionReportsCodeWithoutEchoingTokenOrParams() {
        // 用户故障：实现回包 retcode 非 0，从前只见「OneBotApiException」，说明里还可能带着 Token
        String typedToken = "fake-typed-token-9f3kxq";
        JSONObject params = new JSONObject().fluentPut("no_such_group", "fake-param-should-not-appear");
        // 实现给的说明里原样带着送过去的 Token：拼进回包就是把它显示在页面上
        OneBotApiException apiError = new OneBotApiException("/get_group_member_info", params,
                2333, "账号已掉线，token=" + typedToken + "，请重新登录");

        BotConnectionTester.Result result = tester(throwing(apiError))
                .test(SAVED_ADDRESS, 3000, typedToken);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("错误码 2333"), result.detail());
        // 说明保留（遮盖不是删掉），实现给的提示对排障有用
        assertTrue(result.detail().contains("账号已掉线"), result.detail());
        assertTrue(result.detail().contains("******"), result.detail());
        assertTrue(result.advice().contains("错误说明"), result.advice());
        assertTrue(result.advice().contains("日志"), result.advice());
        assertFalse(result.advice().contains("登录状态"), result.advice());
        assertNothingEchoed(result, typedToken, "fake-param-should-not-appear");
    }

    @Test
    @DisplayName("实现给的说明为空：只写错误码，不落一个孤零零的冒号")
    void apiExceptionWithoutMsgReportsCodeOnly() {
        // 用户故障：实现只给错误码不给说明，提示不能落一个孤零零的冒号
        OneBotApiException apiError = new OneBotApiException("/get_version_info", new JSONObject(),
                1200, "  ");

        BotConnectionTester.Result result = tester(throwing(apiError))
                .test(SAVED_ADDRESS, 3000, "fake-typed-token");

        assertFalse(result.ok());
        assertTrue(result.detail().contains("错误码 1200"), result.detail());
        assertFalse(result.detail().contains("："), result.detail());
    }

    @Test
    @DisplayName("实现自己回 5xx：说明实现内部出错、带上 HTTP 状态码")
    void serverErrorSaysImplementationItselfBroke() {
        // 用户故障：实现自己内部出错（5xx），从前只见「InternalServerError」
        HttpServerErrorException serverError = HttpServerErrorException.create(
                HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", HttpHeaders.EMPTY, new byte[0], null);

        BotConnectionTester.Result result = tester(throwing(serverError))
                .test(SAVED_ADDRESS, 3000, "");

        assertFalse(result.ok());
        assertTrue(result.detail().contains("内部出错"), result.detail());
        assertTrue(result.detail().contains("500"), result.detail());
        assertTrue(result.advice().contains("日志"), result.advice());
        assertTrue(result.advice().contains("重启"), result.advice());
    }

    @Test
    @DisplayName("连不上：提一句 NapCat 停在扫码页时也不开端口")
    void unreachableAdviceMentionsNapCatQrWait() {
        // 用户故障：实现没开端口——用 NapCat 时停在扫码页也不开，看着像地址填错
        ResourceAccessException unreachable = new ResourceAccessException("Connection refused");

        BotConnectionTester.Result result = tester(throwing(unreachable))
                .test(SAVED_ADDRESS, 3000, "");

        assertFalse(result.ok());
        assertTrue(result.detail().contains("无法建立连接"), result.detail());
        assertTrue(result.advice().contains("NapCat"), result.advice());
        assertTrue(result.advice().contains("扫码"), result.advice());
    }

    /** Token 与请求参数的字样都不许出现在回包里；失败消息只给 detail 与 advice 本身 */
    private static void assertNothingEchoed(BotConnectionTester.Result result, String... secrets) {
        for (String secret : secrets) {
            assertFalse(result.detail().contains(secret), "detail 含不该出现的字样，长度 " + secret.length());
            assertFalse(result.advice().contains(secret), "advice 含不该出现的字样，长度 " + secret.length());
        }
    }
}
