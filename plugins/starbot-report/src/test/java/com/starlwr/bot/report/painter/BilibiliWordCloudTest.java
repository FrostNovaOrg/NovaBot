package com.starlwr.bot.report.painter;

import com.starlwr.bot.bilibili.config.NovaBilibiliProperties;
import com.starlwr.bot.bilibili.model.BilibiliLiveMetric;
import com.starlwr.bot.bilibili.model.Room;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.config.NovaCoreProperties;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.service.DefaultLiveDataService;
import com.starlwr.bot.core.service.LiveRoomInfoHistory;
import com.starlwr.bot.core.service.NovaStateStore;
import com.starlwr.bot.report.factory.NovaCommonPainterFactory;
import com.starlwr.bot.report.util.FontUtil;
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
 *
 * <h2>换了一版排版规则之后，这里的判据整类重量过</h2>
 * 新版排版器按锚点撒主词、全画布评分找位，间距改成<b>横纵两轴分开</b>算，
 * 也不再为了填满外包围盒去整体放大字号。于是三族旧判据连同它们量的那件事一起退休：
 * 「词距＝0.35×较大字号」（换成两轴各一条线）、「外包围盒占满 85%」
 * （新版明写 fillRatio 只作统计、不再是目标，版面用没用起来改由落词总数判，
 * 门槛同时从改前的 1.5 倍提到 2.0 倍）、
 * 「第 26 名起每 4 个点 1 个暖色」（换成按名次分档 + 尾档交错取色）。
 * <b>留在这里的每一条都在新版上重新跑出过读数</b>，不是照抄上一版。
 * 词量分档与场景适应性另有 {@code WordCloudHeightTest} 与 {@code WordCloudScenarioTest} 两把尺。
 */
@DisplayName("弹幕词云几何")
class BilibiliWordCloudTest {
    private static final String PLATFORM = "bilibili";

    /**
     * 与 {@code BilibiliLiveReportPainter} 里的同名常量对齐
     */
    private static final int CONTENT_WIDTH = 830;

    private static final int CLOUD_MAX_HEIGHT = 380;

    private static final int CLOUD_MAX_WORDS = 72;

    /**
     * 🔴 <b>下面这几个数字写死在判据里，不许从被测那边取。</b>
     * 引 {@code WordCloudLayout.FRAME_MARGIN} 看着更「不重复」，
     * 可那样一来把留白改成 0，判据会跟着一起松开——判据就再也逮不住
     * 它本来要逮的那件事了
     * <p>
     * 相邻两词至少隔开：横 {@value #MIN_GAP_X}px <b>或</b>纵 {@value #MIN_GAP_Y}px。
     * 两轴取「或」而不是「与」，是因为词是横排的：上下两行字挨得近仍分得开，
     * 左右两个词贴在一起才会被读成一个词
     */
    private static final int MIN_GAP_X = 6;

    private static final int MIN_GAP_Y = 5;

    private static final int FRAME_MARGIN = 24;

    /**
     * 词云允许的最小字号。报告里最小的正文字号是 22，词云尾词比正文再小一档尚可读，
     * 更小就只剩一团色块了——这条是下限，不是当前实现的写照
     */
    private static final int READABLE_FONT_SIZE_MIN = 18;

    /**
     * 词云允许的最大字号，量的是<b>落到图上的字号</b>
     * <p>
     * 🔴 这一条不是「按词频算出来那一档别调大」的另一种写法。上一版排版为了填满框
     * 会把全体字号整体乘一个倍率，只钳基准那一档时，图上真正量到的是 99——
     * 「上限」写在源码里而版式规则并不遵守它
     */
    private static final int FONT_SIZE_MAX = 56;

    /**
     * 头名字号最多是第二名的多少倍。词频悬殊的场次里，线性映射会让头名独吞版面
     */
    private static final double TOP_FONT_SIZE_RATIO = 1.3;

    /**
     * 改前同一批语料的落词总数：**2026-09-04 实测于 lane-c 3c03438**
     * （字号按 √词频、词与词隔 16px）的 20 组合计。
     * <p>
     * 🔴 这是一次<b>历史读数</b>，写死。改成「现算」的话它会跟着被测一起动，
     * 「比改前多放了多少」这条判据就永远成立
     */
    private static final int PLACED_BEFORE = 467;

    /**
     * 落词总数至少要是改前的这个倍数。**2026-09-08 于新版排版器实测 1079**（改前 467 的 2.31 倍），
     * 取 2.0 留一成半余量
     * <p>
     * 这一条接下上一版「外包围盒占满 85%」的班：那一条问的是<b>版面有没有被用起来</b>，
     * 而外包围盒撑得开并不等于里面站满了词——新版明写 fillRatio 只作统计、不再是目标，
     * 换成直接数落了几个词
     */
    private static final double PLACED_TARGET_RATIO = 2.0;

    /**
     * 设计语言册（丙·星云）亮色值：accent2 / accent / cloud3 / c-guard / dim / c-sc。
     * 六个值逐字节抄自册上，不从被测那边取——被测把配色调成一片灰时判据得跟着红
     */
    private static final Color RANK_1_TO_3 = new Color(0xE0, 0x47, 0x9E);

    private static final Color RANK_4_TO_10 = new Color(0x7A, 0x4D, 0xFF);

    private static final Color CLOUD3 = new Color(0xA3, 0x8B, 0xFF);

    private static final Color C_GUARD = new Color(0x00, 0xA6, 0xD6);

    private static final Color DIM = new Color(0x6E, 0x6A, 0x86);

    private static final Color C_SC = new Color(0xF2, 0xA9, 0x3B);

    /**
     * 第 11 名往后可用的四个值。单看一个词判不出该是哪一个，只判「在不在册上」；
     * 「够不够花」由每组的用色数另判（见 {@link #MIN_DISTINCT_COLORS}）
     */
    private static final List<Color> TAIL_COLORS = List.of(CLOUD3, C_GUARD, DIM, C_SC);

    /**
     * 词多的组里至少要用到几种颜色。**实测 20 组皆为 6**，取 5 留一档余量——
     * 尾档全部取同一个值时这一条红，而「配色在册」那一条照样绿
     */
    private static final int MIN_DISTINCT_COLORS = 5;

    /**
     * 用色数这一条从多少个词起判：词太少时名次不够，用不满六档也是对的
     */
    private static final int DISTINCT_COLOR_FROM_SIZE = 26;

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
        buildInfo.setProperty("group", "com.starlwr");
        buildInfo.setProperty("artifact", "starbot-core");
        buildInfo.setProperty("name", "StarBotCore");
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

    // 判据②「词数 4 不出词云、5 出」随「少于 5 词整块不画」那条产品规则一并退休：
    // 现在 0 个词也画（画成空态），词少只是块矮一档。接它的是
    // WordCloudReportBlockTest——那把尺子量的是同一件事的另一头：块画没画、块占多高

    /**
     * 判据③④⑤⑥⑦与「隔多远」「放多少」「多小的字」「多大的字」：随机语料逐组量几何
     * <p>
     * 一组语料一行读数，全部合规才算绿；任何一条不合规都把那一组的实值报出来。
     * 三条跨组的判据（最小实距、落词总数、最小字号）在 20 组跑完之后一并判。
     * 20 组的种子与词数与上一版排版器那一轮<b>逐个相同</b>，落词总数才比得出「比改前多放了多少」
     */
    @Test
    @DisplayName("判据③④⑤⑥⑦：20 组随机语料逐组量包围盒、实距、落词数、内部空洞、字号与配色")
    void geometryHoldsAcrossRandomCorpora() throws Exception {
        List<String> readings = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int minClearance = Integer.MAX_VALUE;
        int minFontSize = Integer.MAX_VALUE;
        int maxFontSize = 0;
        int placedTotal = 0;
        int worstHole = 0;
        // 词多的组数也要记：一组都没有时「内部空洞」那一条恒真, 连红都红不出来
        int denseGroups = 0;

        for (int group = 0; group < 20; group++) {
            long seed = 92_000L + group;
            int size = 5 + new Random(seed).nextInt(116);
            Map<String, Integer> words = corpus(size, seed);

            setUp();
            LiveStreamerInfo streamer = aSession();
            WordCloudLayout.Result result = painter.layoutWordCloud(PLATFORM, streamer.getUid(), words);

            int laid = Math.min(size, CLOUD_MAX_WORDS);
            // 画布高度按词量分档。🔴 分档表写在判据侧，不问被测——问被测的话，
            // 分档改成一律 380px 时越界判会跟着一起挪，永远量不到越界
            int height = bandHeight(laid);

            placedTotal += result.placements().size();

            // ③ 两两隔开：横 ≥6px 或纵 ≥5px（两轴都不足即粘连，两轴都为负即压叠）、
            //    全在框内且离框边 ≥24px
            List<WordCloudLayout.Placement> placed = result.placements();
            int groupMinClearance = Integer.MAX_VALUE;
            for (int i = 0; i < placed.size(); i++) {
                Rectangle a = placed.get(i).box();
                if (a.x < FRAME_MARGIN || a.y < FRAME_MARGIN
                        || a.x + a.width > CONTENT_WIDTH - FRAME_MARGIN
                        || a.y + a.height > height - FRAME_MARGIN) {
                    failures.add("第" + group + "组「" + placed.get(i).text() + "」离框边不足 "
                            + FRAME_MARGIN + "px " + a + "（框 " + CONTENT_WIDTH + "×" + height + "）");
                }
                for (int j = i + 1; j < placed.size(); j++) {
                    Rectangle b = placed.get(j).box();
                    int gapX = gap(a.x, a.width, b.x, b.width);
                    int gapY = gap(a.y, a.height, b.y, b.height);
                    groupMinClearance = Math.min(groupMinClearance, Math.max(gapX, gapY));

                    if (gapX < MIN_GAP_X && gapY < MIN_GAP_Y) {
                        failures.add("第" + group + "组「" + placed.get(i).text() + "」与「"
                                + placed.get(j).text() + "」横隔 " + gapX + "px 纵隔 " + gapY
                                + "px, 两轴都不足（" + MIN_GAP_X + "／" + MIN_GAP_Y + "）"
                                + a + " 与 " + b);
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

            int hole = largestInteriorHole(result);
            int colors = (int) result.placements().stream().map(WordCloudLayout.Placement::color).distinct().count();
            readings.add(String.format("第%02d组 词数%3d 落%3d 丢%2d 框高%3d 字号%2d~%2d 首次比%5.3f 用色%d 最小实距%3s 最大空洞%3d",
                    group, size, placed.size(), result.dropped(), height,
                    groupMinFontSize, groupMaxFontSize, topRatio(result), colors,
                    groupMinClearance == Integer.MAX_VALUE ? "—" : String.valueOf(groupMinClearance), hole));

            // ④ 内部空洞：只留读数，<b>没有立成判据</b>。
            // 🔴 阈值定完要验它还抓不抓得住洞：把新版的局部微调那一段（refineInterior）
            // 拆掉再跑同一批语料，逐组读数确实升了（第18组 12→17、第16组 11→15、第02组 20→25），
            // 可 20 组里最大的那个仍是 25（第19组两边都是 25）——按「最大空洞 ≤ 阈值」立的闸
            // 一次都拦不住它。拦不住的闸和没有这条判据长得一样，故只落读数不断言
            if (placed.size() >= 40) {
                denseGroups++;
                worstHole = Math.max(worstHole, hole);
            }

            // ⑤ 头名不许独吞版面
            if (topRatio(result) > TOP_FONT_SIZE_RATIO) {
                failures.add(String.format("第%d组 头名字号是第二名的 %.3f 倍, 超过 %.1f",
                        group, topRatio(result), TOP_FONT_SIZE_RATIO));
            }

            // ⑥ 配色只许取册上在册的六个值：前 3 名与 4～10 名逐名钉死，
            // 第 11 名往后钉「在那四个里」——取哪一个由名次交错定，逐名钉死就成了照抄实现
            for (WordCloudLayout.Placement placement : result.placements()) {
                String wrong = colorFailure(placement);
                if (wrong != null) {
                    failures.add("第" + group + "组 第" + placement.rank() + "名「" + placement.text() + "」" + wrong);
                }
            }

            // ⑦ 够不够花：尾档四个值全取成同一个时，上面那一条照样绿
            if (placed.size() >= DISTINCT_COLOR_FROM_SIZE && colors < MIN_DISTINCT_COLORS) {
                failures.add("第" + group + "组 落了 " + placed.size() + " 个词却只用了 " + colors
                        + " 种颜色, 应 ≥" + MIN_DISTINCT_COLORS);
            }
        }

        // 🔴 「≥下限」只说得出下限没被破，说不出下限有没有被走到过：
        // 把间距整体放宽一倍，一样每对都 ≥下限。最小实距要贴着纵向那条线，
        // 下限才仍被走到过
        int placedTarget = (int) Math.ceil(PLACED_BEFORE * PLACED_TARGET_RATIO);
        if (minClearance > MIN_GAP_Y + 2) {
            failures.add("相邻词最小实距 " + minClearance + "px, 而定的下限是横 " + MIN_GAP_X
                    + "／纵 " + MIN_GAP_Y + "px（隔得比定的宽得多, 版面白让出去了）");
        }
        if (denseGroups < 1) {
            failures.add("20 组语料里没有一组落满 40 个词, 密集场次这一档没被走到");
        }
        if (placedTotal < placedTarget) {
            failures.add("20 组合计落词 " + placedTotal + " 个, 不足改前 " + PLACED_BEFORE
                    + " 的 " + PLACED_TARGET_RATIO + " 倍（" + placedTarget + "）");
        }
        if (minFontSize < READABLE_FONT_SIZE_MIN) {
            failures.add("最小字号 " + minFontSize + " 低于可读线 " + READABLE_FONT_SIZE_MIN);
        }
        if (maxFontSize > FONT_SIZE_MAX) {
            failures.add("最大字号 " + maxFontSize + " 超过上限 " + FONT_SIZE_MAX);
        }

        String summary = String.format("合计 落%d（改前 %d, 需 ≥%d）最小实距 %d（下限 横%d／纵%d）密集组 %d 最大空洞 %d（只读数不判）字号 %d~%d（上限 %d）",
                placedTotal, PLACED_BEFORE, placedTarget, minClearance, MIN_GAP_X, MIN_GAP_Y,
                denseGroups, worstHole, minFontSize, maxFontSize, FONT_SIZE_MAX);
        readings.add(summary);

        Path dir = Path.of("target", "painter-output");
        Files.createDirectories(dir);
        Files.write(dir.resolve("wordcloud-geometry.txt"), String.join("\n", readings).getBytes());

        assertTrue(failures.isEmpty(), "逐组读数:\n" + String.join("\n", readings)
                + "\n不合规:\n" + String.join("\n", failures));
    }

    /**
     * 词云块按词量该有多高。分档表与 {@code WordCloudHeightTest} 同源、都写死在判据侧
     */
    private static int bandHeight(int wordCount) {
        if (wordCount == 0) {
            return 96;
        }
        if (wordCount <= 3) {
            return 128;
        }
        if (wordCount <= 8) {
            return 200;
        }
        if (wordCount <= 12) {
            return 240;
        }
        if (wordCount <= 24) {
            return 300;
        }
        return CLOUD_MAX_HEIGHT;
    }

    /**
     * 词团内部最大的空洞，单位像素：在外包围盒的<b>内接椭圆</b>里按 5px 网格取样，
     * 量每个取样点到最近的词包围盒有多远，取最大的那个
     * <p>
     * 只量椭圆里那一块，是因为外包围盒的四角本来就该空着——词团是圆的，
     * 拿整个矩形去量，四角那几十像素会把读数顶满，这条判据就再也动不了了。
     * <p>
     * 🔴 取样网格<b>与被测自己那套探针不同源</b>：被测按画布中心的 0.38 椭圆、7px 网格采，
     * 这里按<b>实际外包围盒</b>的 0.75 椭圆、5px 网格采。照抄被测那套的话，
     * 它把探针挪个位置，判据就跟着挪，量到的永远是它自己认可的那片区域
     */
    private static int largestInteriorHole(WordCloudLayout.Result result) {
        List<WordCloudLayout.Placement> placed = result.placements();
        if (placed.size() < 2) {
            return 0;
        }

        Rectangle bounds = result.bounds();
        double centerX = bounds.getCenterX();
        double centerY = bounds.getCenterY();
        double radiusX = bounds.width * 0.5 * 0.75;
        double radiusY = bounds.height * 0.5 * 0.75;
        if (radiusX <= 0 || radiusY <= 0) {
            return 0;
        }

        double worst = 0;
        for (int y = bounds.y; y <= bounds.getMaxY(); y += 5) {
            for (int x = bounds.x; x <= bounds.getMaxX(); x += 5) {
                double normalizedX = (x - centerX) / radiusX;
                double normalizedY = (y - centerY) / radiusY;
                if (normalizedX * normalizedX + normalizedY * normalizedY > 1) {
                    continue;
                }
                double nearest = Double.POSITIVE_INFINITY;
                for (WordCloudLayout.Placement placement : placed) {
                    nearest = Math.min(nearest, distanceToBox(x, y, placement.box()));
                }
                worst = Math.max(worst, nearest);
            }
        }
        return (int) Math.round(worst);
    }

    /**
     * 一个点到一个矩形有多远；点在矩形里时为 0
     */
    private static double distanceToBox(int x, int y, Rectangle box) {
        double dx = Math.max(0, Math.max(box.x - x, x - box.getMaxX()));
        double dy = Math.max(0, Math.max(box.y - y, y - box.getMaxY()));
        return Math.hypot(dx, dy);
    }

    /**
     * 两个区间在一根轴上隔开多少；重叠时为负
     */
    private static int gap(int aStart, int aLength, int bStart, int bLength) {
        return Math.max(bStart - (aStart + aLength), aStart - (bStart + bLength));
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
     * 判据③的成图一面：四周 {@value #FRAME_MARGIN}px 内不许有墨
     * <p>
     * 几何上框已经离边 {@value #FRAME_MARGIN}px，这一条量的是<b>画出来的墨真的在框里</b>——
     * 量与画对不上时，几何是绿的而图上照样压边
     */
    @Test
    @DisplayName("判据③（成图）：墨迹距框四周至少留 24px")
    void keepsFrameMarginFromInk() throws Exception {
        LiveStreamerInfo streamer = aSession();
        Map<String, Integer> words = corpus(60, 92_007L);
        BufferedImage cloud = painter.paintWordCloud(PLATFORM, streamer.getUid(), words);

        assertEquals(CONTENT_WIDTH, cloud.getWidth());
        assertEquals(CLOUD_MAX_HEIGHT, cloud.getHeight());

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

        assertEquals(0, inRing, "四周 " + FRAME_MARGIN + "px 内不应有墨, 实际 " + inRing + " 点");
    }

    /**
     * 词一个都没有时不该炸，排出来的是一个空版式
     * <p>
     * 成图那一面反过来：空版式要画出「暂无有效弹幕词」的空态，
     * 由 {@code WordCloudScenarioTest} 量
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
     * 这个词的配色不对在哪；对的话返回 {@code null}
     * <p>
     * 前 3 名与 4～10 名逐名钉死；第 11 名往后只钉「取的是册上那四个值之一」——
     * 取哪一个由名次交错定，逐名照抄那套交错规则的话，两边就成了同一个来路，
     * 交错改成「一律取灰」两边会一起变。「够不够花」另有用色数那一条判
     */
    private static String colorFailure(WordCloudLayout.Placement placement) {
        Color actual = placement.color();
        if (placement.rank() <= 3) {
            return RANK_1_TO_3.equals(actual) ? null : "配色 " + actual + " 应为 " + RANK_1_TO_3;
        }
        if (placement.rank() <= 10) {
            return RANK_4_TO_10.equals(actual) ? null : "配色 " + actual + " 应为 " + RANK_4_TO_10;
        }
        return TAIL_COLORS.contains(actual) ? null : "配色 " + actual + " 不在册上的四个尾档值里";
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
