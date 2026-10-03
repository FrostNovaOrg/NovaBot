package org.frostnova.nova.core.alert;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import com.sun.net.httpserver.HttpServer;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.RestTemplateConfig;
import org.frostnova.nova.core.properties.LogProperties;
import org.frostnova.nova.core.util.HttpUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网络日志里告警这一路的地址：<b>只记主机名</b>，推送密钥不再随网络日志落盘
 *
 * <h2>抓的是哪件用户故障</h2>
 * 打开设置里的「网络日志」以后发一条 Bark 告警（成功、失败各一次），网络日志里出现推送密钥。
 * Bark、Server 酱把推送密钥拼在地址路径里，而网络日志记的是整条地址——
 * 一开这开关，每发一条告警密钥就落一次盘，发送成功时也照落。
 * 使用者排障时常把日志整份发给别人，<b>漏的时机正好是最坏的时机</b>。
 *
 * <h2>为什么是只记主机名，而不是接着往打码名单里添键</h2>
 * 密钥在<b>路径</b>里而不在查询串里，打码只遮点名的查询参数、路径一律原样；
 * 路径整段拿掉、主机名留下（哪台主机是排障要看的），不猜哪一段路径像密钥。
 *
 * <h2>为什么另有一格守着普通请求</h2>
 * 只记主机名是这一路的特权，不是全局新规：别的请求（比如哔哩哔哩的接口）照旧记完整的
 * 打码地址，排障要看的路径不能跟着一起没了。少那一格，「把所有请求的路径都遮掉」也会全绿。
 *
 * <h2>为什么量落盘那一份、而不是量打码函数的返回值</h2>
 * 失败那一行除地址外还有两处带原文：括号里的异常 message、末参异常的栈迹首行（toString）。
 * 只断言「地址那一列干净」的话，密钥从 message 照样落盘。所以整份渲染文本一起量。
 */
@DisplayName("网络日志里的告警地址")
class WebhookNetworkLogLeakTest {

    /**
     * 模拟 Bark 那种把推送密钥拼进路径的地址（只在测试里出现）
     */
    private static final String PUSH_KEY = "AbCdEfPushKey123";

    /**
     * 密钥后面还有一段普通路径：整段路径都该拿掉，不能只抹「看着像密钥」的那段
     */
    private static final String EXTRA_PATH = "barkgroup";

    /**
     * 地址上原有的查询参数：查询串也整段不许露
     */
    private static final String QUERY = "group=bot&level=active";

    private static final String HOST = "127.0.0.1";

    /**
     * 照生产 logback.xml 里网络日志那个 appender 的模式；栈迹由 logback 接在行末
     */
    private static final String PATTERN = "%d{yyyy-MM-dd HH:mm:ss.SSS} %5p --- [%20.20t] : %msg%n";

    private static final Pattern stackFrame = Pattern.compile("(?m)^\\s+at\\s");

    /**
     * 一套真件：真 HttpUtil 打真地址，网络日志开着，写了什么由真栈说了算
     */
    private static WebhookAlertChannel channelWith(String url, String method) {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getAlert().setWebhookUrl(url);
        properties.getAlert().setWebhookMethod(method);
        LogProperties logConfig = new LogProperties();
        logConfig.setNetworkLog(true);
        HttpUtil http = new HttpUtil(new ThreadPoolTaskExecutor(),
                RestTemplateConfig.buildTemplate(Duration.ofSeconds(3), Duration.ofSeconds(3)), logConfig);
        return new WebhookAlertChannel(properties, http);
    }

    /**
     * 本机 1 号端口：系统划给临时口的那段够不着它。
     * 用前确认连过去是当场被拒；有人在听或别的失败则跳过。
     */
    private static int closedPort() {
        LoopbackPort.assumePortOneRefused();
        return 1;
    }

    /**
     * 一个照单全收的本机接收端：成功那两行得有个真 200 才写得出来
     */
    private static HttpServer okServer() throws IOException {
        LoopbackPort.assumeAllowed();
        HttpServer server = HttpServer.create(new InetSocketAddress(HOST, 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        return server;
    }

    /**
     * 带密钥与查询串的地址
     */
    private static String secretUrl(int port) {
        return "http://" + HOST + ":" + port + "/" + PUSH_KEY + "/" + EXTRA_PATH + "?" + QUERY;
    }

    /**
     * 把这一趟的网络日志按生产的模式渲染出来，返回落盘会长的样子
     */
    private static String captureNetworkLog(Runnable action) {
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
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("NetworkLogger");
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
     * 报文共同底线：密钥、路径、查询串都不许有，主机名必须还在
     */
    private static void assertKeepsHostOnly(String where, String rendered) {
        assertFalse(rendered.contains(PUSH_KEY), where + " 露出推送密钥:\n" + rendered);
        assertFalse(rendered.contains(EXTRA_PATH), where + " 露出地址路径:\n" + rendered);
        assertFalse(rendered.contains("group=bot"), where + " 露出查询串:\n" + rendered);
        assertFalse(rendered.contains("level=active"), where + " 露出查询串:\n" + rendered);
        assertTrue(rendered.contains(HOST), where + " 看不出是哪台主机:\n" + rendered);
    }

    /**
     * 🔴 阳性对照：先证明这把尺子确实能看见明文。
     * 少了这一条，下面几处「不带密钥」在日志根本没写出来时同样是绿的。
     */
    @Test
    @DisplayName("判据自己先能在没打码的写法上看见明文")
    void theRulerSeesPlaintextWhenNothingIsMasked() {
        String rendered = captureNetworkLog(() -> LoggerFactory.getLogger("NetworkLogger")
                .error("GET -> http://x.test/{}/{}?{}", PUSH_KEY, EXTRA_PATH, QUERY));

        assertTrue(rendered.contains(PUSH_KEY), "不打码时都看不见明文，说明这把尺子量错了地方:\n" + rendered);
    }

    @Test
    @DisplayName("Bark 那种 GET 告警发出去以后，发出那一行只记主机名——排障要把日志整份发给别人")
    void barkSendLineKeepsHostOnly() throws IOException {
        HttpServer server = okServer();
        try {
            WebhookAlertChannel channel = channelWith(secretUrl(server.getAddress().getPort()), "GET");

            String rendered = captureNetworkLog(() -> channel.send("标题", "内容"));

            assertTrue(rendered.contains("GET ->"), "发出那一行没写出来，下面的不漏断言就是空的:\n" + rendered);
            assertKeepsHostOnly("GET 发出行", rendered);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Bark 那种 GET 告警发送失败时，失败行连异常原文与栈迹也不带密钥——开着网络日志排障时正是常失败的时刻")
    void barkFailureLineKeepsHostOnly() throws IOException {
        WebhookAlertChannel channel = channelWith(secretUrl(closedPort()), "GET");

        String rendered = captureNetworkLog(
                () -> assertThrows(IllegalStateException.class, () -> channel.send("标题", "内容")));

        assertTrue(rendered.contains("GET <- ["), "失败那一行没写出来，下面的不漏断言就是空的:\n" + rendered);
        assertKeepsHostOnly("GET 失败行", rendered);
        // 栈帧必须还在——把密钥连同排障价值一起删掉，等于逼人把网络日志整个关掉
        assertTrue(stackFrame.matcher(rendered).find(), "栈帧丢了，出错就没法定位了:\n" + rendered);
    }

    @Test
    @DisplayName("Server 酱那种 POST 告警发出去以后，发出行与成功行都只记主机名——成功发送也照落过密钥")
    void postSendAndSuccessLinesKeepHostOnly() throws IOException {
        HttpServer server = okServer();
        try {
            WebhookAlertChannel channel = channelWith(secretUrl(server.getAddress().getPort()), "POST");

            String rendered = captureNetworkLog(() -> channel.send("标题", "内容"));

            assertTrue(rendered.contains("POST ->"), "发出那一行没写出来:\n" + rendered);
            assertTrue(rendered.contains("[200]"), "成功那一行没写出来，下面的不漏断言就是空的:\n" + rendered);
            assertKeepsHostOnly("POST 发出行与成功行", rendered);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Server 酱那种 POST 告警发送失败时，失败行连异常原文与栈迹也不带密钥")
    void postFailureLineKeepsHostOnly() throws IOException {
        WebhookAlertChannel channel = channelWith(secretUrl(closedPort()), "POST");

        String rendered = captureNetworkLog(
                () -> assertThrows(IllegalStateException.class, () -> channel.send("标题", "内容")));

        assertTrue(rendered.contains("POST <- ["), "失败那一行没写出来，下面的不漏断言就是空的:\n" + rendered);
        assertKeepsHostOnly("POST 失败行", rendered);
        assertTrue(stackFrame.matcher(rendered).find(), "栈帧丢了，出错就没法定位了:\n" + rendered);
    }

    /**
     * 带拦不住的字符的地址：那个字符落在推送密钥前面，URI 照收、请求照发
     */
    private static String badCharSecretUrl(int port, char bad) {
        return "http://" + HOST + ":" + port + "/" + bad + "/" + PUSH_KEY + "/" + EXTRA_PATH + "?" + QUERY;
    }

    /**
     * 同一次真失败的原文（绕过剥法拿到的那一条）：长什么样由网络栈与 spring-web 说了算
     */
    private static String rawFailureOf(String url, String method) {
        LogProperties logConfig = new LogProperties();
        HttpUtil http = new HttpUtil(new ThreadPoolTaskExecutor(),
                RestTemplateConfig.buildTemplate(Duration.ofSeconds(3), Duration.ofSeconds(3)), logConfig);
        try {
            if ("GET".equalsIgnoreCase(method)) {
                http.getForStatus(URI.create(url + (url.contains("?") ? "&" : "?") + "title=t&content=c"),
                        new LinkedHashMap<>(), HttpUtil.AddressIsCredential.YES);
            } else {
                http.postForStatus(url, new LinkedHashMap<>(), "{}", HttpUtil.AddressIsCredential.YES);
            }
            return "（没有失败，这一趟居然发出去了）";
        } catch (Exception e) {
            return e.toString();
        }
    }

    /**
     * 密钥前面带 {@code '} {@code (} 全角{@code ，} 时：剥地址的正则在这几个字符上收边界，
     * 失败行括号里的异常原文与末参栈迹首行都会把字符后面那段（正是密钥）带下来。
     * 开着网络日志排障时正是常失败的时刻，日志整份发出去密钥就跟着走。
     */
    private void stopCharFailureLineKeepsHostOnly(String method, char bad) throws IOException {
        String url = badCharSecretUrl(closedPort(), bad);

        // 阳性对照：这一形的真失败原文确实带着密钥——少了这一条，下面的不漏在请求根本没发出去时同样是绿的
        String raw = rawFailureOf(url, method);
        assertTrue(raw.contains(PUSH_KEY), "注入没注入到密钥，说明这把尺子量错了地方:\n" + raw);

        WebhookAlertChannel channel = channelWith(url, method);
        String rendered = captureNetworkLog(
                () -> assertThrows(IllegalStateException.class, () -> channel.send("标题", "内容")));

        assertTrue(rendered.contains(method + " <- ["), "失败那一行没写出来，下面的不漏断言就是空的:\n" + rendered);
        assertKeepsHostOnly(method + " 停字符(" + bad + ")失败行", rendered);
        assertTrue(stackFrame.matcher(rendered).find(), "栈帧丢了，出错就没法定位了:\n" + rendered);
    }

    static Stream<Arguments> stopCharForms() {
        return Stream.of(
                Arguments.of("GET", '\''),
                Arguments.of("GET", '('),
                Arguments.of("GET", '，'),
                Arguments.of("POST", '\''),
                Arguments.of("POST", '('),
                Arguments.of("POST", '，'));
    }

    @ParameterizedTest(name = "{0} 方式，密钥前带 [{1}]")
    @MethodSource("stopCharForms")
    @DisplayName("密钥前带拦不住的字符时，失败行连异常原文与栈迹也不带密钥")
    void stopCharFailureLineLeaksNothing(String method, char bad) throws IOException {
        stopCharFailureLineKeepsHostOnly(method, bad);
    }

    /**
     * 🔴 阴性对照：普通请求照旧记完整的打码地址。
     * 少了这一格，「为告警这一路把所有请求的路径都遮掉」也全绿——而那正是不许做的。
     */
    @Test
    @DisplayName("对照：哔哩哔哩一类的普通请求照旧记完整路径，排障不受影响")
    void ordinaryRequestStillLogsTheWholePath() throws IOException {
        HttpServer server = okServer();
        try {
            LogProperties logConfig = new LogProperties();
            logConfig.setNetworkLog(true);
            HttpUtil http = new HttpUtil(new ThreadPoolTaskExecutor(),
                    RestTemplateConfig.buildTemplate(Duration.ofSeconds(3), Duration.ofSeconds(3)), logConfig);
            String url = "http://" + HOST + ":" + server.getAddress().getPort()
                    + "/x/space/wbi/arc/search?mid=12345&csrf=abc123def456ghi789";

            String rendered = captureNetworkLog(() -> http.get(url));

            assertTrue(rendered.contains("GET ->"), "发出那一行没写出来:\n" + rendered);
            assertTrue(rendered.contains("/x/space/wbi/arc/search"),
                    "普通请求的路径是排障要看的，不该跟着没了:\n" + rendered);
            assertTrue(rendered.contains("mid=12345"), "普通请求的查询串不该跟着没了:\n" + rendered);
            // 打码照旧管着查询串里的凭据参数：路径放开不等于凭据参数也放开
            assertTrue(rendered.contains("csrf=***"), "应当看得出这里原本有过一个 csrf 参数:\n" + rendered);
            assertFalse(rendered.contains("abc123def456ghi789"), "查询串里的凭据明文漏了:\n" + rendered);
        } finally {
            server.stop(0);
        }
    }
}
