package com.starlwr.bot.bilibili.painter;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.model.BilibiliLiveMetric;
import com.starlwr.bot.bilibili.model.Room;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.factory.StarBotCommonPainterFactory;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.service.DefaultLiveDataService;
import com.starlwr.bot.core.service.LiveRoomInfoHistory;
import com.starlwr.bot.core.service.StarBotStateStore;
import com.starlwr.bot.core.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 弹幕词云的<b>几何</b>判据
 *
 * <h2>为什么量几何而不是看图</h2>
 * 词云此前交给第三方库按像素级碰撞随机撒点，判据只验「画得出来、不抛异常」。
 * 于是两件事没有任何东西看得见：同一场直播重发一次报告出来的是<b>另一张图</b>；
 * 词会贴着框边压上圆角。改成自写排版之后，版式规则全部落在
 * {@link WordCloudLayout.Result} 的矩形上，这把尺子读的就是那些矩形。
 * <p>
 * 🔴 <b>不重叠这一条只有几何量得出来。</b>成图上两个词挨得再近也还是墨，
 * 从像素反推不出「这一块属于哪个词」——所以判据必须够得着排版结果本身，
 * 而不是只够得着最后那张图。
 */
@DisplayName("弹幕词云几何")
class BilibiliWordCloudTest {
    private static final String PLATFORM = "bilibili";

    /**
     * 与 {@code BilibiliLiveReportPainter} 里的同名常量对齐
     */
    private static final int CONTENT_WIDTH = 830;

    private static final int CLOUD_HEIGHT = 380;

    private static final int CLOUD_MAX_WORDS = 72;

    /**
     * 🔴 <b>这两个数字写死在判据里，不许从被测那边取。</b>
     * 引 {@code WordCloudLayout.PADDING} 看着更「不重复」，
     * 可那样一来把留白改成 0、把填充目标改成 0，判据会跟着一起松开——
     * 判据就再也逮不住它本来要逮的那件事了
     */
    private static final int PADDING = 8;

    private static final int FILL_TARGET_PERCENT = 85;

    /**
     * 设计语言册（丙·星云）亮色值：accent2 / accent / cloud3 / dim
     */
    private static final Color RANK_1_TO_3 = new Color(0xE0, 0x47, 0x9E);

    private static final Color RANK_4_TO_10 = new Color(0x7A, 0x4D, 0xFF);

    private static final Color RANK_11_TO_25 = new Color(0xA3, 0x8B, 0xFF);

    private static final Color RANK_REST = new Color(0x6E, 0x6A, 0x86);

    private DefaultLiveDataService liveDataService;

    private BilibiliLiveReportPainter painter;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void setUp() {
        StarBotCoreProperties coreProperties = new StarBotCoreProperties();
        // 用核心内置字体，免得版式结论取决于跑测试这台机器装了什么字体
        coreProperties.getPaint().getFonts().add("内置");

        FontUtil fontUtil = new FontUtil(new DefaultResourceLoader(), coreProperties);
        fontUtil.init();

        Properties buildInfo = new Properties();
        buildInfo.setProperty("version", "4.3.0");
        buildInfo.setProperty("group", "com.starlwr");
        buildInfo.setProperty("artifact", "starbot-core");
        buildInfo.setProperty("name", "StarBotCore");
        StarBotCommonPainterFactory factory =
                new StarBotCommonPainterFactory(new BuildProperties(buildInfo), coreProperties, fontUtil);

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

        liveDataService = new DefaultLiveDataService(new StarBotCoreProperties());
        LiveRoomInfoHistory roomInfoHistory = new LiveRoomInfoHistory(new StarBotStateStore(new StarBotCoreProperties()));
        painter = new BilibiliLiveReportPainter(factory, api, liveDataService, fontUtil,
                new StarBotBilibiliProperties(), roomInfoHistory);
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
     * 造一组语料：词与词频都由种子定，同一颗种子给出同一组
     */
    private Map<String, Integer> corpus(int size, long seed) {
        Random random = new Random(seed);
        Map<String, Integer> words = new LinkedHashMap<>();
        String pool = "晚上好听唱歌打游戏厉害加油可爱笑死太强岁月史书下次一定主播前排签到早安冲鸭泪目破防上号整活求歌单可以再来";
        while (words.size() < size) {
            StringBuilder word = new StringBuilder();
            int length = 1 + random.nextInt(4);
            for (int i = 0; i < length; i++) {
                word.append(pool.charAt(random.nextInt(pool.length())));
            }
            words.putIfAbsent(word.toString(), 1 + random.nextInt(200));
        }
        return words;
    }

    /**
     * 判据①：同一场直播两次成图逐字节相同
     * <p>
     * 这是重发同一份报告的人看到的东西——随机撒点时两次是两张不同的图
     */
    @Test
    @DisplayName("判据①：同场同种子两次成图字节相等")
    void sameSessionRendersIdenticalBytes() {
        LiveStreamerInfo streamer = aSession();
        corpus(40, 20260904L).forEach((word, count) -> {
            for (int i = 0; i < count; i++) {
                liveDataService.incrementLiveWordFrequency(PLATFORM, streamer.getUid(), word);
            }
        });

        byte[] first = Base64.getDecoder().decode(painter.paint(PLATFORM, streamer).orElseThrow());
        byte[] second = Base64.getDecoder().decode(painter.paint(PLATFORM, streamer).orElseThrow());

        assertArrayEquals(first, second,
                "同一场直播两次成图应当逐字节相同, 实际长度 " + first.length + " 对 " + second.length);
    }

    /**
     * 判据②：词数 4 不出、5 出
     */
    @Test
    @DisplayName("判据②：词数 4 不出词云、5 出")
    void skipsCloudBelowFiveWords() throws Exception {
        LiveStreamerInfo four = aSession();
        corpus(4, 1L).forEach((word, count) ->
                liveDataService.incrementLiveWordFrequency(PLATFORM, four.getUid(), word));
        int heightWithFour = ImageIO.read(new ByteArrayInputStream(
                Base64.getDecoder().decode(painter.paint(PLATFORM, four).orElseThrow()))).getHeight();

        setUp();
        LiveStreamerInfo five = aSession();
        corpus(5, 1L).forEach((word, count) ->
                liveDataService.incrementLiveWordFrequency(PLATFORM, five.getUid(), word));
        int heightWithFive = ImageIO.read(new ByteArrayInputStream(
                Base64.getDecoder().decode(painter.paint(PLATFORM, five).orElseThrow()))).getHeight();

        // 读数落盘：绿的时候也要留下实值，否则「过了」与「量到了什么」在事后是两回事
        Path dir = Path.of("target", "painter-output");
        Files.createDirectories(dir);
        Files.write(dir.resolve("wordcloud-threshold.txt"),
                ("4 词报告高 " + heightWithFour + "\n5 词报告高 " + heightWithFive
                        + "\n差 " + (heightWithFive - heightWithFour) + "（词云块高 " + CLOUD_HEIGHT + "）\n").getBytes());

        assertTrue(heightWithFive - heightWithFour >= CLOUD_HEIGHT,
                "5 个词应当多出一整块词云, 实际高度 " + heightWithFour + " 对 " + heightWithFive);
    }

    /**
     * 判据③④⑤⑥⑦：随机语料逐组量几何
     * <p>
     * 一组语料一行读数，全部合规才算绿；任何一条不合规都把那一组的实值报出来
     */
    @Test
    @DisplayName("判据③④⑤⑥⑦：20 组随机语料逐组量包围盒、填充率、竖排与配色")
    void geometryHoldsAcrossRandomCorpora() throws Exception {
        List<String> readings = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (int group = 0; group < 20; group++) {
            long seed = 92_000L + group;
            int size = 5 + new Random(seed).nextInt(116);
            Map<String, Integer> words = corpus(size, seed);

            setUp();
            LiveStreamerInfo streamer = aSession();
            WordCloudLayout.Result result = painter.layoutWordCloud(PLATFORM, streamer.getUid(), words);

            int laid = Math.min(size, CLOUD_MAX_WORDS);
            int verticals = (int) result.placements().stream().filter(WordCloudLayout.Placement::vertical).count();
            int verticalLimit = Math.max(0, (laid - 12) / 4);
            double fill = result.fillRatio() * 100;

            readings.add(String.format("第%02d组 词数%3d 落%3d 丢%2d 填充%5.1f%% 竖排%2d/%2d",
                    group, size, result.placements().size(), result.dropped(), fill, verticals, verticalLimit));

            // ③ 两两不相交、全在框内——两个框各自涨 8px 之后仍不相交
            List<Rectangle> boxes = result.placements().stream().map(WordCloudLayout.Placement::box).toList();
            for (int i = 0; i < boxes.size(); i++) {
                Rectangle a = inflate(boxes.get(i));
                if (a.x < 0 || a.y < 0 || a.x + a.width > CONTENT_WIDTH || a.y + a.height > CLOUD_HEIGHT) {
                    failures.add("第" + group + "组「" + result.placements().get(i).text() + "」出界 " + a);
                }
                for (int j = i + 1; j < boxes.size(); j++) {
                    if (a.intersects(inflate(boxes.get(j)))) {
                        failures.add("第" + group + "组「" + result.placements().get(i).text() + "」压上「"
                                + result.placements().get(j).text() + "」 " + a + " 与 " + inflate(boxes.get(j)));
                    }
                }
            }

            // ④ 填充率
            if (fill < FILL_TARGET_PERCENT) {
                failures.add(String.format("第%d组 填充率 %.1f%% 不足 %d%%", group, fill, FILL_TARGET_PERCENT));
            }

            // ⑤⑥ 竖排的个数与位置
            if (verticals > verticalLimit) {
                failures.add("第" + group + "组 竖排 " + verticals + " 个超过上限 " + verticalLimit);
            }
            for (WordCloudLayout.Placement placement : result.placements()) {
                if (placement.rank() <= 12 && placement.vertical()) {
                    failures.add("第" + group + "组 前 12 名的「" + placement.text()
                            + "」（第" + placement.rank() + "名）竖排了");
                }
            }

            // ⑦ 色级逐名次
            for (WordCloudLayout.Placement placement : result.placements()) {
                Color expected = expectedColor(placement.rank());
                if (!expected.equals(placement.color())) {
                    failures.add("第" + group + "组 第" + placement.rank() + "名「" + placement.text()
                            + "」配色 " + placement.color() + " 应为 " + expected);
                }
            }
        }

        Path dir = Path.of("target", "painter-output");
        Files.createDirectories(dir);
        Files.write(dir.resolve("wordcloud-geometry.txt"), String.join("\n", readings).getBytes());

        assertTrue(failures.isEmpty(), "逐组读数:\n" + String.join("\n", readings)
                + "\n不合规:\n" + String.join("\n", failures));
    }

    /**
     * 判据③的成图一面：四周 8px 内不许有墨
     * <p>
     * 几何上框已经离边 8px，这一条量的是<b>画出来的墨真的在框里</b>——
     * 量与画对不上时，几何是绿的而图上照样压边
     */
    @Test
    @DisplayName("判据③（成图）：墨迹距框四周至少留 8px")
    void keepsEightPixelMarginFromFrame() throws Exception {
        LiveStreamerInfo streamer = aSession();
        Map<String, Integer> words = corpus(60, 92_007L);
        BufferedImage cloud = painter.paintWordCloud(PLATFORM, streamer.getUid(), words);

        assertEquals(CONTENT_WIDTH, cloud.getWidth());
        assertEquals(CLOUD_HEIGHT, cloud.getHeight());

        // 留一份样张给人看。判据只说得出「四周有几个墨点」，说不出「难看在哪」
        Path dir = Path.of("target", "painter-output");
        Files.createDirectories(dir);
        ImageIO.write(cloud, "png", dir.resolve("wordcloud.png").toFile());

        int inRing = 0;
        for (int x = 0; x < cloud.getWidth(); x++) {
            for (int y = 0; y < cloud.getHeight(); y++) {
                boolean ring = x < PADDING || y < PADDING
                        || x >= cloud.getWidth() - PADDING || y >= cloud.getHeight() - PADDING;
                if (ring && (cloud.getRGB(x, y) >>> 24) != 0) {
                    inRing++;
                }
            }
        }

        assertEquals(0, inRing, "四周 8px 内不应有墨, 实际 " + inRing + " 点");
    }

    /**
     * 词一个都没有时不该炸，也不该画出空块
     */
    @Test
    @DisplayName("空语料排出空版式")
    void emptyCorpusLaysOutNothing() {
        WordCloudLayout.Result result = painter.layoutWordCloud(PLATFORM, 20002L, Map.of());

        assertTrue(result.placements().isEmpty());
        assertEquals(0, result.dropped());
        assertFalse(result.fillRatio() > 0);
    }

    private static Rectangle inflate(Rectangle box) {
        return new Rectangle(box.x - PADDING, box.y - PADDING, box.width + PADDING * 2, box.height + PADDING * 2);
    }

    private static Color expectedColor(int rank) {
        if (rank <= 3) {
            return RANK_1_TO_3;
        }
        if (rank <= 10) {
            return RANK_4_TO_10;
        }
        if (rank <= 25) {
            return RANK_11_TO_25;
        }
        return RANK_REST;
    }
}
