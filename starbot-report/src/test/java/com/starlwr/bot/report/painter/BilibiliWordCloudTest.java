package com.starlwr.bot.report.painter;

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
     * 🔴 <b>下面这几个数字写死在判据里，不许从被测那边取。</b>
     * 引 {@code WordCloudLayout.WORD_GAP} 看着更「不重复」，
     * 可那样一来把留白改成 0、把填充目标改成 0，判据会跟着一起松开——
     * 判据就再也逮不住它本来要逮的那件事了
     * <p>
     * {@code WORD_GAP} 量的是<b>两个词之间实际隔开多少</b>。它与「每个词四周涨多少」
     * 差一倍：各涨 8px 再判不相交，词与词之间隔的是 16px
     */
    private static final int WORD_GAP = 8;

    /**
     * 词距随字号的系数：相邻两词最小间距 = max(8, round(本系数 × 两词中较大字号))。
     * 同上面那几个数字的纪律：<b>写死在判据侧，不从被测取</b>——引 {@code WordCloudLayout}
     * 的常量的话，把系数改成 0、词距退回固定 8px，判据会跟着一起松，头名粘连这件事
     * 就再也量不出来了。封顶字对另有一条不取整的硬线：实距 ≥ 本系数 × 56（见
     * {@code geometryHoldsAcrossRandomCorpora} 末尾的封顶判）
     */
    private static final double WORD_GAP_PER_FONT = 0.35;

    private static final int FRAME_MARGIN = 8;

    private static final int FILL_TARGET_PERCENT = 85;

    /**
     * 铺不满整框的唯一正当理由：连<b>最小</b>的那个词都已经放大到这个字号以上，
     * 再整体放大只会把词挤掉
     * <p>
     * 字号封顶之后，词少的场次铺不满是算术上的必然：十来个词，一个 {@value #FONT_SIZE_MAX}px
     * 封顶的词占不满 830×380。此前它靠把字号放大到 99px 去凑填充率，而那正是要改掉的事。
     * <p>
     * 🔴 这一条<b>不是把填充率判据放宽</b>：32 是量出来的——词数 5～90 各两颗种子扫一遍，
     * 凡填充率不足 85% 的那 21 个词数，最小字号实测都在 38 以上（最低 38＝17 词那组），
     * 取 32 留 6px 余量。词距随字号加宽后重排，同一组升到 41，余量只宽不窄。
     * 把整体放大那一段拆掉，最小字号会停在 18，两条都不成立即红；
     * 反过来「一律画成最大」也过不去，落词总数那一条会先红
     */
    private static final int FILL_EXEMPT_FONT_SIZE = 32;

    /**
     * 词云允许的最小字号。报告里最小的正文字号是 22，词云尾词比正文再小一档尚可读，
     * 更小就只剩一团色块了——这条是下限，不是当前实现的写照
     */
    private static final int READABLE_FONT_SIZE_MIN = 18;

    /**
     * 词云允许的最大字号，量的是<b>落到图上的字号</b>
     * <p>
     * 🔴 这一条不是「常量 {@code FONT_SIZE_SPAN} 别调大」的另一种写法。排版会为了填满框
     * 把全体字号整体放大，只钳按词频算出来的那一档时，图上真正量到的是 99——
     * 「上限」写在源码里而版式规则并不遵守它
     */
    private static final int FONT_SIZE_MAX = 56;

    /**
     * 头名字号最多是第二名的多少倍。词频悬殊的场次里，线性映射会让头名独吞版面
     */
    private static final double TOP_FONT_SIZE_RATIO = 1.3;

    /**
     * 尾档每几个词点一个暖色
     */
    private static final int ACCENT_GROUP = 4;

    private static final int ACCENT_FROM_RANK = 26;

    /**
     * 改前同一批语料的落词总数：**2026-09-04 实测于 lane-c 3c03438**
     * （字号按 √词频、词与词隔 16px）的 20 组合计。
     * <p>
     * 🔴 这是一次<b>历史读数</b>，写死。改成「现算」的话它会跟着被测一起动，
     * 「比改前多放了多少」这条判据就永远成立
     */
    private static final int PLACED_BEFORE = 467;

    /**
     * 设计语言册（丙·星云）亮色值：accent2 / accent / cloud3 / c-guard / dim / c-sc。
     * 六个值逐字节抄自册上，不从被测那边取——被测把配色调成一片灰时判据得跟着红
     */
    private static final Color RANK_1_TO_3 = new Color(0xE0, 0x47, 0x9E);

    private static final Color RANK_4_TO_10 = new Color(0x7A, 0x4D, 0xFF);

    private static final Color RANK_11_TO_25_ODD = new Color(0xA3, 0x8B, 0xFF);

    private static final Color RANK_11_TO_25_EVEN = new Color(0x00, 0xA6, 0xD6);

    private static final Color RANK_REST = new Color(0x6E, 0x6A, 0x86);

    private static final Color RANK_REST_ACCENT = new Color(0xF2, 0xA9, 0x3B);

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
        when(api.getGuardList(anyLong(), anyLong())).thenReturn(Optional.of(List.of()));

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
     * 判据③④⑤⑥⑦与「隔多远」「放多少」「多小的字」「多大的字」：随机语料逐组量几何
     * <p>
     * 一组语料一行读数，全部合规才算绿；任何一条不合规都把那一组的实值报出来。
     * 三条跨组的判据（最小实距、落词总数、最小字号）在 20 组跑完之后一并判
     */
    @Test
    @DisplayName("判据③④⑤⑥⑦：20 组随机语料逐组量包围盒、实距、落词数、填充率、字号与配色")
    void geometryHoldsAcrossRandomCorpora() throws Exception {
        List<String> readings = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int minClearance = Integer.MAX_VALUE;
        int minFontSize = Integer.MAX_VALUE;
        int maxFontSize = 0;
        int placedTotal = 0;
        // 逐对折算出的期望间距里最小的那档——「下限有没有被走到过」拿它对照（见方法末尾）
        int minExpected = Integer.MAX_VALUE;
        // 较大字号封顶的词对：个数与最小实距。个数也要记，空集时封顶判恒真
        int topPairs = 0;
        int topPairMinClearance = Integer.MAX_VALUE;

        for (int group = 0; group < 20; group++) {
            long seed = 92_000L + group;
            int size = 5 + new Random(seed).nextInt(116);
            Map<String, Integer> words = corpus(size, seed);

            setUp();
            LiveStreamerInfo streamer = aSession();
            WordCloudLayout.Result result = painter.layoutWordCloud(PLATFORM, streamer.getUid(), words);

            int laid = Math.min(size, CLOUD_MAX_WORDS);
            double fill = result.fillRatio() * 100;

            placedTotal += result.placements().size();

            // ③ 两两隔开：≥8 下限，再往上随两词中较大的字号走（隔开 <0 即压叠）、
            //    全在框内且离框边 ≥8px
            List<WordCloudLayout.Placement> placed = result.placements();
            int groupMinClearance = Integer.MAX_VALUE;
            for (int i = 0; i < placed.size(); i++) {
                Rectangle a = placed.get(i).box();
                if (a.x < FRAME_MARGIN || a.y < FRAME_MARGIN
                        || a.x + a.width > CONTENT_WIDTH - FRAME_MARGIN
                        || a.y + a.height > CLOUD_HEIGHT - FRAME_MARGIN) {
                    failures.add("第" + group + "组「" + placed.get(i).text() + "」离框边不足 "
                            + FRAME_MARGIN + "px " + a);
                }
                for (int j = i + 1; j < placed.size(); j++) {
                    int clearance = clearance(a, placed.get(j).box());
                    groupMinClearance = Math.min(groupMinClearance, clearance);

                    int larger = Math.max(placed.get(i).fontSize(), placed.get(j).fontSize());
                    int expected = expectedGap(larger);
                    minExpected = Math.min(minExpected, expected);
                    if (clearance < expected) {
                        failures.add("第" + group + "组「" + placed.get(i).text() + "」与「"
                                + placed.get(j).text() + "」只隔开 " + clearance + "px, 较大字号 "
                                + larger + " 应 ≥" + expected + "px " + a + " 与 " + placed.get(j).box());
                    }

                    // 封顶字对单独记：这一档是「56px 相邻两词肉眼分得开」的正主
                    if (larger == FONT_SIZE_MAX) {
                        topPairs++;
                        topPairMinClearance = Math.min(topPairMinClearance, clearance);
                    }
                }
            }
            if (groupMinClearance != Integer.MAX_VALUE) {
                minClearance = Math.min(minClearance, groupMinClearance);
            }

            int groupMinFontSize = minFontSizeOf(result);
            int groupMaxFontSize = 0;
            for (WordCloudLayout.Placement placement : result.placements()) {
                minFontSize = Math.min(minFontSize, placement.fontSize());
                groupMaxFontSize = Math.max(groupMaxFontSize, placement.fontSize());
            }
            maxFontSize = Math.max(maxFontSize, groupMaxFontSize);

            int colors = (int) result.placements().stream().map(WordCloudLayout.Placement::color).distinct().count();
            readings.add(String.format("第%02d组 词数%3d 落%3d 丢%2d 填充%5.1f%% 字号%2d~%2d 首次比%5.3f 用色%d 最小实距%3s",
                    group, size, placed.size(), result.dropped(), fill,
                    groupMinFontSize, groupMaxFontSize, topRatio(result), colors,
                    groupMinClearance == Integer.MAX_VALUE ? "—" : String.valueOf(groupMinClearance)));

            // ④ 填充率；铺不满时字号必须已经放大到顶（见 FILL_EXEMPT_FONT_SIZE）
            if (fill < FILL_TARGET_PERCENT && groupMinFontSize < FILL_EXEMPT_FONT_SIZE) {
                failures.add(String.format("第%d组 填充率 %.1f%% 不足 %d%%, 而最小字号才 %d——还放得大, 不是铺不满",
                        group, fill, FILL_TARGET_PERCENT, groupMinFontSize));
            }

            // ⑤ 头名不许独吞版面
            if (topRatio(result) > TOP_FONT_SIZE_RATIO) {
                failures.add(String.format("第%d组 头名字号是第二名的 %.3f 倍, 超过 %.1f",
                        group, topRatio(result), TOP_FONT_SIZE_RATIO));
            }

            // ⑥ 色级逐名次：前 25 名逐名钉死，尾档只钉「非此即彼」，比例在下面按组数另算
            for (WordCloudLayout.Placement placement : result.placements()) {
                Color expected = expectedColor(placement.rank(), placement.color());
                if (!expected.equals(placement.color())) {
                    failures.add("第" + group + "组 第" + placement.rank() + "名「" + placement.text()
                            + "」配色 " + placement.color() + " 应为 " + expected);
                }
            }

            // ⑦ 尾档暖色恰好 3:1——每满 4 名点 1 个。
            // 🔴 不许照抄被测的抽签逻辑去比「点中的是哪一个」：那样两边同一个来路，
            // 抽签改成「一组点两个」照样两边一起变。这里只数每一组里点了几个
            failures.addAll(accentRatioFailures(group, result, laid));
        }

        // 🔴 「≥期望」只说得出下限没被破，说不出下限有没有被走到过：
        // 把间距改回「两边各涨」是隔开翻倍，一样每对都 ≥期望。要求最小实距离
        // 期望里最小的那档不超过 1px（实现里四舍五入的那一档松），下限才仍被走到过
        int placedTarget = (int) Math.ceil(PLACED_BEFORE * 1.5);
        if (minClearance > minExpected + 1) {
            failures.add("相邻词最小实距 " + minClearance + "px, 期望里最小一档是 " + minExpected
                    + "px（隔得比定的还宽得多, 可能又改回了两边各涨一半）");
        }
        // 硬线：56px 的相邻两词肉眼要分得开。固定 8px 时这一档
        // 实距就是 8（改前实读）；按系数折要 ≥0.35×56=19.6px。不取整地比——
        // 取整到 19 会把这条线悄悄降一档。一对封顶词对都没有时这条恒真, 连红都红不出来
        if (topPairs < 1) {
            failures.add("20 组语料里没有一对较大字号 " + FONT_SIZE_MAX + " 的相邻词, 封顶判落空");
        }
        if (topPairMinClearance < WORD_GAP_PER_FONT * FONT_SIZE_MAX) {
            failures.add(String.format("封顶字号 %d 的相邻词最小实距 %dpx, 应 ≥%.1fpx（固定 8px 正是这一档粘连的由来）",
                    FONT_SIZE_MAX, topPairMinClearance, WORD_GAP_PER_FONT * FONT_SIZE_MAX));
        }
        if (placedTotal < placedTarget) {
            failures.add("20 组合计落词 " + placedTotal + " 个, 不足改前 " + PLACED_BEFORE
                    + " 的 1.5 倍（" + placedTarget + "）");
        }
        if (minFontSize < READABLE_FONT_SIZE_MIN) {
            failures.add("最小字号 " + minFontSize + " 低于可读线 " + READABLE_FONT_SIZE_MIN);
        }
        if (maxFontSize > FONT_SIZE_MAX) {
            failures.add("最大字号 " + maxFontSize + " 超过上限 " + FONT_SIZE_MAX
                    + "（整体放大是乘在字号上的，只钳按词频算出来的那一档钳不住它）");
        }

        String summary = String.format("合计 落%d（改前 %d, 需 ≥%d）最小实距 %d（最小期望 %d）封顶对 %d 最小实距 %d 字号 %d~%d（上限 %d）",
                placedTotal, PLACED_BEFORE, placedTarget, minClearance, minExpected,
                topPairs, topPairMinClearance, minFontSize, maxFontSize, FONT_SIZE_MAX);
        readings.add(summary);

        Path dir = Path.of("target", "painter-output");
        Files.createDirectories(dir);
        Files.write(dir.resolve("wordcloud-geometry.txt"), String.join("\n", readings).getBytes());

        assertTrue(failures.isEmpty(), "逐组读数:\n" + String.join("\n", readings)
                + "\n不合规:\n" + String.join("\n", failures));
    }

    /**
     * 这个字号的词与邻居至少该隔多少：max(下限, round(系数 × 字号))，数字全在判据侧写死
     * <p>
     * 间距按<b>两词中较大的字号</b>折——小词挨着大词也让出大词的间距，不然挨着
     * 头名的小词照样粘上去
     */
    private static int expectedGap(int largerFontSize) {
        return Math.max(WORD_GAP, (int) Math.round(WORD_GAP_PER_FONT * largerFontSize));
    }

    /**
     * 两个包围盒之间隔开多少：两个轴上各自隔开的距离取大的那个
     * <p>
     * 取大的那个而不是欧氏距离，是因为版式规则控住的正是它——「一个框涨 {@code gap}px
     * 之后与另一个不相交」等价于「两轴之中至少有一轴隔开了 {@code gap}px」
     * （{@code gap} 即 {@link #expectedGap}）。两轴都没隔开（返回负数）就是压叠了
     */
    private static int clearance(Rectangle a, Rectangle b) {
        int dx = Math.max(b.x - (a.x + a.width), a.x - (b.x + b.width));
        int dy = Math.max(b.y - (a.y + a.height), a.y - (b.y + b.height));
        return Math.max(dx, dy);
    }

    /**
     * 判据⑧：一句刷屏弹幕不许把头名撑成一根柱子
     * <p>
     * 🔴 这一条上面那 20 组语料<b>量不到</b>：造语料时词频是 1～200 均匀取的，
     * 头两名的词频本来就挨着，压顶那一段永远碰不到。把压顶整段拆掉，20 组照样全绿。
     * 真实弹幕不长这样——同一句话刷几千遍，第二名只有几十次。
     * <p>
     * 语料要<b>足够密</b>才量得到：词少的时候整体放大会把第二名一路顶到上限，
     * 比值自己就回到 1 附近，压顶那一段仍然没被走到。这里取满额的 {@value #CLOUD_MAX_WORDS} 词
     */
    @Test
    @DisplayName("判据⑧：词频悬殊时头名字号不超过第二名的 1.3 倍")
    void topWordStaysCloseToRunnerUp() throws Exception {
        LiveStreamerInfo streamer = aSession();
        Map<String, Integer> words = new LinkedHashMap<>(corpus(CLOUD_MAX_WORDS, 92_115L));
        String flooded = words.keySet().iterator().next();
        words.put(flooded, 5000);

        WordCloudLayout.Result result = painter.layoutWordCloud(PLATFORM, streamer.getUid(), words);

        int first = fontSizeOfRank(result, 1);
        int second = fontSizeOfRank(result, 2);
        String reading = "刷屏词「" + flooded + "」" + words.get(flooded) + " 次, 头名字号 " + first
                + ", 第二名 " + second + ", 比 " + String.format("%.3f", topRatio(result))
                + "（上限 " + TOP_FONT_SIZE_RATIO + "）, 最大字号 "
                + result.placements().stream().mapToInt(WordCloudLayout.Placement::fontSize).max().orElse(0) + "\n";

        Path dir = Path.of("target", "painter-output");
        Files.createDirectories(dir);
        Files.write(dir.resolve("wordcloud-top-word.txt"), reading.getBytes());

        assertTrue(topRatio(result) <= TOP_FONT_SIZE_RATIO, reading);
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

        // 样张这一张上到底站了几个词——「密不密」是拿这张图看的，那就把这张图的实值也留下
        WordCloudLayout.Result sample = painter.layoutWordCloud(PLATFORM, streamer.getUid(), words);
        Files.write(dir.resolve("wordcloud-sample.txt"),
                ("样张语料 " + words.size() + " 词, 落 " + sample.placements().size()
                        + " 丢 " + sample.dropped() + "\n").getBytes());

        int inRing = 0;
        for (int x = 0; x < cloud.getWidth(); x++) {
            for (int y = 0; y < cloud.getHeight(); y++) {
                boolean ring = x < FRAME_MARGIN || y < FRAME_MARGIN
                        || x >= cloud.getWidth() - FRAME_MARGIN || y >= cloud.getHeight() - FRAME_MARGIN;
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

    /**
     * 这个名次该是什么颜色。第 {@value #ACCENT_FROM_RANK} 名往后是 dim 与 c-sc 两选一，
     * 单看一个词判不出对错——传进实色，是其中之一就算过，比例交给
     * {@link #accentRatioFailures} 按组数判
     */
    private static Color expectedColor(int rank, Color actual) {
        if (rank <= 3) {
            return RANK_1_TO_3;
        }
        if (rank <= 10) {
            return RANK_4_TO_10;
        }
        if (rank <= 25) {
            return rank % 2 == 1 ? RANK_11_TO_25_ODD : RANK_11_TO_25_EVEN;
        }
        return RANK_REST_ACCENT.equals(actual) ? RANK_REST_ACCENT : RANK_REST;
    }

    /**
     * 尾档暖色的比例：从第 {@value #ACCENT_FROM_RANK} 名起每 {@value #ACCENT_GROUP} 名一组，
     * 凑得满的组里恰好一个暖色
     * <p>
     * 只查<b>四名全落下了</b>的组：被丢掉的词照样占名次，组里少一个人时点没点中它无从得知
     *
     * @param laid 这一趟交给排版的词数（丢掉的也算），组够不够得着由它定
     */
    private static List<String> accentRatioFailures(int group, WordCloudLayout.Result result, int laid) {
        Map<Integer, WordCloudLayout.Placement> byRank = new LinkedHashMap<>();
        result.placements().forEach(placement -> byRank.put(placement.rank(), placement));

        List<String> failures = new ArrayList<>();
        for (int first = ACCENT_FROM_RANK; first + ACCENT_GROUP - 1 <= laid; first += ACCENT_GROUP) {
            int accents = 0;
            int present = 0;
            for (int rank = first; rank < first + ACCENT_GROUP; rank++) {
                WordCloudLayout.Placement placement = byRank.get(rank);
                if (placement == null) {
                    continue;
                }
                present++;
                if (RANK_REST_ACCENT.equals(placement.color())) {
                    accents++;
                }
            }
            if (present == ACCENT_GROUP && accents != 1) {
                failures.add("第" + group + "组 第" + first + "～" + (first + ACCENT_GROUP - 1)
                        + "名里点了 " + accents + " 个暖色, 应恰为 1");
            }
        }
        return failures;
    }

    private static int minFontSizeOf(WordCloudLayout.Result result) {
        return result.placements().stream().mapToInt(WordCloudLayout.Placement::fontSize).min().orElse(0);
    }

    /**
     * 头名字号是第二名的几倍；不足两个词时无从谈起，取 1
     */
    private static double topRatio(WordCloudLayout.Result result) {
        int first = fontSizeOfRank(result, 1);
        int second = fontSizeOfRank(result, 2);
        return first == 0 || second == 0 ? 1 : (double) first / second;
    }

    private static int fontSizeOfRank(WordCloudLayout.Result result, int rank) {
        return result.placements().stream().filter(placement -> placement.rank() == rank)
                .mapToInt(WordCloudLayout.Placement::fontSize).findFirst().orElse(0);
    }
}
