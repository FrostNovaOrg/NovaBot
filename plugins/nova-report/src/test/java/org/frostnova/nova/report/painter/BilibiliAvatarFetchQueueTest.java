package org.frostnova.nova.report.painter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.health.BilibiliRiskMetrics;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.UserScore;
import org.frostnova.nova.core.properties.LogProperties;
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
import org.springframework.web.client.RestTemplate;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 取头像撞上图片源站挂住：新来的头像排不排得进队、排不进的怎么记、地址带尺寸后缀下几次
 * <p>
 * 源站挂住时取图线程会被占着，队里堆着的旧请求又把新地址挡在门外——好节点上的头像也跟着
 * 一张都画不出来。这一格走真的 BilibiliApiUtil 与 HttpUtil，假源站架在真 HTTP 服务上：
 * 连接收下、请求记下，然后不回。这样限时、排队、拒绝怎么走都量的是线上那一趟。
 * <p>
 * 判定一律数调用次数、等假源站记到那一笔，不看墙钟。起画时刻的摆法是另一回事，
 * 见 newHeader 那格里的注释；那格每次跑印一行计时（塞池图返回、放线程、新图起画、到点、余量），
 * 墙上真跑出来的数，不拿估的数当量出来的数。
 */
@DisplayName("数据图取头像：源站挂住时排队")
class BilibiliAvatarFetchQueueTest {

    /**
     * 卡住的取图放线程的那趟限时：一趟取图自己的限时，取图那侧配的（现值 5 秒）
     * <p>
     * 摆法按它算放线程时刻；哪天这趟限时改了，跟着改这一个数
     */
    private static final long STUCK_FETCH_LIMIT_MILLIS = 5000;

    /**
     * 新图起画比预计的放线程早这么多毫秒
     * <p>
     * 早过头余量就回来了，晚了池已经放开、那就不叫「池满时新头像照样排进来」
     */
    private static final long START_BEFORE_RELEASE_MILLIS = 500;

    private FakeSource source;

    private BilibiliDataQueryPainter painter;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void setUp() throws Exception {
        source = new FakeSource();

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

        BilibiliApiUtil api = new BilibiliApiUtil(new HttpUtil(null, new RestTemplate(), new LogProperties()),
                new NovaBilibiliProperties(), new BilibiliRiskMetrics());
        painter = new BilibiliDataQueryPainter(factory, api);
    }

    @AfterEach
    void tearDown() throws Exception {
        source.close();
        awaitFetchersDrained();
    }

    @Test
    @DisplayName("源站挂住把池塞满：新来的表头头像照样问到源站")
    void newHeaderReachesSourceWhileOldRequestsHoldThePool() throws Exception {
        source.hangForever();
        ReleaseWatcher release = ReleaseWatcher.startWatching();
        fillPoolWithStuckFetches();
        long fillReturned = System.nanoTime();

        // 卡住的取图要到「一趟取图自己的限时」才放线程（取图那侧配的，现值 5 秒）。
        // 新图起画先前紧跟塞池图返回就起：3 秒截止前只剩塞池图渲染省下的那点，
        // 放线程稍慢一点，新头像还没开工就被到点撤了，一次都问不到源站。
        // 这里把起画往后挪到放线程前不远——池还占满、新头像照旧排头一位，
        // 而截止前留给放线程的余量从一千毫秒出头拉到 2 秒多（每趟那行计时里印着实数）。
        long releaseDue = source.firstCallNanos()
                + TimeUnit.MILLISECONDS.toNanos(STUCK_FETCH_LIMIT_MILLIS - START_BEFORE_RELEASE_MILLIS);
        long sleepNanos = releaseDue - System.nanoTime();
        if (sleepNanos > 0) {
            TimeUnit.NANOSECONDS.sleep(sleepNanos);
        }

        String fresh = source.url("fresh-avatar.jpg");
        long paintStart = System.nanoTime();
        painter.paintCards(new BilibiliDataQueryPainter.Header("新查的人", "本场数据", fresh),
                List.of(new BilibiliDataQueryPainter.DataCard("12", "条弹幕")), "脚注").orElseThrow();
        long deadline = paintStart + avatarWaitNanos();

        boolean reached = source.awaitCallsTo(fresh, 1, 15, TimeUnit.SECONDS);
        System.out.println(timingLine(fillReturned, release.releasedOffsetMillis(), paintStart,
                deadline, source.firstCallNanosOf(fresh)));
        assertTrue(reached,
                "池被卡住的旧取图占满后，新来的表头头像一次都没问到源站（记到 "
                        + source.callsTo(fresh) + " 次）——新头像被积压的旧请求挡在门外");
    }

    /**
     * 这一格的计时行：关键时刻各在第几毫秒、余量多少，一行印完，随跑随印
     * <p>
     * 余量＝新图到点−新头像到源站。新头像得在到点之前开工：到点那步撤的是还没开工的，
     * 它一被撤就再也不会问源站了。放线程与到源站前后脚，那是排队里它排头一位。
     */
    private static String timingLine(long fillReturned, long releasedOffset, long paintStart,
                                     long deadline, long freshCallNanos) {
        long freshOffset = freshCallNanos < 0 ? -1 : offsetMillis(freshCallNanos);
        long margin = freshOffset < 0 ? -1 : offsetMillis(deadline) - freshOffset;
        return "计时 塞池图返回+" + offsetMillis(fillReturned) + " 毫秒"
                + "｜放线程+" + releasedOffset
                + "｜新图起画+" + offsetMillis(paintStart)
                + "｜新图到点+" + offsetMillis(deadline)
                + "｜新头像到源站+" + freshOffset
                + "｜余量 " + margin + " 毫秒（新图到点−新头像到源站；-1＝那一步没发生）";
    }

    @Test
    @DisplayName("源站挂住把池塞满：没排上的在 WARN 里写「排不进队」，不带异常类名")
    void stalledQueueReportsWhyInPlainWords() throws Exception {
        source.hangForever();

        ListAppender<ILoggingEvent> logs = attachToApplicationLog();
        try {
            fillPoolWithStuckFetches();
            String fresh = source.url("fresh-avatar.jpg");
            painter.paintCards(new BilibiliDataQueryPainter.Header("新查的人", "本场数据", fresh),
                    List.of(new BilibiliDataQueryPainter.DataCard("12", "条弹幕")), "脚注").orElseThrow();
            List<String> warnings = logs.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();

            String all = String.valueOf(warnings);
            assertAll(
                    () -> assertTrue(warnings.stream().anyMatch(line -> line.contains("排不进队")),
                            "WARN 里没写「排不进队」，看不出这些头像为什么空着：" + all),
                    () -> assertTrue(warnings.stream().noneMatch(line -> line.contains("RejectedExecutionException")),
                            "WARN 里带出了异常类名，用的人看不懂：" + all));
        } finally {
            detach(logs);
        }
    }

    @Test
    @DisplayName("头像地址自带尺寸后缀：表头与名次行同一个地址只下一次")
    void sizedUrlIsDownloadedOnceForBothSizes() throws Exception {
        source.serveImage(pngOf(new Color(40, 200, 40)));
        // 尺寸后缀写 B 站这两种尺寸都常见的 @100w_100h 形：@100w.webp 那种会被
        // 邮箱形状的扫查看成「shared-avatar 这个邮箱，域名 100w.webp」，夹具自己先红
        String shared = source.url("shared-avatar@100w_100h.webp");

        List<UserScore> rows = rankingWithFaces(3, source, shared);
        painter.paintRanking(new BilibiliDataQueryPainter.Header("弹幕排行榜", "本场数据 · 测试主播的直播间", shared),
                rows, 1, score -> Math.round(score) + " 条", footnote(rows.size())).orElseThrow();

        assertEquals(1, source.callsTo(shared),
                "地址自带尺寸后缀时表头和名次行各下了一次，同一个地址共下载 " + source.callsTo(shared) + " 次");
    }

    /**
     * 把下载线程池塞满：卡住的取图占满线程数，再排满队列长（容量按线程池现量）
     */
    private void fillPoolWithStuckFetches() throws Exception {
        int capacity = fetchCapacity();
        List<UserScore> rows = rankingWithFaces(capacity, source, null);
        painter.paintRanking(new BilibiliDataQueryPainter.Header("弹幕排行榜", "本场数据 · 测试主播的直播间", null),
                rows, 1, score -> Math.round(score) + " 条", footnote(capacity)).orElseThrow();
    }

    /**
     * 下载线程池的容量＝线程数＋排队上限，两个数都取自真正干活的那一个池
     */
    private static int fetchCapacity() throws Exception {
        ThreadPoolExecutor fetchers = (ThreadPoolExecutor) privateField(null, "AVATAR_FETCHERS");
        return fetchers.getMaximumPoolSize() + fetchers.getQueue().size() + fetchers.getQueue().remainingCapacity();
    }

    /**
     * 静态线程池是全进程一份：这一格把池塞满过，收尾必须等它排空，别连累别的格
     */
    private static void awaitFetchersDrained() throws Exception {
        ThreadPoolExecutor fetchers = (ThreadPoolExecutor) privateField(null, "AVATAR_FETCHERS");
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
     * 这一格起跑的时刻；计时行里各处都按它印偏移毫秒，几个时刻放在一行里好对着看
     */
    private static final long STARTED_NANOS = System.nanoTime();

    private static long offsetMillis(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos - STARTED_NANOS);
    }

    /**
     * 出图等头像的总预算（到点没取回的空着位置照出图），取自真干活的那一个字段
     */
    private static long avatarWaitNanos() throws Exception {
        return TimeUnit.MILLISECONDS.toNanos((Long) privateField(null, "AVATAR_WAIT_MILLIS"));
    }

    /**
     * 卡住的取图放线程的时刻
     * <p>
     * 放线程是取图限时到点的事：那一笔算跑完了，线程才空出来。线程池公开的几个数里
     * 「跑完条数」只增不减，认它头一次涨过开工时的底数就是放线程那一刻
     */
    private static final class ReleaseWatcher extends Thread {
        private final ThreadPoolExecutor fetchers;

        private final long baseline;

        private volatile long releasedNanos = -1;

        private ReleaseWatcher(ThreadPoolExecutor fetchers) {
            this.fetchers = fetchers;
            this.baseline = fetchers.getCompletedTaskCount();
            setDaemon(true);
        }

        static ReleaseWatcher startWatching() throws Exception {
            ReleaseWatcher watcher =
                    new ReleaseWatcher((ThreadPoolExecutor) privateField(null, "AVATAR_FETCHERS"));
            watcher.start();
            return watcher;
        }

        @Override
        public void run() {
            while (releasedNanos < 0) {
                if (fetchers.getCompletedTaskCount() > baseline) {
                    releasedNanos = System.nanoTime();
                    return;
                }
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        /**放线程那一刻相对起跑的偏移毫秒；还没放线程就是 -1 */
        long releasedOffsetMillis() {
            return releasedNanos < 0 ? -1 : offsetMillis(releasedNanos);
        }
    }

    /**
     * 接住应用日志这一支（org.frostnova.nova 整支），只收 WARN
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

    private static String footnote(int shown) {
        return "前 " + shown + " 名 · 共 " + shown + " 人";
    }

    /**
     * 造一页连号用户；{@code faceOverride} 不为空时，每一行都用它当头像地址
     */
    private static List<UserScore> rankingWithFaces(int count, FakeSource source, String faceOverride) {
        List<UserScore> rows = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            String face = faceOverride != null ? faceOverride : source.url("fill-" + i + ".jpg");
            rows.add(new UserScore((long) i, "观众" + String.format("%03d", i), face, count - i + 1));
        }
        return rows;
    }

    /**
     * 假源站：连接收下、请求路径记下，然后按当前模式办事——卡住不回，或回一张能解码的图
     * <p>
     * 卡住那条路走的是闩：收尾放闩，卡住的取图这才断得开，线程池排得空
     */
    private static final class FakeSource {
        private final HttpServer server;

        private final ExecutorService handlers = Executors.newCachedThreadPool();

        private final CountDownLatch released = new CountDownLatch(1);

        private final AtomicBoolean hanging = new AtomicBoolean();

        private final Map<String, Integer> calls = new ConcurrentHashMap<>();

        /**每个地址头一次被问到的时刻（与计时行同一口径），计时行用它对墙 */
        private final Map<String, Long> callNanos = new ConcurrentHashMap<>();

        private volatile byte[] body;

        FakeSource() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(handlers);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                long calledAt = System.nanoTime();
                calls.merge(path, 1, Integer::sum);
                callNanos.putIfAbsent(path, calledAt);
                if (hanging.get()) {
                    awaitRelease();
                    exchange.close();
                    return;
                }
                byte[] png = body;
                if (png == null) {
                    exchange.close();
                    return;
                }
                exchange.sendResponseHeaders(200, png.length);
                exchange.getResponseBody().write(png);
                exchange.close();
            });
            server.start();
        }

        String url(String name) {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + name;
        }

        void hangForever() {
            body = null;
            hanging.set(true);
        }

        void serveImage(byte[] png) {
            hanging.set(false);
            body = png;
        }

        /**
         * 这个地址（含追加的尺寸后缀）被问到几次
         */
        int callsTo(String url) {
            String name = java.net.URI.create(url).getPath();
            return calls.entrySet().stream()
                    .filter(entry -> entry.getKey().contains(name))
                    .mapToInt(Map.Entry::getValue)
                    .sum();
        }

        /**
         * 这个地址头一次被问到的时刻；一个都没问到时是 -1，那个数不是时刻、只是「没有」
         */
        long firstCallNanosOf(String url) {
            String name = java.net.URI.create(url).getPath();
            return callNanos.entrySet().stream()
                    .filter(entry -> entry.getKey().contains(name))
                    .mapToLong(Map.Entry::getValue)
                    .min()
                    .orElse(-1);
        }

        /**
         * 头一次有地址被问到的时刻（塞池那批的头一趟）；一个都没问到时是 -1
         */
        long firstCallNanos() {
            return callNanos.values().stream()
                    .mapToLong(Long::longValue)
                    .min()
                    .orElse(-1);
        }

        boolean awaitCallsTo(String url, int atLeast, long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (callsTo(url) < atLeast) {
                if (System.nanoTime() > deadline) {
                    return false;
                }
                Thread.sleep(50);
            }
            return true;
        }

        void close() {
            released.countDown();
            server.stop(0);
            handlers.shutdownNow();
        }

        private void awaitRelease() {
            boolean interrupted = false;
            while (released.getCount() > 0) {
                try {
                    released.await(20, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
