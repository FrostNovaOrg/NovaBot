package org.frostnova.nova.report.painter;

import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.health.BilibiliRiskMetrics;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
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

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两张图同时要同一个还没开工的头像：先到点的那张把那趟撤了，后一张怎么办
 * <p>
 * 后一张跟着前一张那趟走，手里没有撤单权；前一张到点撤掉那趟时，后一张等到的也是「撤掉了」。
 * 它自己的时间还没用完，就该自己再排一趟，而不是跟着前一张一起空着头像位。
 * <p>
 * 取图线程由这一格亲手占满、亲手放开，两张图的截止时刻由这一格给：
 * 「那趟还没开工」「前一张先到点」都是摆出来的，不靠墙钟碰巧。
 */
@DisplayName("数据图取头像：两张图共用一趟被前一张撤掉")
class BilibiliAvatarSharedFetchTest {

    private static final String SHARED = "https://pic.example/shared-face.jpg";

    /**
     * 前一张图的截止：够后一张图跟上那趟就行；跟没跟上，下面当场核
     */
    private static final long LEADER_WAIT_MILLIS = 1500;

    private final CountDownLatch poolGate = new CountDownLatch(1);

    private FakeNetwork network;

    private BilibiliDataQueryPainter painter;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void setUp() throws Exception {
        awaitFetchersDrained();

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

        network = new FakeNetwork(pngOf(new Color(40, 200, 40)));
        BilibiliApiUtil api = new BilibiliApiUtil(network, new NovaBilibiliProperties(), new BilibiliRiskMetrics());
        painter = new BilibiliDataQueryPainter(factory, api);
    }

    @AfterEach
    void tearDown() throws Exception {
        poolGate.countDown();
        awaitFetchersDrained();
    }

    @Test
    @DisplayName("前一张到点撤了共用的那趟：后一张在自己的时间里再取一次，头像画得上")
    void followerRetriesWhenLeaderAbandonsSharedFetch() throws Exception {
        occupyAllFetchThreads();
        long start = System.nanoTime();

        Painting leader = paintInBackground("前一张", start + TimeUnit.MILLISECONDS.toNanos(LEADER_WAIT_MILLIS));
        awaitSharedFetchQueued();
        Painting follower = paintInBackground("后一张", start + TimeUnit.SECONDS.toNanos(30));
        follower.awaitWaitingBefore(start + TimeUnit.MILLISECONDS.toNanos(LEADER_WAIT_MILLIS));

        leader.joinOrFail();
        // 前一张已经到点撤了那趟；这时再放开线程，源站好好的、线程也空了
        poolGate.countDown();
        follower.joinOrFail();

        assertAll(
                () -> assertNotNull(invokeAvatar(),
                        "后一张图自己的时间还没用完，头像位却空着——跟着前一张那趟走，被前一张到点一起撤了，"
                                + "自己没再取（源站被问到 " + network.fetches() + " 次）"),
                () -> assertEquals(1, network.fetches(), "共用的那个头像源站该被问到恰 1 次"));
    }

    @Test
    @DisplayName("后一张再取的那趟到它自己截止还没开工：同样撤掉，队里不留")
    void followerRetryStillQueuedAtItsDeadlineIsAbandoned() throws Exception {
        ThreadPoolExecutor fetchers = fetchers();
        occupyAllFetchThreads();
        int queuedBefore = fetchers.getQueue().size();
        long start = System.nanoTime();

        Painting leader = paintInBackground("前一张", start + TimeUnit.MILLISECONDS.toNanos(LEADER_WAIT_MILLIS));
        awaitSharedFetchQueued();
        Painting follower = paintInBackground("后一张", start + TimeUnit.MILLISECONDS.toNanos(LEADER_WAIT_MILLIS * 3));
        follower.awaitWaitingBefore(start + TimeUnit.MILLISECONDS.toNanos(LEADER_WAIT_MILLIS));

        leader.joinOrFail();
        // 线程一直不放：后一张重排的那趟在队里排着，到它自己截止也开不了工
        boolean requeued = false;
        while (follower.isAlive() && !requeued) {
            requeued = fetchers.getQueue().size() > queuedBefore;
            Thread.sleep(2);
        }
        follower.joinOrFail();

        int queuedAfter = fetchers.getQueue().size();
        int inFlight = ((Map<?, ?>) privateField(painter, "avatarInFlight")).size();
        assertTrue(requeued, "后一张没有重排那趟，这一格要量的「重排的到点撤不撤」没发生");
        assertAll(
                () -> assertEquals(queuedBefore, queuedAfter,
                        "后一张到点后，它重排的那趟还留在队里（队长 " + queuedAfter + "，改前 " + queuedBefore
                                + "）——画完的图的排队件堵着后面的新头像"),
                () -> assertEquals(0, inFlight, "在飞表里还挂着 " + inFlight + " 条已经没人等的取图"),
                () -> assertEquals(0, network.fetches(), "线程一直占着，源站不该被问到"));
    }

    /**
     * 把取图线程全占住，占到放开闸为止；这之后排的取图只能在队里等
     */
    private void occupyAllFetchThreads() throws Exception {
        ThreadPoolExecutor fetchers = fetchers();
        int threads = fetchers.getMaximumPoolSize();
        for (int i = 0; i < threads; i++) {
            fetchers.submit(() -> {
                try {
                    poolGate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (fetchers.getActiveCount() < threads || !fetchers.getQueue().isEmpty()) {
            assertTrue(System.nanoTime() < giveUp, "取图线程没被全占住，前提没摆成");
            Thread.sleep(2);
        }
    }

    /**
     * 等前一张图把共用的那趟排进队（在飞表里有它）
     */
    private void awaitSharedFetchQueued() throws Exception {
        Map<?, ?> inFlight = (Map<?, ?>) privateField(painter, "avatarInFlight");
        long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (inFlight.isEmpty()) {
            assertTrue(System.nanoTime() < giveUp, "前一张图没把头像排进队，前提没摆成");
            Thread.sleep(1);
        }
    }

    private Painting paintInBackground(String name, long deadlineNanos) throws Exception {
        Object request = avatarRequest();
        Method prefetch = BilibiliDataQueryPainter.class.getDeclaredMethod("prefetchAvatars", List.class, long.class);
        prefetch.setAccessible(true);
        Painting painting = new Painting(name, () -> prefetch.invoke(painter, List.of(request), deadlineNanos));
        painting.start();
        return painting;
    }

    /**
     * 一张图的取头像那一步，在自己的线程里跑；跑出的异常收着，join 时抛回来
     */
    private static final class Painting extends Thread {
        private final ThrowingRunnable body;

        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        Painting(String name, ThrowingRunnable body) {
            super(name);
            this.body = body;
            setDaemon(true);
        }

        @Override
        public void run() {
            try {
                body.run();
            } catch (InvocationTargetException e) {
                failure.set(e.getCause());
            } catch (Throwable e) {
                failure.set(e);
            }
        }

        /**
         * 等这张图停在「等头像」那一步，且必须在给定时刻之前：否则前一张先到点，后一张就跟不上那趟
         */
        void awaitWaitingBefore(long deadlineNanos) throws InterruptedException {
            while (!waitingForAvatars()) {
                assertTrue(isAlive(), getName() + "图没停在等头像那一步就跑完了，前提没摆成");
                Thread.sleep(1);
            }
            assertTrue(System.nanoTime() < deadlineNanos, getName() + "图跟上那趟时前一张已经到点，前提没摆成");
        }

        private boolean waitingForAvatars() {
            return getState() == State.TIMED_WAITING && Arrays.stream(getStackTrace())
                    .anyMatch(frame -> frame.getMethodName().equals("awaitAvatars"));
        }

        void joinOrFail() throws InterruptedException {
            join(TimeUnit.SECONDS.toMillis(60));
            assertFalse(isAlive(), getName() + "图 60 秒还没画完");
            Throwable thrown = failure.get();
            if (thrown != null) {
                throw new AssertionError(getName() + "图取头像时抛出异常", thrown);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private BufferedImage invokeAvatar() throws Exception {
        Method avatar = BilibiliDataQueryPainter.class.getDeclaredMethod("avatar", String.class, int.class);
        avatar.setAccessible(true);
        return (BufferedImage) avatar.invoke(painter, SHARED, avatarSize());
    }

    private static Object avatarRequest() throws Exception {
        Class<?> type = Class.forName(BilibiliDataQueryPainter.class.getName() + "$AvatarRequest");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(SHARED, avatarSize());
    }

    private static int avatarSize() throws Exception {
        return (Integer) privateField(null, "AVATAR_SIZE");
    }

    private static ThreadPoolExecutor fetchers() throws Exception {
        return (ThreadPoolExecutor) privateField(null, "AVATAR_FETCHERS");
    }

    /**
     * 静态线程池是全进程一份：开跑前等别的格留下的排空，收尾也等这一格的排空
     */
    private static void awaitFetchersDrained() throws Exception {
        ThreadPoolExecutor fetchers = fetchers();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (fetchers.getActiveCount() > 0 || !fetchers.getQueue().isEmpty()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("取图线程池没排空，还有 " + fetchers.getActiveCount()
                        + " 个在取、" + fetchers.getQueue().size() + " 个在排");
            }
            Thread.sleep(50);
        }
    }

    private static Object privateField(Object target, String name) throws Exception {
        Field field = BilibiliDataQueryPainter.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    /**
     * 只把最底层的网络读换成假的：每次都回同一张图，数被问到几次
     */
    private static final class FakeNetwork extends HttpUtil {
        private final AtomicInteger fetches = new AtomicInteger();

        private final byte[] body;

        FakeNetwork(byte[] body) {
            super(null, null, null);
            this.body = body;
        }

        @Override
        public byte[] getBytes(String url, Map<String, String> headers) {
            return getBytes(url, headers, Duration.ofSeconds(1));
        }

        @Override
        public byte[] getBytes(String url, Map<String, String> headers, Duration fetchTimeout) {
            fetches.incrementAndGet();
            return body;
        }

        int fetches() {
            return fetches.get();
        }
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
}
