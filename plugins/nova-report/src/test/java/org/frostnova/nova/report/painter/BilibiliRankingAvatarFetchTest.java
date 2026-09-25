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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 源站慢时查榜的整张图能不能出来
 * <p>
 * 头像一张一张地同步下，源站一慢群里的人就一直等不到图：五十个人、每人晚一秒回，
 * 是五十秒；碰上一张永远不回的，图就永远出不来。这里用假下载器把这两种源站摆出来，
 * 看出图要多久、没取回的头像怎么摆，以及超时的那一张会不会被记成好几小时都取不到。
 */
@DisplayName("排行榜头像下载：源站慢时整张图照出")
class BilibiliRankingAvatarFetchTest {
    private static final int PEOPLE = 50;

    /**
     * 整张图等头像的总时间：到点没取回的空着位置照出图
     */
    private static final long WAIT_BUDGET_MS = 3000;

    /**
     * 每张头像晚这么久回
     */
    private static final long LATE_MS = 1000;

    private static final Color AVATAR_RED = new Color(220, 40, 40);

    private static final Color AVATAR_GREEN = new Color(40, 200, 40);

    /**
     * 那一张永远不回的头像卡着不放时为 true
     * <p>
     * 进门时还挂着的那一次才算「卡住的那一回」，放手后才进门的那次直接算取到了
     */
    private final AtomicBoolean hanging = new AtomicBoolean(true);

    /**
     * 源站已经恢复：取图不再拖那一秒。
     * <p>
     * 「超时没取回的不许记成失败缓存」问的是源站坏过一阵又好了之后那张头像回不回得来。
     * 好了就是不再慢——若第二次查仍按每人晚一秒算，五十个人里那二三十张没取回的
     * 会把整张图的三秒预算吃光，量到的是「源站还在慢时来不及」，答的不是这一问。
     */
    private final AtomicBoolean sourceRecovered = new AtomicBoolean(false);

    /**
     * 测试收尾：让还卡着的下载线程退出，别拖着进程不放
     */
    private final AtomicBoolean abandoned = new AtomicBoolean(false);

    private final CountDownLatch released = new CountDownLatch(1);

    private final CountDownLatch stuckDone = new CountDownLatch(1);

    private final AtomicInteger hangCalls = new AtomicInteger();

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

        BufferedImage red = solid(AVATAR_RED);
        BufferedImage green = solid(AVATAR_GREEN);

        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.fetchBilibiliImage(anyString())).thenAnswer(invocation -> {
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
                lateReturn();
                return Optional.of(green);
            }
            lateReturn();
            return Optional.of(red);
        });

        painter = new BilibiliDataQueryPainter(factory, api);
    }

    @AfterEach
    void tearDown() {
        abandoned.set(true);
        released.countDown();
        stuckDone.countDown();
    }

    @Test
    @DisplayName("每张头像晚 1 秒回、另有一张永远不回：50 人的图 5 秒内照出，没取回的空着")
    void slowSourceStillProducesImageWithinFiveSeconds() throws Exception {
        List<UserScore> rows = rankingWithFaces(PEOPLE);

        long started = System.nanoTime();
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "avatar-budget-probe");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<String> future = executor.submit(() -> painter.paintRanking(header(), rows, 1,
                    score -> Math.round(score) + " 条", footnote(PEOPLE)).orElseThrow());
            String base64;
            try {
                base64 = future.get(WAIT_BUDGET_MS + 2000, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                fail("出 50 人的图超过 " + (WAIT_BUDGET_MS + 2000) + " 毫秒还没出图"
                        + "——源站慢时群里的人就一直等不到图");
                return;
            }
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            System.out.println("源站慢50人出图耗时 " + elapsedMs + " 毫秒");

            BufferedImage image = decode(base64);
            assertEquals(0, countGreen(image),
                    "永远不回的那张头像该空着位置，不该画占位色块");
            assertTrue(countRed(image) > 0, "取回来的头像照画");
        } finally {
            executor.shutdownNow();
        }

        // 超时没取回的不许记成失败缓存：源站好了之后再查，那一张要能取得回来
        hanging.set(false);
        released.countDown();
        assertTrue(stuckDone.await(2, TimeUnit.SECONDS), "卡住的那一次取图没在 2 秒内收尾");
        sourceRecovered.set(true);

        BufferedImage second = decode(painter.paintRanking(header(), rows, 1,
                score -> Math.round(score) + " 条", footnote(PEOPLE)).orElseThrow());
        assertAll(
                () -> assertTrue(countGreen(second) > 0,
                        "上一轮超时没取回的头像被记成失败缓存了，源站好了再查仍然空着"),
                () -> assertTrue(hangCalls.get() >= 2,
                        "第二回没有重新去取那张头像，取用的是上一轮留下的记录"));
    }

    @Test
    @DisplayName("每张头像晚 1 秒回：50 人出整张图的耗时量出来")
    void fiftyAvatarsEachOneSecondLate() throws Exception {
        // 这一格只量「每张晚 1 秒」，不掺「有一张永远不回」
        hanging.set(false);
        released.countDown();

        List<UserScore> rows = rankingWithFaces(PEOPLE);
        long started = System.nanoTime();
        BufferedImage image = decode(painter.paintRanking(header(), rows, 1,
                score -> Math.round(score) + " 条", footnote(PEOPLE)).orElseThrow());
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        System.out.println("每张头像晚" + LATE_MS + "毫秒回的50人出图耗时 " + elapsedMs + " 毫秒");

        assertTrue(countRed(image) > 0, "图要出得来、头像要画得上");
        assertTrue(elapsedMs > 0, "耗时要量得出数");
    }

    private static BufferedImage solid(Color color) {
        BufferedImage image = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, 200, 200);
        graphics.dispose();
        return image;
    }

    /**
     * 每张头像都晚 {@link #LATE_MS} 才回；中断不算回，免得被中断成「取不到」。
     * 源站恢复后不再拖
     */
    private void lateReturn() {
        if (sourceRecovered.get()) {
            return;
        }
        long deadline = System.currentTimeMillis() + LATE_MS;
        while (System.currentTimeMillis() < deadline && !abandoned.get()) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                // 源站慢就是慢，谁来催都一样
            }
        }
    }

    private BilibiliDataQueryPainter.Header header() {
        return new BilibiliDataQueryPainter.Header("弹幕排行榜", "本场数据 · 测试主播的直播间", null);
    }

    private static String footnote(int shown) {
        return "前 " + shown + " 名 · 共 " + PEOPLE + " 人";
    }

    /**
     * 造一页连号用户，排头那张头像永远不回
     * <p>
     * 「永远不回」的那张放进第一批：线程一批一批地取，它排在后面批次时开取的时刻正压在
     * 整图那条三秒预算的线上——进门早于放手是「永远不回」，晚于放手就成了「回得慢、落进缓存」，
     * 同一格会摆出两个故事。放进第一批则一进门就取，卡住的那一次确定落在第一回里。
     */
    private static List<UserScore> rankingWithFaces(int count) {
        List<UserScore> rows = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            String suffix = i == 1 ? "-hang" : "";
            rows.add(new UserScore((long) i, "观众" + String.format("%02d", i),
                    "https://pic.example/avatar" + i + suffix + ".jpg", count - i + 1));
        }
        return rows;
    }

    private static BufferedImage decode(String base64) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
        assertNotNull(image, "解不出图");
        return image;
    }

    /**
     * 数红像素：头像取回来就画，取不回来就空着
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
}
