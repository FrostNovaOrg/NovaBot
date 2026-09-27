package org.frostnova.nova.core.alert;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import com.sun.net.httpserver.HttpServer;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.RestTemplateConfig;
import org.frostnova.nova.core.properties.LogProperties;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.util.HttpUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.ResourceAccessException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Webhook 发送失败时往外交的报错文本：只留主机名与失败原因，地址的路径与查询串一个字不带
 *
 * <h2>抓的是哪件用户故障</h2>
 * Bark、Server 酱这类服务把推送密钥拼在地址路径里。发送失败时若把报错原文交出去，
 * 密钥会从三个地方露出来：点「发一条测试」的卡片、时间线详情（日志页）、工程日志。
 * 面板可能正开在直播画面上，截图、投屏、把日志发给别人看都发生在这一刻——
 * 漏的时机正好是最坏的时机。
 *
 * <h2>为什么用真失败而不是自造一个异常</h2>
 * 报错原文长什么样由网络栈与 spring-web 说了算（例如 {@code I/O error on POST request for "…"}）。
 * 自造异常只证明得了「替换逻辑在自己写的输入上管用」。这里连本机一个关着的端口，
 * 拿到的是真栈、真 message、真起因链。
 */
@DisplayName("Webhook 发送失败的报错")
class WebhookFailureLeakTest {

    /**
     * 模拟 Bark 那种把推送密钥拼进路径的地址（只在测试里出现）
     */
    private static final String PUSH_KEY = "AbCdEfPushKey123";

    /**
     * 密钥后面还有一段普通路径：整段路径都该被抹掉，不能只抹「看着像密钥」的那段
     */
    private static final String EXTRA_PATH = "barkgroup";

    /**
     * 地址上原有的查询参数：查询串也整段不许露
     */
    private static final String QUERY = "group=bot&level=active";

    private static final String HOST = "127.0.0.1";

    /**
     * 一套真件：真 HttpUtil 打真地址，失败怎么冒出来由网络栈说了算
     */
    private static final class Fixture {
        final NovaCoreProperties properties = new NovaCoreProperties();

        final List<AlertChannel> channels = new ArrayList<>();

        final List<TimelineEvent> timeline = new ArrayList<>();

        final HttpUtil http;

        final AlertService service;

        Fixture(HttpUtil http, String url) {
            properties.getAlert().setWebhookUrl(url);
            this.http = http;
            channels.add(new WebhookAlertChannel(properties, http));
            @SuppressWarnings("unchecked")
            ObjectProvider<AlertChannel> provider = mock(ObjectProvider.class);
            when(provider.orderedStream()).thenAnswer(invocation -> channels.stream());
            service = new AlertService(properties, provider, timeline::add);
        }

        Fixture(String url) {
            this(realHttp(), url);
        }
    }

    private static HttpUtil realHttp() {
        return new HttpUtil(new ThreadPoolTaskExecutor(),
                RestTemplateConfig.buildTemplate(Duration.ofSeconds(3), Duration.ofSeconds(3)),
                new LogProperties());
    }

    /**
     * 一个关着的端口：连接当场被拒，失败是真栈真文
     */
    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * 带密钥与查询串的地址
     */
    private static String secretUrl(int port) {
        return "http://" + HOST + ":" + port + "/" + PUSH_KEY + "/" + EXTRA_PATH + "?" + QUERY;
    }

    /**
     * 阳性对照：真失败的原文里确实带着密钥
     * <p>
     * 少了这一条，下面几处「不带密钥」在注入根本没注入到东西时同样是绿的。
     * 实测这一版 spring-web 的 I/O 失败原文带路径、不带查询串——查询串那一半由
     * 起因链那一格的自造注入来量，阳性对照只钉真栈确实带出来的那一半。
     */
    private static String rawFailureOf(Fixture fixture) {
        try {
            fixture.http.postForStatus(fixture.properties.getAlert().getWebhookUrl(), new LinkedHashMap<>(), "{}",
                    HttpUtil.AddressIsCredential.YES);
            return "（没有失败，这一趟居然发出去了）";
        } catch (Exception e) {
            return e.toString();
        }
    }

    /**
     * 报错文本的共同底线：密钥、路径、查询串都不许有，主机与失败原因必须还在
     */
    private static void assertKeepsHostAndReasonOnly(String where, String text, String host, String... mustAlsoKeep) {
        assertFalse(text.contains(PUSH_KEY), where + " 露出推送密钥:\n" + text);
        assertFalse(text.contains(EXTRA_PATH), where + " 露出地址路径:\n" + text);
        assertFalse(text.contains("group=bot"), where + " 露出查询串:\n" + text);
        assertFalse(text.contains("level=active"), where + " 露出查询串:\n" + text);
        assertTrue(text.contains(host), where + " 看不出是哪台主机:\n" + text);
        for (String keep : mustAlsoKeep) {
            assertTrue(text.contains(keep), where + " 看不出失败原因（缺「" + keep + "」）:\n" + text);
        }
    }

    @Test
    @DisplayName("阳性对照：真失败的原文里带着密钥，下面的不漏断言才是真的在量")
    void rawFailureCarriesTheSecret() throws IOException {
        Fixture fixture = new Fixture(secretUrl(closedPort()));

        String raw = rawFailureOf(fixture);

        assertTrue(raw.contains(PUSH_KEY), "注入没注入到密钥，说明这把尺子量错了地方:\n" + raw);
        assertTrue(raw.contains(EXTRA_PATH), "注入没注入到路径:\n" + raw);
    }

    @Test
    @DisplayName("点「发一条测试」失败时卡片上的报错只留主机与原因——面板可能正开在直播画面上")
    void testReplyCarriesHostAndReasonOnly() throws IOException {
        Fixture fixture = new Fixture(secretUrl(closedPort()));

        AlertService.TestResult result = fixture.service.test("webhook");

        assertKeepsHostAndReasonOnly("测试回话", result.message(), HOST, "连不上");
    }

    @Test
    @DisplayName("真告警失败时时间线详情里的原因只留主机与原因——日志页照原样显示这一栏")
    void timelineReasonCarriesHostAndReasonOnly() throws IOException {
        Fixture fixture = new Fixture(secretUrl(closedPort()));

        fixture.service.alert("link.lost", "机器人掉线", "请重新扫码");

        assertFalse(fixture.timeline.isEmpty(), "失败该记一条时间线，一条没记就是量错了地方");
        String reason = fixture.timeline.get(fixture.timeline.size() - 1).detail().get("reason");
        assertKeepsHostAndReasonOnly("时间线详情", String.valueOf(reason), HOST, "连不上");
    }

    @Test
    @DisplayName("真告警失败时工程日志整份都不带密钥——栈迹与起因链都要留着，排障还得靠它")
    void engineeringLogCarriesHostAndReasonOnly() throws IOException {
        Fixture fixture = new Fixture(secretUrl(closedPort()));

        String rendered = captureRendered(() -> fixture.service.alert("link.lost", "机器人掉线", "请重新扫码"));

        assertKeepsHostAndReasonOnly("工程日志", rendered, HOST, "连不上");
        assertTrue(stackFrame.matcher(rendered).find(), "栈帧丢了，出错就没法定位了:\n" + rendered);
    }

    @Test
    @DisplayName("GET 方式失败时连查询串也不带——标题与内容当时正拼在查询串里")
    void getFailureDropsQueryStringToo() throws IOException {
        Fixture fixture = new Fixture(secretUrl(closedPort()));
        fixture.properties.getAlert().setWebhookMethod("GET");

        AlertService.TestResult result = fixture.service.test("webhook");

        assertKeepsHostAndReasonOnly("GET 测试回话", result.message(), HOST, "连不上");
        assertFalse(result.message().contains("title="), "查询串里的标题字段露出来了:\n" + result.message());
        assertFalse(result.message().contains("content="), "查询串里的内容字段露出来了:\n" + result.message());
    }

    /**
     * 地址里混进空格的那一种（粘贴换行、手滑都常见）：空格落在推送密钥前面。
     * 现码里 URI.create 会抛，原文带着整条地址；剥地址的正则碰到空白就停，
     * 空格后面那段原样留在工程日志里——正是密钥待的地方。
     */
    private static String spaceySecretUrl(int port) {
        return "http://" + HOST + ":" + port + "/ " + PUSH_KEY + "/" + EXTRA_PATH + "?" + QUERY;
    }

    @Test
    @DisplayName("GET 地址里混进空格：往外那句只带主机名与「地址里有空格」，整条异常文本里找不到密钥")
    void getWithSpaceInUrlFailsWithoutLeaking() throws IOException {
        Fixture fixture = new Fixture(spaceySecretUrl(closedPort()));
        fixture.properties.getAlert().setWebhookMethod("GET");

        AlertService.TestResult[] holder = new AlertService.TestResult[1];
        String rendered = captureRendered(() -> holder[0] = fixture.service.test("webhook"));

        assertTrue(holder[0].message().contains("地址里有空格"),
                "往外那句没说清是地址里有空格:\n" + holder[0].message());
        assertKeepsHostAndReasonOnly("GET 空格测试回话", holder[0].message(), HOST);
        // 工程日志打出整条异常（含起因链与栈迹），那一整份里也不许有密钥
        assertKeepsHostAndReasonOnly("GET 空格工程日志", rendered, HOST);
    }

    @Test
    @DisplayName("地址里没有空格时 GET 照旧走网络那一路，不会被空格分支误伤")
    void getWithoutSpaceStillDialsOut() throws IOException {
        Fixture fixture = new Fixture(secretUrl(closedPort()));
        fixture.properties.getAlert().setWebhookMethod("GET");

        AlertService.TestResult result = fixture.service.test("webhook");

        assertKeepsHostAndReasonOnly("GET 无空格", result.message(), HOST, "连不上");
        assertFalse(result.message().contains("地址里有空格"),
                "没有空格也报空格，说明空格分支放错了地方:\n" + result.message());
    }

    @Test
    @DisplayName("对方回了状态码时报错带主机与状态码——状态码本身就是失败原因")
    void statusFailureKeepsHostAndCode() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(HOST, 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(502, -1);
            exchange.close();
        });
        server.start();
        try {
            Fixture fixture = new Fixture(secretUrl(server.getAddress().getPort()));

            AlertService.TestResult result = fixture.service.test("webhook");

            assertKeepsHostAndReasonOnly("状态码那一路", result.message(), HOST, "502");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("起因链里带着地址也不漏——工程日志会打出整条起因链")
    void causeChainCarriesNoSecretEither() {
        // 自造的深起因：真栈的起因消息一般不带地址，这一格专堵「起因里带地址」那条路。
        // 起因是超时那一类：原因词还得分得出「超时」，不是一律「发送失败」
        String outerText = "I/O error on POST request for \"http://api.day.app/"
                + PUSH_KEY + "/" + EXTRA_PATH + "?" + QUERY + "\": boom";
        String causeText = "read https://api.day.app/" + PUSH_KEY + "/" + EXTRA_PATH + " timed out";
        ResourceAccessException outer = new ResourceAccessException(outerText, new SocketTimeoutException(causeText));
        assertTrue(outerText.contains(PUSH_KEY) && outerText.contains("group=bot") && causeText.contains(PUSH_KEY),
                "注入没注入到密钥与查询串，说明这把尺子量错了地方");
        HttpUtil http = mock(HttpUtil.class);
        when(http.postForStatus(anyString(), anyMap(), any(), any())).thenThrow(outer);
        Fixture fixture = new Fixture(http, "https://api.day.app/" + PUSH_KEY + "/" + EXTRA_PATH + "?" + QUERY);

        String rendered = captureRendered(() -> fixture.service.alert("link.lost", "机器人掉线", "请重新扫码"));

        assertKeepsHostAndReasonOnly("起因链", rendered, "api.day.app", "超时");
        assertTrue(rendered.contains("Caused by"), "起因链被整个丢掉了, 排障要看的层就没了:\n" + rendered);
    }

    private static final Pattern stackFrame = Pattern.compile("(?m)^\\s+at\\s");

    /**
     * 把这一趟工程日志按生产的模式渲染出来，返回落盘会长的样子
     */
    private static String captureRendered(Runnable action) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} %5p --- [%20.20t] %-40.40logger{39} : %msg%n");
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
        appender.setContext(context);
        appender.setEncoder(encoder);
        appender.setOutputStream(sink);
        appender.start();

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AlertService.class);
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
}
