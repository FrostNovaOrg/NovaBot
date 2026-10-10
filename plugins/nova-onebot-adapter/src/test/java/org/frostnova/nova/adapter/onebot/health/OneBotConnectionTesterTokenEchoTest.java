package org.frostnova.nova.adapter.onebot.health;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.adapter.onebot.config.OneBotAdapterPluginProperties;
import org.frostnova.nova.adapter.onebot.http.OneBotHttpAdapter;
import org.frostnova.nova.adapter.onebot.model.OneBotSender;
import org.frostnova.nova.core.account.BotConnectionTester;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 测试连接不回显 HTTP Token
 * <p>
 * 已存的 Token 可能经配置文件或安全模式编辑器写进了请求头放不下的字符（换行、中文等）。
 * 留空沿用它去测时，HTTP 客户端建请求就会拒收，异常消息原样带着整段请求头；
 * 这段消息若拼进回包或日志，已存的 Token 就显示在页面上、落进日志里。
 * 用户自己敲的 Token 同理。
 * <p>
 * 桩照 JDK HttpClient 的做法：头值里有放不下的字符就抛 IllegalArgumentException，
 * 消息带整段头值。断言失败时只打 Token 的长度，不打内容。
 */
@DisplayName("测试连接不回显 HTTP Token")
class OneBotConnectionTesterTokenEchoTest {
    private static final String SAVED_ADDRESS = "127.0.0.1";

    private static final String GOOD_SAVED_TOKEN = "fake-saved-x7q3z9w";

    private OneBotAdapterPluginProperties properties;

    private final List<OneBotSender> sent = new ArrayList<>();

    private ch.qos.logback.classic.Logger logger;

    private Level previousLevel;

    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        properties = new OneBotAdapterPluginProperties();
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(OneBotConnectionTester.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        logger.setLevel(previousLevel);
    }

    private void save(String token) {
        OneBotSender saved = new OneBotSender();
        saved.setOneBotAddress(SAVED_ADDRESS);
        saved.setOneBotHttpPort(3000);
        saved.setOneBotWebsocketPort(3001);
        saved.setOneBotHttpToken(token);
        properties.getSenders().add(saved);
    }

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

    /** 照 JDK HttpClient 建请求时的头值校验：不收就抛，消息带整段头值 */
    private static JSONObject jdkLike(OneBotSender sender) {
        String value = "Bearer " + sender.getOneBotHttpToken();
        for (char c : value.toCharArray()) {
            if (c > 0xFF || (c < 0x20 && c != '\t') || c == 0x7F) {
                throw new IllegalArgumentException("invalid header value: \"" + value + "\"");
            }
        }
        return new JSONObject().fluentPut("app_version", "1.0").fluentPut("nickname", "bot")
                .fluentPut("user_id", 1L);
    }

    @Test
    @DisplayName("已存 Token 带中文、留空同地址测：不发请求，回包与日志都不带 Token")
    void savedTokenWithCjkIsNotEchoed() {
        String token = "fake-saved-x7q令";
        save(token);

        BotConnectionTester.Result result = tester(OneBotConnectionTesterTokenEchoTest::jdkLike)
                .test(SAVED_ADDRESS, 3000, "");

        assertRejectedWithoutEcho(result, token);
    }

    @Test
    @DisplayName("已存 Token 带换行、留空同地址测：不发请求，回包与日志都不带 Token")
    void savedTokenWithNewlineIsNotEchoed() {
        String token = "fake-saved-x7q\n";
        save(token);

        BotConnectionTester.Result result = tester(OneBotConnectionTesterTokenEchoTest::jdkLike)
                .test(SAVED_ADDRESS, 3000, "");

        assertRejectedWithoutEcho(result, token);
    }

    @Test
    @DisplayName("用户敲的 Token 带坏字符：不发请求，回包与日志都不带 Token")
    void typedTokenWithBadCharsIsNotEchoed() {
        String token = "fake-typed-x7q\r全";

        BotConnectionTester.Result result = tester(OneBotConnectionTesterTokenEchoTest::jdkLike)
                .test(SAVED_ADDRESS, 3000, token);

        assertRejectedWithoutEcho(result, token);
    }

    @Test
    @DisplayName("Token 字符都放得下但别处抛出带 Token 的异常：回包不拼原消息，日志里 Token 被遮盖")
    void unexpectedFailureDoesNotEchoToken() {
        save(GOOD_SAVED_TOKEN);

        BotConnectionTester.Result result = tester(sender -> {
            throw new IllegalStateException("boom: Bearer " + sender.getOneBotHttpToken());
        }).test(SAVED_ADDRESS, 3000, "");

        assertFalse(result.ok());
        assertEquals(1, sent.size());
        assertNotEchoed(result, GOOD_SAVED_TOKEN);
        assertTrue(result.detail().contains("IllegalStateException"), result.detail());
    }

    @Test
    @DisplayName("地址两头带空格仍算同一台：留空沿用已存的 Token")
    void paddedSameAddressStillUsesSavedToken() {
        save(GOOD_SAVED_TOKEN);

        BotConnectionTester.Result result = tester(OneBotConnectionTesterTokenEchoTest::jdkLike)
                .test(" " + SAVED_ADDRESS + " ", 3000, "");

        assertFalse(sent.isEmpty());
        for (OneBotSender s : sent) {
            if (!GOOD_SAVED_TOKEN.equals(s.getOneBotHttpToken())) {
                fail("发出去的 Token 不是已存那一份，长度 " + String.valueOf(s.getOneBotHttpToken()).length());
            }
        }
        assertTrue(result.ok(), result.detail());
    }

    private void assertRejectedWithoutEcho(BotConnectionTester.Result result, String token) {
        assertFalse(result.ok());
        assertNotEchoed(result, token);
        assertEquals(0, sent.size(), "HTTP 接口被调了 " + sent.size() + " 次");
        assertTrue(result.detail().contains("放不下的字符"), result.detail());
    }

    /** Token 任意连续 4 个字符都不许出现在回包与日志里；失败消息只给长度和位置 */
    private void assertNotEchoed(BotConnectionTester.Result result, String token) {
        List<String> texts = new ArrayList<>();
        texts.add(String.valueOf(result.detail()));
        texts.add(String.valueOf(result.advice()));
        for (ILoggingEvent event : logs.list) {
            texts.add(event.getFormattedMessage());
            if (event.getThrowableProxy() != null) {
                texts.add(String.valueOf(event.getThrowableProxy().getMessage()));
            }
        }
        for (int i = 0; i + 4 <= token.length(); i++) {
            String piece = token.substring(i, i + 4);
            for (int t = 0; t < texts.size(); t++) {
                if (texts.get(t).contains(piece)) {
                    fail("第 " + t + " 段文字（0=detail，1=advice，其后为日志）含 Token 从第 " + i
                            + " 位起的 4 个字符，Token 长度 " + token.length());
                }
            }
        }
    }
}
