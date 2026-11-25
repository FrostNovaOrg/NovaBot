package org.frostnova.nova.core.config.ui;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.filter.ThresholdFilter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.alert.AlertChannel;
import org.frostnova.nova.core.alert.AlertService;
import org.frostnova.nova.core.config.LogLevelApplier;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSession;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSessionStore;
import org.frostnova.nova.core.config.ui.auth.LoginThrottle;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.service.AutoSaveIntervalListener;
import org.frostnova.nova.core.service.DefaultLiveDataService;
import org.frostnova.nova.core.service.NovaMailService;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 设置页上保存之后不重启，程序就按新值工作
 * <p>
 * 抓的用户故障：管理员改了设置、页面说已生效，程序却一直按启动时的旧值跑——
 * 日志级别、发件服务器、来源 IP 名单、登录时长与锁定、定时存盘与告警重发的间隔、新版检查。
 * 每一条都走真实的保存口（{@code POST /config/api/values}），再从读取方那一侧看行为变没变。
 */
@DisplayName("设置保存后不重启即按新值工作")
class SettingsApplyWithoutRestartTest {
    private static final ConfigUiAccount ADMIN = ConfigUiAccount.local(Instant.EPOCH);

    @TempDir
    Path directory;

    private Path config;
    private NovaCoreProperties properties;
    private AnnotationConfigApplicationContext context;
    private ConfigUiController settings;
    private MockMvc mvc;

    @BeforeEach
    void prepare() throws Exception {
        config = directory.resolve("application.yml");
        Files.writeString(config, "novabot:\n  core:\n    push:\n      enabled: true\n");
        properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(directory.resolve("data").resolve("live.json").toString());
    }

    @AfterEach
    void close() {
        if (context != null) {
            context.close();
        }
    }

    /**
     * 装好保存口：配置对象之外再登记哪些组件，由各条自己给
     */
    private void start(Consumer<AnnotationConfigApplicationContext> beans) {
        context = new AnnotationConfigApplicationContext();
        context.registerBean(NovaCoreProperties.class, () -> properties);
        beans.accept(context);
        context.refresh();
        settings = ExcludedViewersFileTest.controller(context, config);
        // 两样与运行中相同：代理头过滤器排在最前；读请求体时把机密项交给日志遮蔽
        mvc = MockMvcBuilders.standaloneSetup(settings)
                .setControllerAdvice(new ConsoleCredentialLogAdvice(new ConfigurationMetadataService()))
                .addFilters(new ForwardedHeaderFilter()).build();
    }

    private JSONObject save(Map<String, String> changes, int status) throws Exception {
        return save(changes, status, "127.0.0.1", null);
    }

    /**
     * 照设置页那样保存一批
     * @param remote 请求的对端地址
     * @param forwardedFor 反向代理带来的真实来源，没有时为 null
     */
    private JSONObject save(Map<String, String> changes, int status, String remote, String forwardedFor)
            throws Exception {
        var request = post("/config/api/values").requestAttr(ConfigUiAccess.ACCOUNT, ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content(new JSONObject(changes).toJSONString())
                .with(raw -> {
                    raw.setRemoteAddr(remote);
                    return raw;
                });
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        var response = mvc.perform(request).andReturn().getResponse();
        JSONObject body = JSON.parseObject(response.getContentAsString(StandardCharsets.UTF_8));
        assertEquals(status, response.getStatus(), body::toJSONString);
        if (status == 200) {
            assertTrue(body.getBooleanValue("success"), body::toJSONString);
            assertEquals(List.of(), body.getList("restartRequired", String.class), body::toJSONString);
        }
        return body;
    }

    private ConfigUiRegistrar registrar() {
        return new ConfigUiRegistrar(properties, mock(WebServerApplicationContext.class));
    }

    /**
     * 等一件事成真，等不到就红；上限给足，正常几十毫秒到一两秒就到
     */
    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("等了 20 秒仍未看到：" + what);
            }
            Thread.sleep(20);
        }
    }

    /**
     * 在给定时长内等一件事；等到了为 true，到点没等到为 false（用来看「不该发生的事」这段时间里发生了没有）
     */
    private static boolean within(Duration window, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + window.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    // ================================================================ 1 日志级别

    @Nested
    @DisplayName("日志级别")
    class LogLevels {
        @Test
        @DisplayName("控制台改成 WARN、文件改成 DEBUG：保存后下一行日志就按新级别出现或消失")
        void levelsChangeOnTheSpot() throws Exception {
            LoggerContext logs = new LoggerContext();
            ListAppender<ILoggingEvent> console = thresholded(logs, "Console", "INFO");
            ListAppender<ILoggingEvent> file = thresholded(logs, "RollingFile", "INFO");
            // 文件那一路在真配置里包在异步输出里面，这里照样包一层（同步转交，免得等队列）
            Wrapper async = new Wrapper();
            async.setContext(logs);
            async.setName("AsyncRollingFile");
            async.addAppender(file);
            async.start();
            Logger root = logs.getLogger(Logger.ROOT_LOGGER_NAME);
            root.setLevel(Level.DEBUG);
            root.addAppender(console);
            root.addAppender(async);
            Logger logger = logs.getLogger("org.frostnova.nova.demo");

            start(beans -> beans.registerBean(LogLevelApplier.class, () -> new LogLevelApplier(properties, () -> logs)));
            logger.info("改之前的一行");
            logger.debug("改之前的调试");

            Map<String, String> batch = new LinkedHashMap<>();
            batch.put("novabot.core.log.console", "WARN");
            batch.put("novabot.core.log.file", "debug");
            save(batch, 200);

            logger.info("改之后的一行");
            logger.debug("改之后的调试");
            logger.warn("改之后的警告");

            assertEquals(List.of("改之前的一行", "改之后的警告"), messages(console),
                    "控制台改成 WARN 后，INFO 当场不再出现");
            assertEquals(List.of("改之前的一行", "改之后的一行", "改之后的调试", "改之后的警告"), messages(file),
                    "文件改成 DEBUG 后，调试日志当场写进文件");
            assertEquals("DEBUG", Files.readString(config).lines()
                    .filter(line -> line.strip().startsWith("file:")).findFirst().orElseThrow()
                    .replace("file:", "").replace("\"", "").replace("'", "").strip(),
                    "文件里写的是与内存同一个标准写法");
        }

        @Test
        @DisplayName("留空不收：留空会让日志框架退回 DEBUG，界面上却看不出来")
        void blankLevelIsRejected() throws Exception {
            start(beans -> { });
            String before = Files.readString(config);

            JSONObject rejected = save(Map.of("novabot.core.log.console", " "), 400);

            assertTrue(rejected.getString("message").contains("novabot.core.log.console"), rejected::toJSONString);
            assertEquals(before, Files.readString(config));
            assertNull(properties.getLog().getConsole());
        }

        /**
         * 抓的用户故障：设置页上改了级别，套用器按名字去找的那几路输出在正式配置里叫别的名字，
         * 找不到就改不动，级别照旧是启动时那个
         * <p>
         * 名字取自套用器自己认的那两张表，不在这里另抄一份。正式配置从模块源码目录读：
         * 带 {@code install} 那档构建时 {@code logback.xml} 不进类路径。
         */
        @Test
        @DisplayName("套用器认的输出名，正式配置里确实叫这个名字、门槛确实跟着这两项")
        void appenderNamesMatchTheRealConfiguration() throws Exception {
            Path config = Path.of("src/main/resources/logback.xml");
            assertTrue(Files.isRegularFile(config), "找不到正式的日志配置: " + config.toAbsolutePath());
            String xml = Files.readString(config);
            @SuppressWarnings("unchecked")
            java.util.Set<String> console = (java.util.Set<String>) org.springframework.test.util.ReflectionTestUtils
                    .getField(LogLevelApplier.class, "CONSOLE_APPENDERS");
            @SuppressWarnings("unchecked")
            java.util.Set<String> file = (java.util.Set<String>) org.springframework.test.util.ReflectionTestUtils
                    .getField(LogLevelApplier.class, "FILE_APPENDERS");
            assertFalse(console.isEmpty() || file.isEmpty(), "套用器的名字表是空的，下面量的是空集");
            for (String name : console) {
                assertTrue(appenderBlock(xml, name).contains("${consoleLogLevel}"), name);
            }
            for (String name : file) {
                assertTrue(appenderBlock(xml, name).contains("${fileLogLevel}"), name);
            }
        }

        /**
         * 把事件原样转交给里头那一路的输出，形同真配置里的异步输出，只是不排队
         */
        private static final class Wrapper extends ch.qos.logback.core.AppenderBase<ILoggingEvent>
                implements ch.qos.logback.core.spi.AppenderAttachable<ILoggingEvent> {
            private final ch.qos.logback.core.spi.AppenderAttachableImpl<ILoggingEvent> inner =
                    new ch.qos.logback.core.spi.AppenderAttachableImpl<>();

            @Override protected void append(ILoggingEvent event) { inner.appendLoopOnAppenders(event); }
            @Override public void addAppender(ch.qos.logback.core.Appender<ILoggingEvent> appender) { inner.addAppender(appender); }
            @Override public java.util.Iterator<ch.qos.logback.core.Appender<ILoggingEvent>> iteratorForAppenders() { return inner.iteratorForAppenders(); }
            @Override public ch.qos.logback.core.Appender<ILoggingEvent> getAppender(String name) { return inner.getAppender(name); }
            @Override public boolean isAttached(ch.qos.logback.core.Appender<ILoggingEvent> appender) { return inner.isAttached(appender); }
            @Override public void detachAndStopAllAppenders() { inner.detachAndStopAllAppenders(); }
            @Override public boolean detachAppender(ch.qos.logback.core.Appender<ILoggingEvent> appender) { return inner.detachAppender(appender); }
            @Override public boolean detachAppender(String name) { return inner.detachAppender(name); }
        }

        private static String appenderBlock(String xml, String name) {
            int start = xml.indexOf("<appender name=\"" + name + "\"");
            assertTrue(start >= 0, "logback.xml 里没有输出 " + name);
            return xml.substring(start, xml.indexOf("</appender>", start));
        }

        private static ListAppender<ILoggingEvent> thresholded(LoggerContext logs, String name, String level) {
            ThresholdFilter threshold = new ThresholdFilter();
            threshold.setLevel(level);
            threshold.start();
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.setContext(logs);
            appender.setName(name);
            appender.addFilter(threshold);
            appender.start();
            return appender;
        }

        private static List<String> messages(ListAppender<ILoggingEvent> appender) {
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        }
    }

    // ================================================================ 2 发件服务

    @Nested
    @DisplayName("发件服务")
    class Mail {
        private static final String PASSWORD = "fixture-smtp-secret-7Qx";
        private static final String NEXT_PASSWORD = "fixture-smtp-secret-9Kd";

        private NovaMailService mail;

        @BeforeEach
        void startMail() throws Exception {
            // 照发行模板：发件服务那一块在文件里，只是服务器留空
            Files.writeString(config, "spring:\n  mail:\n    host: \"\"\nnovabot:\n  core:\n    push:\n      enabled: true\n");
            RecordingTransport.reset();
            properties.getMail().setDefaultTo("ops@example.com");
            // 启动时没配服务器：容器里没有发信器，只有协议这类旁项在环境里
            MockEnvironment environment = new MockEnvironment()
                    .withProperty("spring.mail.protocol", RecordingTransport.PROTOCOL);
            ObjectProvider<JavaMailSender> none = new DefaultListableBeanFactory().getBeanProvider(JavaMailSender.class);
            mail = new NovaMailService(none, properties, environment);
        }

        /**
         * @param masked 是否装上运行中那道日志遮蔽
         */
        private void startMail(boolean masked) {
            start(beans -> {
                beans.registerBean(NovaMailService.class, () -> mail);
                if (masked) {
                    beans.registerBean(ConsoleCredentialLogFilter.class);
                }
            });
        }

        @Test
        @DisplayName("启动时没配服务器、后来在设置页填上：保存后下一封信就按新服务器、新账号发出")
        void filledInLaterSendsTheNextMail() throws Exception {
            startMail(false);
            IllegalStateException before = assertThrows(IllegalStateException.class,
                    () -> mail.sendAlertMail("改之前", "正文"));
            assertTrue(before.getMessage().contains("没有配置好"), before.getMessage());

            Map<String, String> batch = new LinkedHashMap<>();
            batch.put("spring.mail.host", " smtp.example.invalid ");
            batch.put("spring.mail.port", "2525");
            batch.put("spring.mail.username", "bot@example.com");
            batch.put("spring.mail.password", PASSWORD);
            save(batch, 200);
            mail.sendAlertMail("改之后", "正文");

            assertEquals(List.of(new RecordingTransport.Connection("smtp.example.invalid", 2525, "bot@example.com", PASSWORD)),
                    RecordingTransport.CONNECTIONS);
            assertEquals("bot@example.com", RecordingTransport.SENT.get(0).from(), "发件人跟着账号换");
            assertEquals("改之后", RecordingTransport.SENT.get(0).subject());
        }

        @Test
        @DisplayName("只改口令：服务器与账号沿用，下一封信用新口令登录")
        void changingOnlyThePasswordKeepsTheRest() throws Exception {
            startMail(false);
            save(Map.of("spring.mail.host", "smtp.example.invalid", "spring.mail.port", "465",
                    "spring.mail.username", "bot@example.com", "spring.mail.password", PASSWORD), 200);
            save(Map.of("spring.mail.password", NEXT_PASSWORD), 200);
            mail.sendAlertMail("换口令之后", "正文");

            assertEquals(new RecordingTransport.Connection("smtp.example.invalid", 465, "bot@example.com", NEXT_PASSWORD),
                    RecordingTransport.CONNECTIONS.get(0));
        }

        @Test
        @DisplayName("保存与重建的全过程，本程序自己的日志（不靠遮蔽）与回包里都搜不到口令原文")
        void passwordNeverReachesTheLog() throws Exception {
            startMail(false);
            String echoed;
            try (Captured log = new Captured("org.frostnova.nova")) {
                JSONObject first = save(Map.of("spring.mail.host", "smtp.example.invalid", "spring.mail.port", "587",
                        "spring.mail.username", "bot@example.com", "spring.mail.password", PASSWORD), 200);
                JSONObject second = save(Map.of("spring.mail.password", NEXT_PASSWORD), 200);
                mail.sendAlertMail("测一封", "正文");
                // 端口写错的那一批被拒时，回话与日志里同样不带口令
                JSONObject rejected = save(Map.of("spring.mail.port", "七", "spring.mail.password", "fixture-smtp-secret-3Zm"), 400);
                echoed = first.toJSONString() + second.toJSONString() + rejected.toJSONString();
                String text = log.text();
                assertFalse(text.isEmpty(), "一行日志都没截到，下面的判断就是空的");
                for (String secret : List.of(PASSWORD, NEXT_PASSWORD, "fixture-smtp-secret-3Zm")) {
                    assertFalse(text.contains(secret), () -> "日志里出现了口令原文，出自 " + log.leakingLoggers(secret));
                    assertFalse(echoed.contains(secret), "回包里出现了口令原文");
                }
            }
            assertTrue(echoed.contains("spring.mail.port"), "被拒那一批要指出是哪一项");
        }

        @Test
        @DisplayName("连框架与发信库的调试日志全开，装上运行中那道遮蔽后，整条链路的日志里也搜不到口令原文")
        void passwordNeverReachesAnyLogWithTheRunningMask() throws Exception {
            startMail(true);
            try (Captured log = new Captured(Logger.ROOT_LOGGER_NAME, "org.frostnova.nova", "org.springframework",
                    "jakarta.mail", "org.eclipse.angus")) {
                save(Map.of("spring.mail.host", "smtp.example.invalid", "spring.mail.port", "587",
                        "spring.mail.username", "bot@example.com", "spring.mail.password", PASSWORD), 200);
                save(Map.of("spring.mail.password", NEXT_PASSWORD), 200);
                mail.sendAlertMail("测一封", "正文");
                String text = log.text();
                assertTrue(text.contains("spring.mail.password"), "截到的日志里该有这一批请求，否则下面的判断是空的");
                for (String secret : List.of(PASSWORD, NEXT_PASSWORD)) {
                    assertFalse(text.contains(secret), () -> "日志里出现了口令原文，出自 " + log.leakingLoggers(secret));
                }
            }
        }

        @Test
        @DisplayName("端口写错整批不收：配置文件不动，发信器照旧")
        void badPortIsRejected() throws Exception {
            startMail(false);
            String before = Files.readString(config);
            for (String port : List.of("0", "70000", "", "smtp")) {
                JSONObject rejected = save(Map.of("spring.mail.host", "smtp.example.invalid", "spring.mail.port", port), 400);
                assertTrue(rejected.getString("message").contains("spring.mail.port"), rejected::toJSONString);
            }
            assertEquals(before, Files.readString(config));
            assertThrows(IllegalStateException.class, () -> mail.sendAlertMail("仍未配置", "正文"));
        }
    }

    // ================================================================ 3 来源 IP 名单

    @Nested
    @DisplayName("控制台来源 IP 名单")
    class AllowIps {
        private static final String KEY = "novabot.core.config-ui.allow-ips";

        @Test
        @DisplayName("名单里加上一个网段：从下一个请求起就放这个网段进来")
        void newListAppliesToTheNextRequest() throws Exception {
            start(beans -> { });
            ConfigUiSecurityFilter filter = filter();
            assertTrue(blockedByAllowList(filter, "192.168.1.20", null), "改之前这个地址进不来");

            save(Map.of(KEY, "127.0.0.1/32\n::1/128\n 192.168.1.0/24 "), 200);

            assertFalse(blockedByAllowList(filter, "192.168.1.20", null), "改之后下一个请求就该放行");
            assertTrue(blockedByAllowList(filter, "10.0.0.5", null), "不在名单里的照旧挡住");
            assertEquals(List.of("127.0.0.1/32", "::1/128", "192.168.1.0/24"), boundFromFile().getConfigUi().getAllowIps());
        }

        @Test
        @DisplayName("管理员保存的新名单把自己挡在外面：拒绝保存，旧名单照用")
        void listThatLocksOutTheSaverIsRejected() throws Exception {
            properties.getConfigUi().setAllowIps(List.of("127.0.0.1/32", "192.168.1.0/24"));
            start(beans -> { });
            ConfigUiSecurityFilter filter = filter();
            String before = Files.readString(config);

            JSONObject rejected = save(Map.of(KEY, "127.0.0.1/32"), 400, "192.168.1.20", null);

            assertTrue(rejected.getString("message").contains("这样保存后你会被挡在控制台外面"), rejected::toJSONString);
            assertEquals(before, Files.readString(config), "配置文件不动");
            assertEquals(List.of("127.0.0.1/32", "192.168.1.0/24"), properties.getConfigUi().getAllowIps());
            assertFalse(blockedByAllowList(filter, "192.168.1.20", null), "旧名单照用，这个人还进得来");
        }

        @Test
        @DisplayName("开了反向代理那一套时，自挡判断看的是与过滤器同一个改写后的来源")
        void selfLockoutFollowsTheProxyRulesOfTheFilter() throws Exception {
            properties.getConfigUi().setAllowIps(List.of("127.0.0.1/32", "203.0.113.0/24"));
            start(beans -> { });

            // 对端是本机的反向代理，真实来源 203.0.113.9：新名单只留本机，过滤器会把这个人挡在外面
            JSONObject rejected = save(Map.of(KEY, "127.0.0.1/32"), 400, "127.0.0.1", "203.0.113.9");
            assertTrue(rejected.getString("message").contains("203.0.113.9"), rejected::toJSONString);

            // 同一个名单、同一个请求交给过滤器（前面同样是框架的代理头过滤器），它确实挡人
            properties.getConfigUi().setAllowIps(List.of("127.0.0.1/32"));
            assertTrue(blockedByAllowList(filter(), "127.0.0.1", "203.0.113.9"));
            // 反过来：新名单含真实来源时收下，过滤器也放行
            properties.getConfigUi().setAllowIps(List.of("127.0.0.1/32", "203.0.113.0/24"));
            save(Map.of(KEY, "203.0.113.0/24"), 200, "127.0.0.1", "203.0.113.9");
            assertFalse(blockedByAllowList(filter(), "127.0.0.1", "203.0.113.9"));
        }

        @Test
        @DisplayName("名单里有一条写错：拒绝保存并指出是哪一条，旧名单照用")
        void mistypedEntryIsRejected() throws Exception {
            start(beans -> { });
            String before = Files.readString(config);

            for (String wrong : List.of("10.0.0.300", "192.168.1.0/40", "office-pc", "10.0.0.0/abc")) {
                JSONObject rejected = save(Map.of(KEY, "127.0.0.1/32\n" + wrong), 400);
                assertTrue(rejected.getString("message").contains("「" + wrong + "」"), rejected::toJSONString);
            }
            assertEquals(before, Files.readString(config));
            assertEquals(List.of("127.0.0.1/32", "::1/128"), properties.getConfigUi().getAllowIps());
        }

        private ConfigUiSecurityFilter filter() {
            ConfigUiAuthService auth = mock(ConfigUiAuthService.class);
            when(auth.isEnabled()).thenReturn(false);
            return registrar().configUiSecurityFilterRegistration(auth, mock(ConfigUiApiInterceptor.class)).getFilter();
        }

        /**
         * 这个请求是不是被来源 IP 名单挡下的（挡在后面令牌那一道的不算）
         */
        private boolean blockedByAllowList(ConfigUiSecurityFilter filter, String remote, String forwardedFor)
                throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", ConfigUiController.BASE_PATH + "/api/status");
            request.setRequestURI(ConfigUiController.BASE_PATH + "/api/status");
            request.setRemoteAddr(remote);
            if (forwardedFor != null) {
                request.addHeader("X-Forwarded-For", forwardedFor);
            }
            MockHttpServletResponse response = new MockHttpServletResponse();
            List<Object> reasons = new ArrayList<>();
            new ForwardedHeaderFilter().doFilter(request, response, (rewritten, out) -> {
                filter.doFilter(rewritten, out, (passed, ignored) -> { });
                reasons.add(rewritten.getAttribute(ConfigUiOperationFilter.REASON));
            });
            return "来源 IP 不在白名单内".equals(reasons.get(0));
        }
    }

    // ================================================================ 4 会话时长

    @Nested
    @DisplayName("登录会话时长")
    class Sessions {
        private final Instant loggedIn = Instant.parse("2026-11-01T08:00:00Z");

        @Test
        @DisplayName("有效期从 168 小时缩到 1 小时：已登录 90 分钟的会话，下一个请求当场被拒")
        void shorterLifetimeEndsOldSessions() throws Exception {
            start(beans -> { });
            ConfigUiSessionStore store = registrar().configUiSessionStore();
            ConfigUiSession session = store.issue("127.0.0.1", loggedIn, ConfigUiSession.Channel.PASSWORD);
            assertTrue(store.validate(session.getId(), loggedIn.plus(Duration.ofMinutes(90))).isPresent());

            save(Map.of("novabot.core.config-ui.auth.session-hours", "1"), 200);

            assertTrue(store.validate(session.getId(), loggedIn.plus(Duration.ofMinutes(91))).isEmpty(),
                    "按新有效期它已经过期了");
        }

        @Test
        @DisplayName("闲置时长从 12 小时缩到 1 小时：闲了 61 分钟的会话下一个请求被拒，闲了 30 分钟的照常")
        void shorterIdleEndsIdleSessions() throws Exception {
            start(beans -> { });
            ConfigUiSessionStore store = registrar().configUiSessionStore();
            ConfigUiSession idle = store.issue("127.0.0.1", loggedIn, ConfigUiSession.Channel.PASSWORD);
            ConfigUiSession busy = store.issue("127.0.0.1", loggedIn, ConfigUiSession.Channel.PASSWORD);
            Instant lastUse = loggedIn.plus(Duration.ofHours(3));
            assertTrue(store.validate(idle.getId(), lastUse).isPresent());
            assertTrue(store.validate(busy.getId(), lastUse.plus(Duration.ofMinutes(31))).isPresent());

            save(Map.of("novabot.core.config-ui.auth.idle-hours", "1"), 200);

            assertTrue(store.validate(idle.getId(), lastUse.plus(Duration.ofMinutes(61))).isEmpty());
            assertTrue(store.validate(busy.getId(), lastUse.plus(Duration.ofMinutes(61))).isPresent());
        }

        @Test
        @DisplayName("0 与负数不收，旧时长照用")
        void nonPositiveIsRejected() throws Exception {
            start(beans -> { });
            save(Map.of("novabot.core.config-ui.auth.session-hours", "0"), 400);
            save(Map.of("novabot.core.config-ui.auth.idle-hours", "-2"), 400);
            assertEquals(168, properties.getConfigUi().getAuth().getSessionHours());
            assertEquals(12, properties.getConfigUi().getAuth().getIdleHours());
        }
    }

    // ================================================================ 5 登录锁定

    @Nested
    @DisplayName("登录失败锁定")
    class Lockout {
        private final Instant now = Instant.parse("2026-11-01T08:00:00Z");

        @Test
        @DisplayName("锁定次数从 5 改成 3：已经错了两次的，第 3 次就锁；锁定时长按新的 1 分钟")
        void smallerThresholdLocksAtTheNewCount() throws Exception {
            start(beans -> { });
            LoginThrottle throttle = registrar().configUiLoginThrottle();
            throttle.recordFailure("10.0.0.2", now);
            throttle.recordFailure("10.0.0.2", now);

            save(Map.of("novabot.core.config-ui.auth.max-failures", "3",
                    "novabot.core.config-ui.auth.lockout-minutes", "1"), 200);
            throttle.recordFailure("10.0.0.2", now.plusSeconds(1));

            assertEquals(Duration.ofMinutes(1), throttle.remainingLockout("10.0.0.2", now.plusSeconds(1)),
                    "保存前的两次照算，第 3 次就该锁，锁 1 分钟");
        }

        @Test
        @DisplayName("正在锁定中的不因保存解锁")
        void ongoingLockoutSurvivesTheSave() throws Exception {
            start(beans -> { });
            LoginThrottle throttle = registrar().configUiLoginThrottle();
            for (int i = 0; i < 5; i++) {
                throttle.recordFailure("10.0.0.1", now);
            }
            assertEquals(Duration.ofMinutes(15), throttle.remainingLockout("10.0.0.1", now));

            save(Map.of("novabot.core.config-ui.auth.max-failures", "10",
                    "novabot.core.config-ui.auth.lockout-minutes", "1"), 200);

            assertEquals(Duration.ofMinutes(14), throttle.remainingLockout("10.0.0.1", now.plus(Duration.ofMinutes(1))));
        }
    }

    // ================================================================ 7 直播数据保存间隔

    @Nested
    @DisplayName("直播数据保存间隔")
    class AutoSave {
        @Test
        @DisplayName("间隔从 1 小时改成 1 秒：保存后不重启，一两秒内就写盘，运行状态也跟着写")
        void shorterIntervalWritesSoon() throws Exception {
            properties.getLive().setAutoSaveLiveDataInterval(3600);
            DefaultLiveDataService live =
                    new DefaultLiveDataService(properties);
            NovaStateStore state = new NovaStateStore(properties);
            start(beans -> {
                beans.registerBean(DefaultLiveDataService.class, () -> live);
                beans.registerBean(NovaStateStore.class, () -> state);
                beans.registerBean(AutoSaveIntervalListener.class);
            });
            Files.createDirectories(Path.of(properties.getLive().getLiveDataPath()).getParent());
            live.onApplicationReadyEvent();
            state.onApplicationReadyEvent();
            // 运行状态从没写过东西时不落盘，先记一项
            state.write("Demo", json -> json.put("seen", true));
            Path liveFile = Path.of(properties.getLive().getLiveDataPath());
            Path stateFile = liveFile.resolveSibling("state.json");
            assertFalse(Files.exists(liveFile), "起步时还没写过盘，否则下面量不出区别");

            save(Map.of("novabot.core.live.auto-save-live-data-interval", "1"), 200);

            await("按新间隔写下直播数据", () -> Files.exists(liveFile));
            await("按新间隔写下运行状态", () -> Files.exists(stateFile));
        }

        @Test
        @DisplayName("重排时正在写的那一趟照常写完、不被打断；新排的等它写完才轮到，两趟不同时写")
        void reschedulingNeitherDropsNorOverlapsAWrite() throws Exception {
            properties.getLive().setAutoSaveLiveDataInterval(1);
            AtomicBoolean hold = new AtomicBoolean();
            AtomicInteger entered = new AtomicInteger();
            AtomicInteger inFlight = new AtomicInteger();
            AtomicInteger mostAtOnce = new AtomicInteger();
            AtomicBoolean interrupted = new AtomicBoolean();
            CountDownLatch release = new CountDownLatch(1);
            AbstractDataSource source = mock(AbstractDataSource.class);
            // 取主播名单是写盘的头一步：在这里卡住，就是一趟写到一半
            when(source.getAllUsers()).thenAnswer(invocation -> {
                mostAtOnce.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                try {
                    // 只卡住头一趟：后面的若在它写完之前就进来了，就是两趟同时写
                    if (hold.get() && entered.incrementAndGet() == 1) {
                        release.await(20, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                } finally {
                    inFlight.decrementAndGet();
                }
                return List.of();
            });
            @SuppressWarnings("unchecked")
            ObjectProvider<AbstractDataSource> sources = mock(ObjectProvider.class);
            when(sources.getIfAvailable()).thenReturn(source);
            DefaultLiveDataService live = new DefaultLiveDataService(properties, sources);
            start(beans -> {
                beans.registerBean(DefaultLiveDataService.class, () -> live);
                beans.registerBean(AutoSaveIntervalListener.class);
            });
            Path liveFile = Path.of(properties.getLive().getLiveDataPath());
            Files.createDirectories(liveFile.getParent());
            live.onApplicationReadyEvent();
            live.onDataSourceLoaded();
            hold.set(true);
            await("一趟定时存盘写到一半", () -> entered.get() == 1);
            String before = Files.readString(liveFile);

            save(Map.of("novabot.core.live.auto-save-live-data-interval", "1"), 200);
            // 新间隔是 1 秒。头一趟再卡 3 秒：这期间新排的那一趟若不等它就开写，这里就看得见
            boolean overlapped = within(Duration.ofSeconds(3), () -> entered.get() >= 2);
            release.countDown();

            await("写到一半的那一趟写完落盘", () -> !read(liveFile).equals(before));
            await("按新间隔排的下一趟也来了", () -> entered.get() >= 2);
            assertFalse(interrupted.get(), "重排把写到一半的那一趟打断了");
            assertFalse(overlapped, "头一趟还没写完，新排的那一趟就开写了");
            assertEquals(1, mostAtOnce.get(), "两趟写盘同时进行了");
        }

        @Test
        @DisplayName("0 与负数不收：定时器排不了，文件里写进去重启就起不来")
        void nonPositiveIsRejected() throws Exception {
            start(beans -> { });
            save(Map.of("novabot.core.live.auto-save-live-data-interval", "0"), 400);
            assertEquals(300, properties.getLive().getAutoSaveLiveDataInterval());
        }

        private static String read(Path file) {
            try {
                return Files.readString(file);
            } catch (java.io.IOException e) {
                return "";
            }
        }
    }

    // ================================================================ 8 告警重发间隔

    @Nested
    @DisplayName("告警失败重发间隔")
    class AlertRetry {
        @Test
        @DisplayName("间隔从 1 小时改成 1 秒：排队中的告警一条不丢，按新间隔重发出去")
        void queuedAlertsGoOutAtTheNewInterval() throws Exception {
            properties.getAlert().setRetryInterval(3600);
            FlakyChannel channel = new FlakyChannel();
            @SuppressWarnings("unchecked")
            ObjectProvider<AlertChannel> channels = mock(ObjectProvider.class);
            when(channels.orderedStream()).thenAnswer(invocation -> Stream.of(channel));
            AlertService alerts = new AlertService(properties, channels, TimelineWriter.NONE);
            start(beans -> beans.registerBean(AlertService.class, () -> alerts));
            alerts.onApplicationReadyEvent();
            try {
                channel.failing = true;
                alerts.alert("disk", "磁盘将满", "剩余 100 MB");
                alerts.alert("login", "登录失效", "要重新扫码");
                assertEquals(2, alerts.pendingCount());
                channel.failing = false;

                save(Map.of("novabot.core.alert.retry-interval", "1"), 200);

                await("两条都按新间隔重发出去", () -> channel.delivered.size() == 2);
                assertEquals(java.util.Set.of("磁盘将满", "登录失效"), java.util.Set.copyOf(channel.delivered));
                assertEquals(0, alerts.pendingCount());
            } finally {
                alerts.onContextClosedEvent();
            }
        }

        @Test
        @DisplayName("改成 0 只停排，队列里的不丢；负数不收")
        void zeroStopsWithoutDroppingTheQueue() throws Exception {
            FlakyChannel channel = new FlakyChannel();
            @SuppressWarnings("unchecked")
            ObjectProvider<AlertChannel> channels = mock(ObjectProvider.class);
            when(channels.orderedStream()).thenAnswer(invocation -> Stream.of(channel));
            AlertService alerts = new AlertService(properties, channels, TimelineWriter.NONE);
            start(beans -> beans.registerBean(AlertService.class, () -> alerts));
            alerts.onApplicationReadyEvent();
            try {
                channel.failing = true;
                alerts.alert("disk", "磁盘将满", "剩余 100 MB");

                save(Map.of("novabot.core.alert.retry-interval", "0"), 200);
                save(Map.of("novabot.core.alert.retry-interval", "-1"), 400);

                assertEquals(1, alerts.pendingCount(), "停排不等于清队列");
                assertEquals(0, properties.getAlert().getRetryInterval());
            } finally {
                alerts.onContextClosedEvent();
            }
        }
    }

    // ================================================================ 9 去首尾空白

    @Test
    @DisplayName("Webhook 请求方式填「 get 」：文件里写的也是去掉空白的 get，重启后仍按 GET 发")
    void trimmedValueIsWhatTheFileGets() throws Exception {
        start(beans -> { });

        save(Map.of("novabot.core.alert.webhook-method", " get ",
                "novabot.core.alert.webhook-title-field", " text "), 200);

        assertTrue(properties.getAlert().webhookFormat().get(), "当场按 GET");
        NovaCoreProperties restarted = boundFromFile();
        assertEquals("get", restarted.getAlert().getWebhookMethod(), "文件里不留空白");
        assertTrue(restarted.getAlert().webhookFormat().get(), "重启后按文件读，仍是 GET");
        assertEquals("text", restarted.getAlert().getWebhookTitleField());
        assertEquals(properties.getAlert().getWebhookTitleField(), restarted.getAlert().getWebhookTitleField());
    }

    // ================================================================ 11 事件输出只读令牌

    @Test
    @DisplayName("「事件输出要求只读访问令牌」不在设置页上：填 false 也按 true 运行，摆出来只会让人以为关得掉")
    void requireTokenIsNotOnTheSettingsPage() throws Exception {
        Files.writeString(config, "novabot:\n  core:\n    event-stream:\n      require-token: false\n");
        start(beans -> { });

        JSONObject schema = settings.schema();
        List<String> names = new ArrayList<>();
        schema.getList("groups", JSONObject.class).forEach(group ->
                group.getList("fields", JSONObject.class).forEach(field -> names.add(field.getString("name"))));
        schema.getList("ungrouped", JSONObject.class).forEach(field -> names.add(field.getString("name")));

        assertTrue(names.contains("novabot.core.event-stream.allowed-origins"), "同一组的其他项还在，否则下面量的是空集");
        assertFalse(names.contains("novabot.core.event-stream.require-token"));
        // 配置文件里写着它照旧读得下来
        var source = new MapConfigurationPropertySource(new ConfigurationFileService(config).readAsLoaded());
        var bound = new Binder(source).bind(org.frostnova.nova.core.properties.EventStreamProperties.PREFIX,
                org.frostnova.nova.core.properties.EventStreamProperties.class);
        assertTrue(bound.isBound(), "写着这个键的配置文件照旧读得下来");
    }

    // ================================================================ 10 新版检查

    @Nested
    @DisplayName("检查新版本")
    class UpdateCheck {
        private final List<String> fetched = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch release = new CountDownLatch(1);
        private UpdateCheckService updates;

        @BeforeEach
        void startUpdates() {
            properties.getConfigUi().getUpdate().setEnabled(false);
            Properties build = new Properties();
            build.setProperty("version", "6.1.1");
            DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
            factory.registerSingleton("build", new BuildProperties(build));
            updates = new UpdateCheckService(properties, new NovaStateStore(properties),
                    factory.getBeanProvider(BuildProperties.class), url -> {
                fetched.add(url);
                // 来源很慢：保存不该等它
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                JSONObject answer = new JSONObject();
                answer.put("tag_name", "v99.0.0");
                answer.put("body", "新版说明");
                answer.put("html_url", "https://example.invalid/releases/v99.0.0");
                return answer;
            });
            start(beans -> beans.registerBean(UpdateCheckService.class, () -> updates));
        }

        @AfterEach
        void stopUpdates() {
            release.countDown();
            updates.onContextClosedEvent();
        }

        @Test
        @DisplayName("打开检查：保存当场返回，后台立刻查一次，不等第二天")
        void enablingChecksRightAway() throws Exception {
            save(Map.of("novabot.core.config-ui.update.enabled", "true"), 200);
            assertTrue(updates.pendingUpdate().isEmpty(), "来源还没答复，保存已经返回了");

            await("后台去问来源", () -> !fetched.isEmpty());
            release.countDown();
            await("查到的新版出现在提醒里", () -> updates.pendingUpdate().isPresent());
            assertEquals("v99.0.0", updates.pendingUpdate().orElseThrow().version());
        }

        @Test
        @DisplayName("换来源：保存后立刻按新来源查一次；写成不是网址的不收")
        void changingSourceChecksTheNewSource() throws Exception {
            properties.getConfigUi().getUpdate().setEnabled(true);
            release.countDown();

            save(Map.of("novabot.core.config-ui.update.source", "https://mirror.example.invalid/releases/latest"), 200);

            await("按新来源去问", () -> fetched.contains("https://mirror.example.invalid/releases/latest"));
            save(Map.of("novabot.core.config-ui.update.source", "mirror.example.invalid"), 400);
        }
    }

    // ================================================================ 工具

    private NovaCoreProperties boundFromFile() throws Exception {
        var source = new MapConfigurationPropertySource(new ConfigurationFileService(config).readAsLoaded());
        return new Binder(source).bind("novabot.core", NovaCoreProperties.class).orElseGet(NovaCoreProperties::new);
    }

    /**
     * 先失败、后恢复的告警通道
     */
    private static final class FlakyChannel implements AlertChannel {
        volatile boolean failing;
        final List<String> delivered = Collections.synchronizedList(new ArrayList<>());

        @Override public String id() { return "flaky"; }
        @Override public String name() { return "时好时坏"; }
        @Override public boolean isAvailable() { return true; }

        @Override
        public void send(String subject, String content) {
            if (failing) {
                throw new IllegalStateException("连不上");
            }
            delivered.add(subject.replace("【补发】", "").strip());
        }
    }

    /**
     * 截下这段时间里全部的日志：消息、参数与异常全文都算
     */
    private static final class Captured implements AutoCloseable {
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        private final List<Logger> loggers = new ArrayList<>();
        private final List<Level> levels = new ArrayList<>();

        /**
         * @param names 截哪几棵记录器，各自调到 TRACE
         */
        Captured(String... names) {
            LoggerContext logs = (LoggerContext) LoggerFactory.getILoggerFactory();
            appender.setContext(logs);
            appender.start();
            for (String name : names) {
                Logger logger = logs.getLogger(name);
                loggers.add(logger);
                levels.add(logger.getLevel());
                logger.setLevel(Level.TRACE);
                logger.addAppender(appender);
            }
        }

        /**
         * 哪几个记录器、哪一级的日志里带着这段字：只报出处，不印原文
         */
        List<String> leakingLoggers(String secret) {
            return List.copyOf(appender.list).stream()
                    .filter(event -> textOf(event).contains(secret))
                    .map(event -> event.getLoggerName() + "/" + event.getLevel())
                    .distinct().toList();
        }

        private static String textOf(ILoggingEvent event) {
            StringBuilder text = new StringBuilder();
            appendTo(text, event);
            return text.toString();
        }

        String text() {
            StringBuilder text = new StringBuilder();
            for (ILoggingEvent event : List.copyOf(appender.list)) {
                appendTo(text, event);
            }
            return text.toString();
        }

        private static void appendTo(StringBuilder text, ILoggingEvent event) {
            {
                text.append(event.getFormattedMessage()).append('\n');
                if (event.getArgumentArray() != null) {
                    for (Object argument : event.getArgumentArray()) {
                        text.append(argument).append('\n');
                    }
                }
                if (event.getThrowableProxy() != null) {
                    text.append(ThrowableProxyUtil.asString(event.getThrowableProxy())).append('\n');
                }
            }
        }

        @Override
        public void close() {
            for (int i = 0; i < loggers.size(); i++) {
                loggers.get(i).detachAppender(appender);
                loggers.get(i).setLevel(levels.get(i));
            }
        }
    }
}
