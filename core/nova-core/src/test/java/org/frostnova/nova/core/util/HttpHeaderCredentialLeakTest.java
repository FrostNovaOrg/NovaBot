package org.frostnova.nova.core.util;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import org.frostnova.nova.core.config.RestTemplateConfig;
import org.frostnova.nova.core.properties.LogProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 凭据带了请求头放不下的字符时，报错、日志和健康页不再带出凭据本身
 *
 * <h2>抓的是哪件用户故障</h2>
 * 配置里的 OneBot HTTP Token、Webhook 请求头或 B 站 Cookie，粘贴时带上了行尾的换行。
 * 连接失败以后，日志、健康页和投递结果里都出现整串凭据。主播把日志贴出去求助，凭据就漏了。
 *
 * <h2>两处漏法</h2>
 * 一是坏头值被 HTTP 客户端在组请求那一步拒收，拒收的报错原文里带着<b>整串头值</b>，
 * 调用方把这份原文写进日志、健康页和投递结果。
 * 二是开了网络日志时，失败行的异常原文与栈迹首行都没遮请求头的值：
 * 走 {@link UrlRedactor} 那支只剥地址，走 {@link UrlMasker} 那支只遮查询串，
 * 谁也不管头值——而头值本身就是凭据（Authorization、Cookie 一类）。
 *
 * <h2>为什么拦在 HttpUtil</h2>
 * 所有请求最后都落到这两个执行方法，在这里挡一道，调用方各自不必记得先查一遍；
 * 挡下来那句只写头名、不写值，也不挂带值的起因——报错会一路写进健康页。
 */
@DisplayName("请求头里的凭据")
class HttpHeaderCredentialLeakTest {

    /**
     * 只在测试里出现的假凭据
     */
    private static final String FAKE_TOKEN = "FAKE-TOKEN-abc123XYZ-not-real";

    /**
     * 坏头值：粘贴时把行尾的换行一起带上了
     */
    private static final String BAD_VALUE = "Bearer " + FAKE_TOKEN + "\n";

    /**
     * 好头值：放得进请求头，值本身就是凭据
     */
    private static final String OK_VALUE = "Bearer " + FAKE_TOKEN;

    private static final String HEADER = "Authorization";

    private static final String URL = "http://127.0.0.1:1/probe";

    /**
     * 照生产 logback.xml 里网络日志那个 appender 的模式；栈迹由 logback 接在行末
     */
    private static final String PATTERN = "%d{yyyy-MM-dd HH:mm:ss.SSS} %5p --- [%20.20t] : %msg%n";

    /**
     * 四条进 HttpUtil 的路：两个执行方法各配上「地址是不是凭据」两支
     */
    private enum Way {
        PLAIN_STRING {
            @Override
            void send(HttpUtil http, String url, Map<String, String> headers) {
                http.getForEntity(url, headers);
            }
        },
        PLAIN_URI {
            @Override
            void send(HttpUtil http, String url, Map<String, String> headers) {
                http.get(URI.create(url), headers);
            }
        },
        CREDENTIAL_STRING {
            @Override
            void send(HttpUtil http, String url, Map<String, String> headers) {
                http.postForStatus(url, headers, "{}", HttpUtil.AddressIsCredential.YES);
            }
        },
        CREDENTIAL_URI {
            @Override
            void send(HttpUtil http, String url, Map<String, String> headers) {
                http.getForStatus(URI.create(url), headers, HttpUtil.AddressIsCredential.YES);
            }
        };

        abstract void send(HttpUtil http, String url, Map<String, String> headers);
    }

    static Stream<Arguments> ways() {
        return Stream.of(
                Arguments.of("普通请求, 字符串地址", Way.PLAIN_STRING),
                Arguments.of("普通请求, URI 地址", Way.PLAIN_URI),
                Arguments.of("地址本身是凭据, POST", Way.CREDENTIAL_STRING),
                Arguments.of("地址本身是凭据, GET", Way.CREDENTIAL_URI));
    }

    private static HttpUtil httpUtil(RestTemplate template, boolean networkLog) {
        LogProperties logConfig = new LogProperties();
        logConfig.setNetworkLog(networkLog);
        return new HttpUtil(new ThreadPoolTaskExecutor(), template, logConfig);
    }

    private static RestTemplate realTemplate(Duration timeout) {
        return RestTemplateConfig.buildTemplate(timeout, timeout);
    }

    /**
     * 把这一趟的网络日志按生产的模式渲染出来，返回落盘会长的样子
     */
    private static String captureNetworkLog(Runnable action) {
        return captureLog("NetworkLogger", action);
    }

    /**
     * 把某个日志器这一趟写出来的按生产的模式渲染出来，返回落盘会长的样子
     */
    private static String captureLog(String loggerName, Runnable action) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern(PATTERN);
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
        appender.setContext(context);
        appender.setEncoder(encoder);
        appender.setOutputStream(sink);
        appender.start();

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(loggerName);
        boolean additive = logger.isAdditive();
        logger.setAdditive(false);
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            logger.setAdditive(additive);
            appender.stop();
        }
        return sink.toString(StandardCharsets.UTF_8);
    }

    /**
     * 会抛错的桩请求工厂：报错文字里带着调用方交进来的头值
     * <p>
     * 真实失败里这种情况很常见——客户端把触发失败的头值裹进报错原文，起因里再带一遍。
     * 抛的是读写失败那一类，模板会照常把它包成「I/O error on …」，与真实的连接失败同形
     */
    private static ClientHttpRequestFactory failingFactory(AtomicInteger createCalls) {
        return (uri, method) -> {
            createCalls.incrementAndGet();
            return new MockClientHttpRequest(method, uri) {
                @Override
                protected ClientHttpResponse executeInternal() throws IOException {
                    String seen = String.valueOf(getHeaders().getFirst(HEADER));
                    throw new IOException("桩请求失败, 见 " + HEADER + "=" + seen,
                            new IllegalStateException("起因里也带一遍 " + seen));
                }
            };
        };
    }

    /**
     * 照单全收的桩请求工厂：记下这一趟真正交出去的头
     */
    private static ClientHttpRequestFactory recordingFactory(Map<String, String> sent, AtomicInteger createCalls) {
        return (uri, method) -> {
            createCalls.incrementAndGet();
            return new MockClientHttpRequest(method, uri) {
                @Override
                protected ClientHttpResponse executeInternal() {
                    sent.put(HEADER, String.valueOf(getHeaders().getFirst(HEADER)));
                    return new MockClientHttpResponse(new byte[0], 200);
                }
            };
        };
    }

    /**
     * 失败行里除地址外还有两处带原文：括号里的异常 message、末参异常的栈迹首行（toString）。
     * 整份渲染文本一起量，一处漏了都算漏
     */
    private static void assertNoToken(String where, String rendered) {
        assertFalse(rendered.contains(FAKE_TOKEN), where + " 露出了凭据:\n" + rendered);
        assertFalse(rendered.contains(BAD_VALUE.trim()), where + " 露出了凭据:\n" + rendered);
        assertTrue(rendered.contains("***"), where + " 看不出这里原本有过一段凭据:\n" + rendered);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ways")
    @DisplayName("坏头值在发出前就被拦下：报错只点出头名，凭据一个字不带")
    void badHeaderValueIsRejectedWithoutTheValue(String label, Way way) {
        AtomicInteger createCalls = new AtomicInteger();
        RestTemplate real = realTemplate(Duration.ofSeconds(2));
        ClientHttpRequestFactory counting = (uri, method) -> {
            createCalls.incrementAndGet();
            return real.getRequestFactory().createRequest(uri, method);
        };

        HttpUtil http = httpUtil(new RestTemplate(counting), false);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(HEADER, BAD_VALUE);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> way.send(http, URL, headers), label + " 没有拦下坏头值");

        assertTrue(String.valueOf(e.getMessage()).contains(HEADER),
                label + " 报错没点出是哪个头写得不对: " + e.getMessage());
        for (Throwable c = e; c != null; c = (c.getCause() == c ? null : c.getCause())) {
            assertFalse(String.valueOf(c.getMessage()).contains(FAKE_TOKEN),
                    label + " 起因链里带着凭据: " + c.getMessage());
            assertFalse(c.toString().contains(FAKE_TOKEN),
                    label + " 起因链里带着凭据: " + c);
            assertFalse(c.toString().contains(BAD_VALUE),
                    label + " 起因链里带着坏头值原文: " + c);
        }
        assertEquals(0, createCalls.get(), label + " 把请求发出去了");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ways")
    @DisplayName("失败行把调用方的头值整段换成掩码——开着网络日志排障时正是常失败的时刻")
    void failureLineMasksCallerHeaderValues(String label, Way way) {
        AtomicInteger createCalls = new AtomicInteger();
        HttpUtil http = httpUtil(new RestTemplate(failingFactory(createCalls)), true);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(HEADER, OK_VALUE);

        String rendered = captureNetworkLog(() ->
                assertThrows(ResourceAccessException.class, () -> way.send(http, URL, headers)));

        assertTrue(rendered.contains("<- ["), label + " 失败那一行没写出来，下面的不漏断言就是空的:\n" + rendered);
        assertNoToken(label + " 失败行", rendered);
        assertTrue(rendered.contains("127.0.0.1"), label + " 看不出是哪台主机:\n" + rendered);
        assertTrue(rendered.contains("GET") || rendered.contains("POST"),
                label + " 看不出是哪个请求:\n" + rendered);
    }

    @Test
    @DisplayName("正常头值照常发出、照常记日志，不被误拦")
    void goodHeaderValueStillGoesOut() {
        Map<String, String> sent = new LinkedHashMap<>();
        AtomicInteger createCalls = new AtomicInteger();
        HttpUtil http = httpUtil(new RestTemplate(recordingFactory(sent, createCalls)), true);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(HEADER, OK_VALUE);

        String rendered = captureNetworkLog(() -> http.get(URL, headers));

        assertEquals(OK_VALUE, sent.get(HEADER), "正常头值被改掉了");
        assertEquals(1, createCalls.get(), "请求没发出去");
        assertTrue(rendered.contains("GET ->"), "发出那一行没写出来:\n" + rendered);
        assertTrue(rendered.contains("GET <- [200]"), "成功那一行没写出来:\n" + rendered);
        assertTrue(rendered.contains("/probe"), "普通请求的路径是排障要看的，不该跟着没了:\n" + rendered);
    }

    @Test
    @DisplayName("取图失败时，日志里的异常同样不带调用方的头值——这一路的调用方交进来的是 Cookie")
    void imageFetchLogMasksCallerHeaderValues() {
        AtomicInteger createCalls = new AtomicInteger();
        HttpUtil http = httpUtil(new RestTemplate(failingFactory(createCalls)), false);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(HEADER, OK_VALUE);

        String rendered = captureLog(HttpUtil.class.getName(),
                () -> http.getBufferedImage(URL, headers));

        assertTrue(rendered.contains("读取图片异常"), "失败那一行没写出来:\n" + rendered);
        assertNoToken("取图日志", rendered);
    }
}
