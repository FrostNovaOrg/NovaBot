package org.frostnova.nova.report.painter;

import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.UserScore;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 表头那张主播头像，源站慢或说没有时出图受不受影响
 * <p>
 * 「直播间数据」「数据排行榜」两张图的表头都带主播头像。头像不许拖住整图：
 * 取它要限时，到点没取回的照旧出图；源站明说没有的记住，这次没取成的不记——
 * 记错了，源站卡的那几分钟会变成接下来几小时都画不出头像。
 * 这里把源站摆成两种样子（永远不回、明说没有），看两张图各自怎么出。
 */
@DisplayName("表头主播头像：源站慢或说没有时整张图照出")
class BilibiliHeaderAvatarFetchTest {

    /**
     * 整张图等头像的总时间：到点没取回的空着位置照出图
     */
    private static final long WAIT_BUDGET_MS = 3000;

    private static final Color AVATAR_RED = new Color(220, 40, 40);

    private static final Color AVATAR_GREEN = new Color(40, 200, 40);

    /**
     * 那一张永远不回的头像卡着不放时为 true
     * <p>
     * 进门时还挂着的那一次才算「卡住的那一回」，放手后才进门的那次直接算取到了
     */
    private final AtomicBoolean hanging = new AtomicBoolean(true);

    /**
     * 测试收尾：让还卡着的下载线程退出，别拖着进程不放
     */
    private final AtomicBoolean abandoned = new AtomicBoolean(false);

    private final CountDownLatch released = new CountDownLatch(1);

    private final CountDownLatch stuckDone = new CountDownLatch(1);

    /**
     * 卡住的那张头像一共被叫了几次：进门计数，判「卡住的那一回」与「第二回重新去取」都认它
     */
    private final AtomicInteger hangCalls = new AtomicInteger();

    /**
     * 源站明说没有的那张头像一共被问了几次：第二张图还问就是没记住
     */
    private final AtomicInteger missCalls = new AtomicInteger();

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

        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getBilibiliImage(anyString())).thenAnswer(this::fetchImage);
        when(api.fetchBilibiliImage(anyString())).thenAnswer(this::fetchImage);

        painter = new BilibiliDataQueryPainter(factory, api);
    }

    @AfterEach
    void tearDown() {
        abandoned.set(true);
        released.countDown();
        stuckDone.countDown();
    }

    @Test
    @DisplayName("源站卡住表头头像：直播间数据那张图 5 秒内照出、头像位置空着")
    void cardsRenderWithinBudgetWhileHeaderFaceHangs() throws Exception {
        List<BilibiliDataQueryPainter.DataCard> cards = List.of(
                new BilibiliDataQueryPainter.DataCard("144 条", "弹幕 · 第 3 名"),
                new BilibiliDataQueryPainter.DataCard("¥52.5", "礼物 · 第 1 名"));

        BufferedImage first = decode(withinBudget(
                () -> painter.paintCards(headerWithHangFace(), cards, "直播时长 2 小时 8 分钟")));
        assertAll(
                () -> assertTrue(hangCalls.get() >= 1, "卡住的那一次没进门，这一格什么都没量到"),
                () -> assertEquals(0, countGreen(first), "永远不回的那张表头头像该空着位置，不该画占位色块"),
                () -> assertTrue(countPink(first) > 0, "表头要照出图：标题要画得上"));

        recoverSource();
        BufferedImage second = decode(painter.paintCards(headerWithHangFace(), cards, "直播时长 2 小时 8 分钟")
                .orElseThrow());
        assertAll(
                () -> assertTrue(countGreen(second) > 0,
                        "源站好了下一张图没画上头像——上一轮没取到的被记成「没有这张图」了"),
                () -> assertTrue(hangCalls.get() >= 2,
                        "第二回没有重新去取那张头像，取用的是上一轮留下的记录"));
    }

    @Test
    @DisplayName("源站卡住表头头像：排行榜那张图 5 秒内照出、名次行照画、头像位置空着")
    void rankingRendersWithinBudgetWhileHeaderFaceHangs() throws Exception {
        List<UserScore> rows = ranking(5);

        BufferedImage first = decode(withinBudget(
                () -> painter.paintRanking(headerWithHangFace(), rows, 1, score -> Math.round(score) + " 条", null)));
        assertAll(
                () -> assertTrue(hangCalls.get() >= 1, "卡住的那一次没进门，这一格什么都没量到"),
                () -> assertEquals(0, countGreen(first), "永远不回的那张表头头像该空着位置，不该画占位色块"),
                () -> assertTrue(countRed(first) > 0, "名次行的头像要照画"),
                () -> assertTrue(countPink(first) > 0, "表头要照出图：标题要画得上"));

        recoverSource();
        BufferedImage second = decode(painter.paintRanking(headerWithHangFace(), rows, 1,
                score -> Math.round(score) + " 条", null).orElseThrow());
        assertAll(
                () -> assertTrue(countGreen(second) > 0,
                        "源站好了下一张图没画上头像——上一轮没取到的被记成「没有这张图」了"),
                () -> assertTrue(hangCalls.get() >= 2,
                        "第二回没有重新去取那张头像，取用的是上一轮留下的记录"));
    }

    @Test
    @DisplayName("源站明说没有这张图：表头记住，下一张图不再重取")
    void missingHeaderFaceIsRemembered() {
        List<BilibiliDataQueryPainter.DataCard> cards = List.of(
                new BilibiliDataQueryPainter.DataCard("10241", "弹幕 · 328 人参与"));

        BufferedImage first = decode(painter.paintCards(headerWithMissingFace(), cards, null).orElseThrow());
        BufferedImage second = decode(painter.paintCards(headerWithMissingFace(), cards, null).orElseThrow());

        assertAll(
                () -> assertEquals(1, missCalls.get(),
                        "源站明说没有这张图还一趟趟重取，等于一直在问一个没有的答案"),
                () -> assertEquals(0, countGreen(first), "源站说没有的头像不该画出来"),
                () -> assertEquals(0, countGreen(second), "源站说没有的头像不该画出来"),
                () -> assertTrue(countPink(first) > 0 && countPink(second) > 0, "两回都要照出表头"));
    }

    /**
     * 假源站：卡住的那张拖着不回，明说没有的回空，其余给一张纯色图
     * <p>
     * 两个取图入口同一副肠子——出图走哪条路都撞得上同一种源站
     */
    private Optional<BufferedImage> fetchImage(InvocationOnMock invocation) throws InterruptedException {
        String url = invocation.getArgument(0);
        if (url.contains("hang")) {
            // 进来时还挂着「永远不回」的就是卡住的那一回，放手后它算没取到
            boolean stuckAttempt = hanging.get();
            hangCalls.incrementAndGet();
            try {
                while (released.getCount() > 0 && !abandoned.get()) {
                    released.await(20, TimeUnit.MILLISECONDS);
                }
            } finally {
                stuckDone.countDown();
            }
            if (abandoned.get()) {
                throw new IllegalStateException("测试收尾，这次取图算没回");
            }
            if (stuckAttempt) {
                throw new IllegalStateException("连接被放弃");
            }
            return Optional.of(solid(AVATAR_GREEN));
        }
        if (url.contains("missing")) {
            missCalls.incrementAndGet();
            return Optional.empty();
        }
        return Optional.of(solid(AVATAR_RED));
    }

    /**
     * 整图在「预算＋余量」内出来；到点没出来就判红——源站慢时群里的人不能一直等不到图
     */
    private String withinBudget(Supplier<Optional<String>> draw) throws Exception {
        long started = System.nanoTime();
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "avatar-budget-probe");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<String> future = executor.submit(() -> draw.get().orElseThrow());
            String base64;
            try {
                base64 = future.get(WAIT_BUDGET_MS + 2000, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                fail("表头头像源站卡住时整张图应在 5 秒内出来、头像位置空着——现在整图跟着这张头像一直等下去");
                return "";
            }
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            System.out.println("表头头像卡住时出图耗时 " + elapsedMs + " 毫秒");
            return base64;
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 源站恢复：放开那张卡住的连接，等它这趟收完尾，下一张图要重新去取
     */
    private void recoverSource() throws InterruptedException {
        hanging.set(false);
        released.countDown();
        assertTrue(stuckDone.await(2, TimeUnit.SECONDS), "卡住的那一次取图没在 2 秒内收尾");
    }

    private static BilibiliDataQueryPainter.Header headerWithHangFace() {
        return new BilibiliDataQueryPainter.Header("测试主播", "本场数据 · 测试主播的直播间",
                "https://pic.example/face-hang.jpg");
    }

    private static BilibiliDataQueryPainter.Header headerWithMissingFace() {
        return new BilibiliDataQueryPainter.Header("测试主播", "累计数据 · 历次直播合计",
                "https://pic.example/face-missing.jpg");
    }

    /**
     * 造一页连号观众，得分随名次递减，头像地址是假的
     */
    private static List<UserScore> ranking(int count) {
        List<UserScore> rows = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            rows.add(new UserScore((long) i, "观众" + String.format("%02d", i),
                    "https://pic.example/audience" + i + ".jpg", count - i + 1));
        }
        return rows;
    }

    private static BufferedImage solid(Color color) {
        BufferedImage image = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, 200, 200);
        graphics.dispose();
        return image;
    }

    private static BufferedImage decode(String base64) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
            assertNotNull(image, "解不出图");
            return image;
        } catch (Exception e) {
            return fail("解不出图", e);
        }
    }

    /**
     * 数红像素：名次行的头像取回来就画，取不回来就空着
     */
    private static int countRed(BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                if (((rgb >> 16) & 0xFF) > 150 && ((rgb >> 8) & 0xFF) < 100 && (rgb & 0xFF) < 100) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * 数绿像素：版面上没有别的绿色，数到绿色就是那张「永远不回」的头像被画出来了
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

    /**
     * 数标题粉：表头出没出图，看标题那一行的字画没画
     */
    private static int countPink(BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                if (((rgb >> 16) & 0xFF) > 200 && ((rgb >> 8) & 0xFF) > 60 && ((rgb >> 8) & 0xFF) < 170
                        && (rgb & 0xFF) > 110 && (rgb & 0xFF) < 190) {
                    count++;
                }
            }
        }
        return count;
    }
}
