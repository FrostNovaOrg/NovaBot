package org.frostnova.nova.report.painter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.model.BilibiliLiveMetric;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.frostnova.nova.bilibili.model.Room;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.service.DefaultLiveDataService;
import org.frostnova.nova.core.service.LiveRoomInfoHistory;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 词云那一块在<b>整份报告</b>里占多高：调用层的三件事
 *
 * <h2>为什么排版器绿了还得量调用层</h2>
 * 排版器交出「这份词该排成 128px 高的一小团」，调用层照旧按 380px 去要图、
 * 按 380px 去挪下一块的位置——图上就是一小团词底下吊着二百多像素的空白，
 * 而排版器那边的判据一条都不会红。<b>推荐高度只有被真的用上才算数</b>：
 * 这里量的是报告总高，它是词云块实际占了多高的唯一外部读数。
 * <p>
 * 另两件同样只在调用层看得见：词少的场次到底出不出词云（旧版少于 5 个词整块不画），
 * 以及放不下的词有没有留下痕迹。
 */
@DisplayName("词云块在报告里的高度与日志")
class WordCloudReportBlockTest {
    private static final String PLATFORM = "bilibili";

    /**
     * 词云块的高度分档，写死在判据侧：3 个词一档、满额 72 个词一档
     */
    private static final int HEIGHT_FOR_THREE_WORDS = 128;

    private static final int HEIGHT_FOR_FULL_CLOUD = 380;

    private static final int CONTENT_WIDTH = 830;

    private static final int CLOUD_MAX_WORDS = 72;

    private DefaultLiveDataService liveDataService;

    private BilibiliLiveReportPainter painter;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void setUp() {
        NovaCoreProperties coreProperties = new NovaCoreProperties();
        // 用核心内置字体，免得版式结论取决于跑测试这台机器装了什么字体
        coreProperties.getPaint().getFonts().add("内置");

        FontUtil fontUtil = new FontUtil(new DefaultResourceLoader(), coreProperties);
        fontUtil.init();

        Properties buildInfo = new Properties();
        buildInfo.setProperty("version", "4.3.0");
        buildInfo.setProperty("group", "com." + "starlwr");
        buildInfo.setProperty("artifact", "starbot-core");
        buildInfo.setProperty("name", "NovaBot");
        NovaCommonPainterFactory factory =
                new NovaCommonPainterFactory(new BuildProperties(buildInfo), coreProperties, fontUtil);

        BufferedImage placeholder = new BufferedImage(640, 360, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = placeholder.createGraphics();
        graphics.setColor(new Color(120, 170, 220));
        graphics.fillRect(0, 0, 640, 360);
        graphics.dispose();

        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getBilibiliImage(anyString())).thenReturn(Optional.of(placeholder));
        Room room = new Room();
        room.setTitle("测试直播间");
        room.setCover("https://pic.example/cover.jpg");
        when(api.getLiveInfoByRoomId(anyLong())).thenReturn(room);
        when(api.getGuardList(anyLong(), anyLong())).thenReturn(Optional.of(List.of()));

        liveDataService = new DefaultLiveDataService(new NovaCoreProperties());
        LiveRoomInfoHistory roomInfoHistory = new LiveRoomInfoHistory(new NovaStateStore(new NovaCoreProperties()));
        painter = new BilibiliLiveReportPainter(factory, api, liveDataService, fontUtil,
                new NovaBilibiliProperties(), roomInfoHistory);
    }

    private LiveStreamerInfo aSession() {
        LiveStreamerInfo streamer = new LiveStreamerInfo(10001L, "测试主播", 20002L, "https://pic.example/face.jpg");
        long start = 1_700_000_000_000L;
        liveDataService.setLiveStartTime(PLATFORM, streamer.getUid(), start);
        liveDataService.setLiveEndTime(PLATFORM, streamer.getUid(), start + 3600_000);
        liveDataService.incrementLiveMetric(PLATFORM, streamer.getUid(), BilibiliLiveMetric.DANMU_COUNT, 200);
        return streamer;
    }

    /**
     * 判据①：3 个词的场次，词云块画出来了
     * <p>
     * 旧版少于 5 个词整块不画，这一条那时是红的。量两件事：块本身有墨、
     * 报告总高比关掉词云时多出至少一整块
     */
    @Test
    @DisplayName("判据①：3 个词的场次照画词云块")
    void threeWordsStillGetACloud() throws Exception {
        LiveStreamerInfo streamer = aSession();
        Map<String, Integer> words = new LinkedHashMap<>();
        words.put("晚上好", 9);
        words.put("好听", 5);
        words.put("加油", 2);

        BufferedImage cloud = painter.paintWordCloud(PLATFORM, streamer.getUid(), words);
        int ink = inkPixels(cloud);

        words.forEach((word, count) -> {
            for (int i = 0; i < count; i++) {
                liveDataService.incrementLiveWordFrequency(PLATFORM, streamer.getUid(), word);
            }
        });
        int withCloud = reportHeight(streamer, true);
        int withoutCloud = reportHeight(streamer, false);

        String reading = "3 词词云块 " + cloud.getWidth() + "×" + cloud.getHeight() + ", 墨点 " + ink
                + "\n报告总高 带词云 " + withCloud + " 不带 " + withoutCloud
                + " 差 " + (withCloud - withoutCloud) + "（块高应为 " + HEIGHT_FOR_THREE_WORDS + "）\n";
        write("wordcloud-three-words.txt", reading);

        List<String> failures = new ArrayList<>();
        if (cloud.getHeight() != HEIGHT_FOR_THREE_WORDS) {
            failures.add("3 词词云块高 " + cloud.getHeight() + ", 应为 " + HEIGHT_FOR_THREE_WORDS);
        }
        if (cloud.getWidth() != CONTENT_WIDTH) {
            failures.add("词云块宽 " + cloud.getWidth() + ", 应为 " + CONTENT_WIDTH);
        }
        if (ink <= 0) {
            failures.add("3 词词云块上一个墨点都没有");
        }
        if (withCloud - withoutCloud < HEIGHT_FOR_THREE_WORDS) {
            failures.add("报告开词云只多出 " + (withCloud - withoutCloud) + "px, 不足一整块 "
                    + HEIGHT_FOR_THREE_WORDS + "px——这一块没画");
        }
        assertTrue(failures.isEmpty(), reading + String.join("\n", failures));
    }

    /**
     * 判据②：报告总高随词云的实际高度走
     * <p>
     * 词云是报告里最后一块内容，它下面就是落款——所以<b>报告总高之差就是词云块之差</b>。
     * 只把 PNG 缩小、仍按 380px 预留位置的话，两份报告一样高，这一条红
     */
    @Test
    @DisplayName("判据②：3 词与 72 词的报告总高之差＝两块词云的高度之差")
    void reportHeightFollowsCloudHeight() throws Exception {
        LiveStreamerInfo few = aSession();
        Map<String, Integer> threeWords = new LinkedHashMap<>();
        threeWords.put("晚上好", 9);
        threeWords.put("好听", 5);
        threeWords.put("加油", 2);
        threeWords.forEach((word, count) -> {
            for (int i = 0; i < count; i++) {
                liveDataService.incrementLiveWordFrequency(PLATFORM, few.getUid(), word);
            }
        });
        int heightWithThree = reportHeight(few, true);
        int cloudWithThree = painter.paintWordCloud(PLATFORM, few.getUid(), threeWords).getHeight();

        setUp();
        LiveStreamerInfo many = aSession();
        Map<String, Integer> fullCloud = corpus(CLOUD_MAX_WORDS);
        fullCloud.forEach((word, count) -> {
            for (int i = 0; i < count; i++) {
                liveDataService.incrementLiveWordFrequency(PLATFORM, many.getUid(), word);
            }
        });
        int heightWithSeventyTwo = reportHeight(many, true);
        int cloudWithSeventyTwo = painter.paintWordCloud(PLATFORM, many.getUid(), fullCloud).getHeight();

        int reportDelta = heightWithSeventyTwo - heightWithThree;
        int cloudDelta = cloudWithSeventyTwo - cloudWithThree;
        String reading = "3 词: 报告 " + heightWithThree + " 词云块 " + cloudWithThree
                + "\n72 词: 报告 " + heightWithSeventyTwo + " 词云块 " + cloudWithSeventyTwo
                + "\n报告之差 " + reportDelta + " 词云之差 " + cloudDelta + "\n";
        write("wordcloud-report-height.txt", reading);

        List<String> failures = new ArrayList<>();
        if (cloudWithThree != HEIGHT_FOR_THREE_WORDS) {
            failures.add("3 词词云块高 " + cloudWithThree + ", 应为 " + HEIGHT_FOR_THREE_WORDS);
        }
        if (cloudWithSeventyTwo != HEIGHT_FOR_FULL_CLOUD) {
            failures.add("72 词词云块高 " + cloudWithSeventyTwo + ", 应为 " + HEIGHT_FOR_FULL_CLOUD);
        }
        if (reportDelta != cloudDelta) {
            failures.add("报告总高之差 " + reportDelta + " 与词云块之差 " + cloudDelta
                    + " 对不上——下面的模块没有按实际高度挪");
        }
        assertTrue(failures.isEmpty(), reading + String.join("\n", failures));
    }

    /**
     * 判据③：有词没放下时，日志里恰好留下一行 WARN
     * <p>
     * 放不下的词被静默丢掉，报告看上去仍然完整——「这一场的词云少了三十八个词」
     * 只有日志说得出来。恰一行：每丢一个词打一行会把日志刷爆，一行不打就是静默
     */
    @Test
    @DisplayName("判据③：有词没放下时恰打一行 WARN")
    void droppedWordsGetExactlyOneWarning() throws Exception {
        LiveStreamerInfo streamer = aSession();

        // 72 个 8 码点的长标签装不进 830×380，这是排版器自己认下的容量边界
        Map<String, Integer> longLabels = new LinkedHashMap<>();
        for (int i = 0; i < CLOUD_MAX_WORDS; i++) {
            longLabels.put(String.format("弹幕场景词条%02d", i), 100 - i);
        }

        WordCloudLayout.Result layout = painter.layoutWordCloud(PLATFORM, streamer.getUid(), longLabels);

        List<ILoggingEvent> events = new ArrayList<>();
        try (Capture ignored = new Capture(BilibiliLiveReportPainter.class, events)) {
            painter.paintWordCloud(PLATFORM, streamer.getUid(), longLabels);
        }

        List<ILoggingEvent> warnings = events.stream().filter(event -> event.getLevel() == Level.WARN).toList();
        StringBuilder reading = new StringBuilder("长标签场次 未放入 " + layout.dropped()
                + " 个词, 捕到 " + events.size() + " 条日志, 其中 WARN " + warnings.size() + " 条\n");
        warnings.forEach(event -> reading.append("  ").append(event.getFormattedMessage()).append('\n'));
        write("wordcloud-dropped-log.txt", reading.toString());

        List<String> failures = new ArrayList<>();
        if (layout.dropped() <= 0) {
            failures.add("这一份语料一个词都没丢, 判据落空——换一份装不下的语料");
        }
        if (warnings.size() != 1) {
            failures.add("丢了 " + layout.dropped() + " 个词却打了 " + warnings.size() + " 行 WARN, 应恰为 1 行");
        }
        if (warnings.size() == 1 && !warnings.get(0).getFormattedMessage().contains(String.valueOf(layout.dropped()))) {
            failures.add("WARN 里没有说丢了几个词: " + warnings.get(0).getFormattedMessage());
        }
        assertTrue(failures.isEmpty(), reading + String.join("\n", failures));
    }

    /**
     * 一个词都没有的场次也照画一块，图上写着「暂无有效弹幕词」
     */
    @Test
    @DisplayName("判据①（续）：0 个词的场次画出空态块")
    void emptySessionStillGetsABlock() throws Exception {
        LiveStreamerInfo streamer = aSession();
        BufferedImage cloud = painter.paintWordCloud(PLATFORM, streamer.getUid(), Map.of());

        int ink = inkPixels(cloud);
        String reading = "0 词词云块 " + cloud.getWidth() + "×" + cloud.getHeight() + ", 墨点 " + ink + "\n";
        write("wordcloud-empty-block.txt", reading);

        List<String> failures = new ArrayList<>();
        if (cloud.getHeight() != 96) {
            failures.add("0 词词云块高 " + cloud.getHeight() + ", 应为 96");
        }
        if (ink <= 0) {
            failures.add("0 词词云块上一个墨点都没有");
        }
        assertTrue(failures.isEmpty(), reading + String.join("\n", failures));
    }

    /**
     * 出一张报告，量它的实际高度
     *
     * @param cloud 这一趟开不开词云
     */
    private int reportHeight(LiveStreamerInfo streamer, boolean cloud) throws Exception {
        JSONObject params = new JSONObject();
        params.put("danmu_cloud", cloud);
        BilibiliLiveReportOptions options = BilibiliLiveReportOptions.of(params, true);
        String base64 = painter.paint(PLATFORM, streamer, options).orElseThrow();
        return ImageIO.read(new ByteArrayInputStream(java.util.Base64.getDecoder().decode(base64))).getHeight();
    }

    private static int inkPixels(BufferedImage image) {
        int ink = 0;
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    ink++;
                }
            }
        }
        return ink;
    }

    /**
     * 造一份 n 个词的合成语料。真实场次的弹幕里带着观众昵称，不进夹具
     */
    private static Map<String, Integer> corpus(int size) {
        java.util.Random random = new java.util.Random(20_260_908L);
        String pool = "晚上好听唱歌打游戏厉害加油可爱笑死太强岁月史书下次一定主播前排签到早安冲鸭泪目破防上号整活求歌单可以再来";
        Map<String, Integer> words = new LinkedHashMap<>();
        int count = 99;
        while (words.size() < size) {
            StringBuilder word = new StringBuilder();
            int length = 2 + random.nextInt(3);
            for (int i = 0; i < length; i++) {
                word.append(pool.charAt(random.nextInt(pool.length())));
            }
            if (words.putIfAbsent(word.toString(), count) == null) {
                count = Math.max(1, count - 1 - random.nextInt(3));
            }
        }
        return words;
    }

    private static void write(String name, String content) throws Exception {
        Path dir = Path.of("target", "painter-output");
        Files.createDirectories(dir);
        Files.write(dir.resolve(name), content.getBytes());
    }

    /**
     * 把一个类的日志接到一个列表上，用完还原
     * <p>
     * 🔴 量的是<b>真的经过日志框架的那一份</b>：直接去数「源码里有几处 log.warn」
     * 数得出写法，数不出这一趟到底打了几行
     */
    private static final class Capture extends AppenderBase<ILoggingEvent> implements AutoCloseable {
        private final ch.qos.logback.classic.Logger logger;

        private final List<ILoggingEvent> sink;

        private Capture(Class<?> type, List<ILoggingEvent> events) {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            this.logger = context.getLogger(type);
            this.sink = events;
            events.clear();
            setContext(context);
            start();
            logger.setLevel(Level.WARN);
            logger.addAppender(this);
        }

        @Override
        protected void append(ILoggingEvent event) {
            sink.add(event);
        }

        @Override
        public void close() {
            logger.detachAppender(this);
            logger.setLevel(null);
            stop();
        }
    }
}
