package org.frostnova.nova.adapter.onebot.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.frostnova.nova.adapter.onebot.config.OneBotAdapterPluginProperties;
import org.frostnova.nova.adapter.onebot.converter.OneBotMessageConverter;
import org.frostnova.nova.adapter.onebot.health.OneBotConnectionState;
import org.frostnova.nova.adapter.onebot.http.OneBotHttpAdapter;
import org.frostnova.nova.adapter.onebot.model.OneBotSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadFactory;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 两条「连接地址」日志行跟着 one-bot-address 里的协议头走
 *
 * <h2>抓的用户故障</h2>
 * 配了 {@code https://主机} 的机器人，连接地址行里却写着 {@code http://}——
 * 排障的人照着日志去核对明文端口，白查一轮。这两行是「实际连的到底是哪个地址」
 * 在工程日志里唯一的落点，拼错了它们，日志就成了误导。
 *
 * <h2>为什么连 Websocket 也看日志行</h2>
 * 连接地址行印出的就是拿去 {@code URI.create} 的那一个字符串：这一行对了，
 * 实际连接的地址就对了。这里不去真连一个 TLS 服务——量的是拼地址，不是握手。
 */
@DisplayName("连接地址日志行跟着协议头走")
class OneBotEndpointAddressLogTest {

    private static final String ASSEMBLY_LOGGER_NAME =
            "org.frostnova.nova.adapter.onebot.config.OneBotEndpointAddresses";

    /** 连接地址行在第一轮连接尝试开头就打，等它几秒够慢机器用的 */
    private static final Duration PATIENCE = Duration.ofSeconds(10);

    private ThreadPoolTaskScheduler scheduler;

    private ThreadPoolTaskExecutor executor;

    private OneBotWebsocketService websocketService;

    private ListAppender<ILoggingEvent> websocketLog;

    private ch.qos.logback.classic.Logger websocketLogger;

    private Level originalWebsocketLevel;

    private ListAppender<ILoggingEvent> assemblyLog;

    private ch.qos.logback.classic.Logger assemblyLogger;

    @BeforeEach
    void setUp() {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadFactory(daemonThreads("test-onebot-address-detect"));
        scheduler.initialize();

        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        // 连不上时的退避睡在池里的线程上，守护线程才不会把测试 JVM 的收尾拖住
        executor.setThreadFactory(daemonThreads("test-onebot-address-retry"));
        executor.initialize();

        websocketService = new OneBotWebsocketService(scheduler, executor, quietProperties(),
                mock(OneBotHttpService.class), new OneBotConnectionState(), mock(ApplicationEventPublisher.class));

        websocketLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(OneBotWebsocketService.class);
        originalWebsocketLevel = websocketLogger.getLevel();
        websocketLogger.setLevel(Level.INFO);
        websocketLog = new ListAppender<>();
        websocketLog.start();
        websocketLogger.addAppender(websocketLog);

        assemblyLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ASSEMBLY_LOGGER_NAME);
        assemblyLog = new ListAppender<>();
        assemblyLog.start();
        assemblyLogger.addAppender(assemblyLog);
    }

    @AfterEach
    void tearDown() {
        websocketLogger.detachAppender(websocketLog);
        websocketLog.stop();
        websocketLogger.setLevel(originalWebsocketLevel);
        assemblyLogger.detachAppender(assemblyLog);
        assemblyLog.stop();
        executor.shutdown();
        scheduler.shutdown();
    }

    @Test
    @DisplayName("Websocket 连接地址行：各写法拼出的地址，带路径／端口／别的协议头时还有那句错")
    void websocketAddressLineFollowsTheScheme() throws Exception {
        int port = sparePort();
        websocketAddressLineSays("只写主机", "127.0.0.1", "ws://127.0.0.1:" + port + "/", false, port);
        websocketAddressLineSays("http头", "http://127.0.0.1", "ws://127.0.0.1:" + port + "/", false, port);
        websocketAddressLineSays("https头", "https://127.0.0.1", "wss://127.0.0.1:" + port + "/", false, port);
        websocketAddressLineSays("ws头", "ws://127.0.0.1", "ws://127.0.0.1:" + port + "/", false, port);
        websocketAddressLineSays("wss头", "wss://127.0.0.1", "wss://127.0.0.1:" + port + "/", false, port);
        websocketAddressLineSays("大写头", "WSS://127.0.0.1", "wss://127.0.0.1:" + port + "/", false, port);
        websocketAddressLineSays("带路径", "127.0.0.1/onebot", "ws://127.0.0.1/onebot:" + port + "/", true, port);
        websocketAddressLineSays("带端口", "127.0.0.1:3000", "ws://127.0.0.1:3000:" + port + "/", true, port);
        websocketAddressLineSays("别的协议头", "ftp://127.0.0.1", "ws://ftp://127.0.0.1:" + port + "/", true, port);
    }

    @Test
    @DisplayName("HTTP 连接地址行：https://主机 与 wss://主机 都显示成 https")
    void httpAddressLineFollowsTheScheme() {
        ListAppender<ILoggingEvent> httpLog = new ListAppender<>();
        httpLog.start();
        ch.qos.logback.classic.Logger httpLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(OneBotHttpService.class);
        Level original = httpLogger.getLevel();
        httpLogger.addAppender(httpLog);
        try {
            OneBotHttpService httpService = new OneBotHttpService(scheduler, executor, quietProperties(),
                    mock(OneBotHttpAdapter.class), mock(OneBotMessageConverter.class), new OneBotConnectionState());

            httpService.check(senderTo("HTTP行-https头", "https://a.example.internal", 3000));
            httpService.check(senderTo("HTTP行-wss头", "wss://b.example.internal", 3000));

            assertTrue(contains(httpLog, "连接地址: https://a.example.internal:3000"),
                    "https://主机 的 HTTP 连接地址行该是 https，实际: " + formatted(httpLog));
            assertTrue(contains(httpLog, "连接地址: https://b.example.internal:3000"),
                    "wss://主机 的 HTTP 该一起走加密，实际: " + formatted(httpLog));
        } finally {
            httpLogger.detachAppender(httpLog);
            httpLog.stop();
            httpLogger.setLevel(original);
        }
    }

    /**
     * 按这个写法连一次，等连接地址行打出来。{@code expectsError} 的写法还要等到那句
     * 「该怎么写」的错——它点名机器人、带上原值，排障的人照着改就行。
     */
    private void websocketAddressLineSays(String tag, String address, String expected, boolean expectsError, int port) {
        String senderName = "地址机器人-" + tag;
        // 前一种写法的连接线程可能还挂着没退场，先把两份日志清空，等到的才是这一种写法的
        clear(websocketLog);
        clear(assemblyLog);
        websocketService.start(senderTo(senderName, address, port));
        try {
            assertTrue(awaitLine(websocketLog, "连接地址: " + expected, null),
                    tag + "：连接地址行该打出 " + expected + ", 实际: " + formatted(websocketLog));
            if (expectsError) {
                assertTrue(awaitLine(assemblyLog, senderName, Level.WARN),
                        tag + "：该打一句点名机器人（" + senderName + "）的错, 实际: " + formatted(assemblyLog));
                assertTrue(contains(assemblyLog, address), "那句错里要带原值 " + address + ": " + formatted(assemblyLog));
                assertTrue(contains(assemblyLog, "https://"), "那句错要说清加密时该怎么写: " + formatted(assemblyLog));
            }
        } finally {
            websocketService.stop(senderName);
        }
    }

    private static OneBotSender senderTo(String name, String address, int websocketPort) {
        OneBotSender sender = new OneBotSender();
        sender.setName(name);
        sender.setWebsocket(true);
        sender.setOneBotAddress(address);
        sender.setOneBotHttpPort(3000);
        sender.setOneBotWebsocketPort(websocketPort);
        sender.setOneBotWebsocketToken("ws-token");
        return sender;
    }

    /**
     * 空出来的端口：先占一次拿到号，随即放掉，好让连接这一段确实「连不上」而不是连上别的东西
     */
    private static int sparePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static ThreadFactory daemonThreads(String name) {
        return task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * 定期检测只会往连接状态里写噪声，这里量的是日志行
     */
    private static OneBotAdapterPluginProperties quietProperties() {
        OneBotAdapterPluginProperties properties = new OneBotAdapterPluginProperties();
        properties.getDetect().setEnableHttpDetect(false);
        properties.getDetect().setEnableWebsocketDetect(false);
        return properties;
    }

    private void awaitUntil(String what, BooleanSupplier condition) {
        java.time.Instant deadline = java.time.Instant.now().plus(PATIENCE);
        while (java.time.Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        assertTrue(condition.getAsBoolean(), what + "：等了 " + PATIENCE.toSeconds() + " 秒仍不成立");
    }

    private boolean awaitLine(ListAppender<ILoggingEvent> appender, String fragment, Level level) {
        awaitUntil("等日志行「" + fragment + "」", () -> contains(appender, fragment, level));
        return true;
    }

    private static boolean contains(ListAppender<ILoggingEvent> appender, String fragment) {
        return contains(appender, fragment, null);
    }

    private static boolean contains(ListAppender<ILoggingEvent> appender, String fragment, Level level) {
        synchronized (appender) {
            return appender.list.stream()
                    .filter(e -> level == null || e.getLevel() == level)
                    .map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(m -> m.contains(fragment));
        }
    }

    private static List<String> formatted(ListAppender<ILoggingEvent> appender) {
        synchronized (appender) {
            List<String> lines = new ArrayList<>();
            for (ILoggingEvent event : appender.list) {
                lines.add(event.getLevel() + " " + event.getFormattedMessage());
            }
            return lines;
        }
    }

    private static void clear(ListAppender<ILoggingEvent> appender) {
        synchronized (appender) {
            appender.list.clear();
        }
    }
}
