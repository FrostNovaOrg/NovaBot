package org.frostnova.nova.report.painter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.health.BilibiliRiskMetrics;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.UserScore;
import org.frostnova.nova.core.util.HttpUtil;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 取头像取不到时，「这次没取到」要不要记住
 * <p>
 * 「源站明说没有这张图」与「这次没取成」（连接超时、读超时、断线、别的出错）是两回事：
 * 前者再问也还是没有，记住它免得每次都重问；后者只是一趟没成，记上几小时就等于——
 * 源站卡的那几分钟里查过的人，接下来半天在排行榜上都没有头像。
 * <p>
 * 这里走真的 BilibiliApiUtil 与 HttpUtil，只把最底层的网络读换成假的：假在更上层的话，
 * 把出错接住回「没图」的那几层就被绕过去了，量到的不是线上那一趟。
 */
@DisplayName("排行榜头像下载：这次没取到要不要记住")
class BilibiliRankingAvatarRetryTest {
    private static final int PEOPLE = 50;

    private static final Color AVATAR_GREEN = new Color(40, 200, 40);

    private FakeNetwork network;

    private BilibiliDataQueryPainter painter;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void setUp() {
        NovaCoreProperties coreProperties = new NovaCoreProperties();
        coreProperties.getPaint().getFonts().add("内置");

        FontUtil fontUtil = new FontUtil(new DefaultResourceLoader(), coreProperties);
        fontUtil.init();

        Properties buildInfo = new Properties();
        buildInfo.setProperty("version", "4.0.0");
        buildInfo.setProperty("group", "com." + "starlwr");
        buildInfo.setProperty("artifact", "nova-core");
        buildInfo.setProperty("name", "NovaBot");

        NovaCommonPainterFactory factory =
                new NovaCommonPainterFactory(new BuildProperties(buildInfo), coreProperties, fontUtil);

        network = new FakeNetwork();
        BilibiliApiUtil api = new BilibiliApiUtil(network, new NovaBilibiliProperties(), new BilibiliRiskMetrics());
        painter = new BilibiliDataQueryPainter(factory, api);
    }

    @AfterEach
    void tearDown() {
        network.release();
    }

    @Test
    @DisplayName("读超时的不记住：源站好了下一张图照常取，日志 ERROR 0 条、WARN 至多 1 条")
    void sourceOutageIsNotRememberedForHours() throws Exception {
        network.readTimeout();
        List<UserScore> rows = rankingWithFaces(PEOPLE, 0);

        ListAppender<ILoggingEvent> logs = attachToApplicationLog();
        try {
            painter.paintRanking(header(), rows, 1, score -> Math.round(score) + " 条", footnote(PEOPLE)).orElseThrow();
            List<ILoggingEvent> firstPass = new ArrayList<>(logs.list);
            logs.list.clear();

            network.serve(pngOf(AVATAR_GREEN));
            int fetchesBefore = network.fetches();
            BufferedImage second = decode(painter.paintRanking(header(), rows, 1,
                    score -> Math.round(score) + " 条", footnote(PEOPLE)).orElseThrow());
            int refetched = network.fetches() - fetchesBefore;

            long errors = firstPass.stream().filter(event -> event.getLevel() == Level.ERROR).count();
            long warnings = firstPass.stream().filter(event -> event.getLevel() == Level.WARN).count();
            assertAll(
                    () -> assertTrue(refetched >= PEOPLE,
                            "第二张图重新去取的头像只有 " + refetched + " 张——源站卡过一阵之后，头像被记成接下来几小时都取不到"),
                    () -> assertTrue(countGreen(second) > 0,
                            "源站恢复后头像还是画不上——上一轮没取到的被记住了"),
                    () -> assertEquals(0, errors,
                            "源站卡住的那几秒不该给每张头像记一条带堆栈的 ERROR，实际 " + errors + " 条：" + messages(firstPass)),
                    () -> assertTrue(warnings <= 1,
                            "每出一张排行榜图至多一条 WARN 说明几张没取到，实际 " + warnings + " 条：" + messages(firstPass)));
        } finally {
            detach(logs);
        }
    }

    @Test
    @DisplayName("源站一直挂着：连出四张各 300 个头像的图，在飞表不跟着新地址一直涨")
    void inFlightTableStaysBoundedWhileSourceHangs() throws Exception {
        network.hangForever();

        for (int page = 0; page < 4; page++) {
            painter.paintRanking(header(), rankingWithFaces(300, page * 300), 1,
                    score -> Math.round(score) + " 条", footnote(300)).orElseThrow();
        }

        int inFlight = inFlightCount();
        int capacity = fetchCapacity();
        assertTrue(network.fetches() > 0, "假网络读一次都没被叫到，这一格什么都没量到");
        assertTrue(inFlight > 0, "在飞表是空的，这一格什么都没量到");
        assertTrue(inFlight <= capacity,
                "源站一直挂着时在飞表涨到 " + inFlight + " 条，超过线程数＋队列长 " + capacity
                        + "——排不进队的那条留在表里不清，这张表跟着新地址一直涨");
    }

    @Test
    @DisplayName("源站明说没这张图：记住，下一张图不再重取")
    void sourceSaysNotFoundIsRemembered() throws Exception {
        network.notFound();
        List<UserScore> rows = rankingWithFaces(PEOPLE, 0);

        painter.paintRanking(header(), rows, 1, score -> Math.round(score) + " 条", footnote(PEOPLE)).orElseThrow();
        int fetchesAfterFirst = network.fetches();

        network.serve(pngOf(AVATAR_GREEN));
        BufferedImage second = decode(painter.paintRanking(header(), rows, 1,
                score -> Math.round(score) + " 条", footnote(PEOPLE)).orElseThrow());

        assertEquals(fetchesAfterFirst, network.fetches(),
                "源站明说没有这张图还一趟趟重取，等于一直在问一个没有的答案");
        assertEquals(0, countGreen(second),
                "源站说没有的头像不该画出来");
    }

    /**
     * 只把最底层的网络读换成假的：往上的取图、解码、头像缓存全是真码
     */
    private static class FakeNetwork extends HttpUtil {
        private final AtomicInteger fetches = new AtomicInteger();

        private final AtomicBoolean hanging = new AtomicBoolean();

        private final AtomicBoolean abandoned = new AtomicBoolean();

        private final CountDownLatch released = new CountDownLatch(1);

        private final HttpClientErrorException.NotFound missing = sourceSaysNoImage();

        private volatile byte[] body;

        private volatile RuntimeException failure;

        FakeNetwork() {
            super(null, null, null);
        }

        @Override
        public byte[] getBytes(String url, Map<String, String> headers) {
            fetches.incrementAndGet();
            if (hanging.get()) {
                while (released.getCount() > 0 && !abandoned.get()) {
                    try {
                        released.await(20, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                throw new ResourceAccessException("连接被放弃");
            }
            if (failure != null) {
                throw failure;
            }
            return body;
        }

        void readTimeout() {
            hanging.set(false);
            body = null;
            failure = new ResourceAccessException("Read timed out");
        }

        void notFound() {
            hanging.set(false);
            body = null;
            failure = missing;
        }

        void serve(byte[] png) {
            hanging.set(false);
            failure = null;
            body = png;
        }

        void hangForever() {
            failure = null;
            body = null;
            hanging.set(true);
        }

        void release() {
            abandoned.set(true);
            released.countDown();
        }

        int fetches() {
            return fetches.get();
        }

        /**
         * 源站回 HTTP 404＝明说没有这张图
         */
        private static HttpClientErrorException.NotFound sourceSaysNoImage() {
            HttpClientErrorException.NotFound notFound = mock(HttpClientErrorException.NotFound.class);
            when(notFound.getStatusCode()).thenReturn(HttpStatus.NOT_FOUND);
            return notFound;
        }
    }

    /**
     * 在飞表里现有的条数：排上队与正在取的都在表里，排不进队的当场拿掉
     */
    private int inFlightCount() throws Exception {
        return ((Map<?, ?>) privateField(painter, "avatarInFlight")).size();
    }

    /**
     * 这张表的上界＝下载线程数＋排队上限，两个数都取自真正干活的那个线程池
     */
    private int fetchCapacity() throws Exception {
        ThreadPoolExecutor fetchers = (ThreadPoolExecutor) privateField(null, "AVATAR_FETCHERS");
        return fetchers.getMaximumPoolSize() + fetchers.getQueue().size() + fetchers.getQueue().remainingCapacity();
    }

    private static Object privateField(Object target, String name) throws Exception {
        Field field = BilibiliDataQueryPainter.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    /**
     * 接住应用日志这一支（org.frostnova.nova 整支）：出错不许多，且要有一条说清几张没取到
     * <p>
     * 只挂这一层：再往根上挂同一个收集器的话，事件一路上冒会记成两条
     */
    private static ListAppender<ILoggingEvent> attachToApplicationLog() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        applicationLogger().addAppender(appender);
        return appender;
    }

    private static void detach(ListAppender<ILoggingEvent> appender) {
        applicationLogger().detachAppender(appender);
    }

    private static ch.qos.logback.classic.Logger applicationLogger() {
        return (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.frostnova.nova");
    }

    private static String messages(List<ILoggingEvent> events) {
        List<String> lines = events.stream().map(ILoggingEvent::getFormattedMessage).toList();
        return String.valueOf(lines).length() > 500 ? String.valueOf(lines).substring(0, 500) : String.valueOf(lines);
    }

    private static byte[] pngOf(Color color) throws IOException {
        BufferedImage image = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, 200, 200);
        graphics.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static BilibiliDataQueryPainter.Header header() {
        return new BilibiliDataQueryPainter.Header("弹幕排行榜", "本场数据 · 测试主播的直播间", null);
    }

    private static String footnote(int shown) {
        return "前 " + shown + " 名 · 共 " + shown + " 人";
    }

    /**
     * 造一页连号用户，头像地址按页错开，页页不同
     */
    private static List<UserScore> rankingWithFaces(int count, int offset) {
        List<UserScore> rows = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            rows.add(new UserScore((long) (offset + i), "观众" + String.format("%03d", offset + i),
                    "https://pic.example/avatar" + (offset + i) + ".jpg", count - i + 1));
        }
        return rows;
    }

    private static BufferedImage decode(String base64) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
        assertNotNull(image, "解不出图");
        return image;
    }

    /**
     * 数绿像素：版面上没有别的绿色，数到绿色就是恢复后取回的头像被画出来了
     */
    private static int countGreen(BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                if (((rgb >> 8) & 0xFF) > 150 && ((rgb >> 16) & 0xFF) < 100 && (rgb & 0xFF) < 100) {
                    count++;
                }
            }
        }
        return count;
    }
}
