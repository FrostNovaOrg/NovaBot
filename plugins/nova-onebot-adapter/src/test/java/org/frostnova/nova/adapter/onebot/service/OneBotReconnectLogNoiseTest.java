package org.frostnova.nova.adapter.onebot.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.frostnova.nova.adapter.onebot.config.OneBotAdapterPluginProperties;
import org.frostnova.nova.adapter.onebot.health.OneBotConnectionState;
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
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 机器人连不上的时候，工程日志要按量级合并
 * <p>
 * 退避封顶 60 秒之后，重连是每分钟一轮。每轮照打「准备连接」「连接地址」加一行失败，
 * 一小时就是一百八十多行同样的话；而排障真正要的只有「现在连不上」和「什么时候恢复的」。
 * <p>
 * 这把尺在真重连循环上量两头：连不上的那头不许按轮数重复，
 * 连上的那头要说得出中断了多久、重试了几次。两头都在真实的 {@code connect} 循环上走，
 * 不去数一个自己另写的重试表。
 */
@DisplayName("机器人重连日志按量级合并")
class OneBotReconnectLogNoiseTest {

    private static final String SENDER = "qq-onebot";

    /** 4 轮失败的退避睡是 2+4+8 秒，再留握手与调度余量 */
    private static final Duration PATIENCE = Duration.ofSeconds(30);

    /**
     * 等到第 4 轮开始之后，再停一小会儿才量。
     * <p>
     * 「准备连接」是每轮<b>开头</b>打的，第 4 轮开头那一行先到、它的失败行后到，
     * 量得赶早了会把「还没打」读成「打过又合掉了」。而第 4 轮失败之后要睡 8 秒才开第 5 轮，
     * 所以这一小会儿落在两头之间：既等到了第 4 轮的收场，又还没第 5 轮。
     */
    private static final long SETTLE_MILLIS = 700;

    private ThreadPoolTaskScheduler scheduler;

    private ThreadPoolTaskExecutor executor;

    private OneBotWebsocketService websocketService;

    private ListAppender<ILoggingEvent> appender;

    private ch.qos.logback.classic.Logger logger;

    private Level originalLevel;

    @BeforeEach
    void setUp() {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadFactory(daemonThreads("test-onebot-ws-detect"));
        scheduler.initialize();

        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        // 重连循环在退避里能睡上几十秒。它是池里的线程，池里的线程默认非守护——
        // 测完这一例它还在睡，整个测试 JVM 就跟着一起等。守护线程才不会把收尾拖住。
        executor.setThreadFactory(daemonThreads("test-onebot-ws-retry"));
        executor.initialize();

        OneBotAdapterPluginProperties properties = new OneBotAdapterPluginProperties();
        properties.getDetect().setEnableHttpDetect(false);
        properties.getDetect().setEnableWebsocketDetect(false);

        websocketService = new OneBotWebsocketService(scheduler, executor, properties,
                mock(OneBotHttpService.class), new OneBotConnectionState(),
                mock(ApplicationEventPublisher.class));

        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(OneBotWebsocketService.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        websocketService.stop(SENDER);
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(originalLevel);
        executor.shutdown();
        scheduler.shutdown();
    }

    private static OneBotSender senderTo(String address, int port) {
        OneBotSender sender = new OneBotSender();
        sender.setName(SENDER);
        sender.setWebsocket(true);
        sender.setOneBotAddress(address);
        sender.setOneBotWebsocketPort(port);
        sender.setOneBotWebsocketToken("ws-token");
        return sender;
    }

    private static java.util.concurrent.ThreadFactory daemonThreads(String name) {
        return task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * 空出来的端口：先占一次拿到号，随即放掉，好让这一段在接上之前确实是「连不上」
     */
    private static int sparePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
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

    /**
     * 重连线程还在往这份日志里追加。追加用的是收集器这把锁，先抄一份再逐条看。
     */
    private List<ILoggingEvent> loggedLines() {
        synchronized (appender) {
            return new ArrayList<>(appender.list);
        }
    }

    private long count(String fragment, Level level) {
        return loggedLines().stream()
                .filter(e -> level == null || e.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains(fragment))
                .count();
    }

    private long prepareLines() {
        return count("准备连接", null);
    }

    private long addressInfoLines() {
        return count("连接地址", Level.INFO);
    }

    /**
     * 失败行：超时 WARN 与不可用 ERROR 两类都是「这一轮没连上」
     */
    private long failureLines() {
        return loggedLines().stream()
                .filter(e -> e.getLevel() == Level.WARN || e.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("重试"))
                .count();
    }

    private long errorLinesWithStack() {
        return loggedLines().stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .filter(e -> e.getFormattedMessage().contains("不可用"))
                .filter(e -> e.getThrowableProxy() != null)
                .count();
    }

    @Test
    @DisplayName("判据自己先看得见每一轮的失败——不然下面几条只是空断言")
    void theRulerSeesEachFailedRound() {
        websocketService.start(senderTo("bad host", 39999));
        awaitUntil("等第一轮失败", () -> failureLines() >= 1);

        assertTrue(failureLines() >= 1, "连不上时失败行是排障的主线，必须打得出来");
        assertTrue(prepareLines() >= 1, "每轮的准备/地址行也该看得见，才谈得上合并");
    }

    @Test
    @DisplayName("连不上的一小时不许每分钟重复同样三行")
    void repeatedFailuresStopRepeatingTheSameLines() throws InterruptedException {
        websocketService.start(senderTo("bad host", 39999));
        awaitUntil("等 4 轮重连尝试", () -> prepareLines() >= 4);
        Thread.sleep(SETTLE_MILLIS);
        assertEquals(4, prepareLines(),
                "该停在第 4 轮收场之后、第 5 轮开头之前——少了这一句，下面几条可能量的是第 3 轮");

        assertEquals(1, addressInfoLines(),
                "连接地址只在这一轮第一次打；每轮都打就是刷屏, 实际 " + addressInfoLines() + " 行");
        assertEquals(0, count("准备连接", Level.INFO),
                "「准备连接」该降到 DEBUG，不占工程日志, 实际 " + count("准备连接", Level.INFO) + " 行");
        assertTrue(failureLines() >= 3, "前几轮照打——少了这条，0 行的假绿也能过");
        assertTrue(failureLines() <= 3,
                "4 轮失败时前几轮照打、之后按量级合并，第 4 轮不该再单独占一行, 实际 " + failureLines() + " 行");
        assertTrue(errorLinesWithStack() <= 1,
                "堆栈只在第一次带，后面每轮都带整段栈是刷屏的一半, 实际 " + errorLinesWithStack() + " 条");
    }

    @Test
    @DisplayName("恢复时要说得出中断了多久、重试了几次")
    void recoverySaysHowLongItWasDown() throws IOException {
        int port = sparePort();
        websocketService.start(senderTo("127.0.0.1", port));
        awaitUntil("等第一次连不上", () -> failureLines() >= 1);

        try (FakeOneBotWebsocketServer server = new FakeOneBotWebsocketServer(port)) {
            awaitUntil("等连上", () -> count("恢复", Level.INFO) >= 1);

            assertTrue(server.accepted() >= 1, "服务端这边要真的接过连接，否则「恢复」是凭空说的");

            String recovery = loggedLines().stream()
                    .filter(e -> e.getLevel() == Level.INFO)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.contains("恢复"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(recovery.contains("重试"),
                    "要说清是重试了几次之后恢复的: " + recovery);
        }
    }

    /**
     * 按量级合并那张表本身。
     * <p>
     * 上面几条量的是「四轮之后合起来是三行」，可合并口径是第 10、100、1000… 时报——
     * 那几格在四轮之内量不到，不钉在这里就成了没人验过的空话。这张表写死：
     * 前三轮照打，之后只有 10 的整数次幂才报，中间一律不报。
     */
    @Test
    @DisplayName("合并那张表：前三轮照打，之后只在 10、100、1000… 报")
    void failureLogScheduleIsPinned() {
        for (int round : new int[]{1, 2, 3}) {
            assertTrue(OneBotWebsocketService.shouldLogRetryFailure(round),
                    "前三轮照打——排障要先看得见连不上和它长什么样: 第 " + round + " 轮");
        }
        for (int round : new int[]{4, 5, 9, 11, 20, 99, 2000}) {
            assertFalse(OneBotWebsocketService.shouldLogRetryFailure(round),
                    "量级之间不报: 第 " + round + " 轮");
        }
        for (int round : new int[]{10, 100, 1000, 10000}) {
            assertTrue(OneBotWebsocketService.shouldLogRetryFailure(round),
                    "10 的整数次幂处报一声: 第 " + round + " 轮");
        }
        assertFalse(OneBotWebsocketService.shouldLogRetryFailure(0), "没失败过不报");
    }
}
