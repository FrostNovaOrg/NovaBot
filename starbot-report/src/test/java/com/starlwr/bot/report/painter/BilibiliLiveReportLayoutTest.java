package com.starlwr.bot.report.painter;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.model.BilibiliLiveMetric;
import com.starlwr.bot.bilibili.model.BilibiliLiveReportOptions;
import com.starlwr.bot.bilibili.model.GuardMember;
import com.starlwr.bot.bilibili.model.Room;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.factory.StarBotCommonPainterFactory;
import com.starlwr.bot.core.model.TextWithStyle;
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
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 下播报告的<b>版式</b>判据：文字不许画到右边距里去
 *
 * <h2>为什么要单独有这么一类判据</h2>
 * 原先所有报告判据都断言在 {@code textReport}（文字降级版）的字符串上，
 * 图片版只验「画得出来、不抛异常」。<b>没有一条判据量过任何一个像素</b>——
 * 于是 2026-08-14 那场报告里「本场收益 ¥55.3」被画出画布、只剩一个 `¥`，
 * 全套判据是绿的，是主播看到图才发现的。
 *
 * <h2>这把尺子量什么</h2>
 * 渲染出真图，扫**右边距那一条竖带**（{@code [宽-35, 宽)}）有没有墨。
 * 版式正确时那一条应当只有底色。
 * <p>
 * 🔴 它防的不是已经修好的那几处，而是<b>下一个往那一行里再加一句话的人</b>——
 * 概览行的溢出正是「加了一句『其中 N 秒因维护未采集』」造成的，
 * 而加的时候没有任何东西会告诉他这行放不下了。
 */
@DisplayName("下播报告版式")
class BilibiliLiveReportLayoutTest {
    private static final String PLATFORM = "bilibili";

    /**
     * 与 {@code BilibiliLiveReportPainter} 里的同名常量对齐
     */
    private static final int WIDTH = 900;

    private static final int MARGIN = 35;

    private BilibiliApiUtil api;

    private DefaultLiveDataService liveDataService;

    private BilibiliLiveReportPainter painter;

    private StarBotCommonPainterFactory factory;

    private FontUtil fontUtil;

    private LiveRoomInfoHistory roomInfoHistory;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void setUp() {
        StarBotCoreProperties coreProperties = new StarBotCoreProperties();
        // 用核心内置字体，免得版式结论取决于跑测试这台机器装了什么字体
        coreProperties.getPaint().getFonts().add("内置");

        fontUtil = new FontUtil(new DefaultResourceLoader(), coreProperties);
        fontUtil.init();

        Properties buildInfo = new Properties();
        buildInfo.setProperty("version", "4.3.0");
        buildInfo.setProperty("group", "com.starlwr");
        buildInfo.setProperty("artifact", "starbot-core");
        buildInfo.setProperty("name", "StarBotCore");

        factory = new StarBotCommonPainterFactory(new BuildProperties(buildInfo), coreProperties, fontUtil);

        BufferedImage placeholder = new BufferedImage(640, 360, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = placeholder.createGraphics();
        graphics.setColor(new Color(120, 170, 220));
        graphics.fillRect(0, 0, 640, 360);
        graphics.dispose();

        api = mock(BilibiliApiUtil.class);
        when(api.getBilibiliImage(anyString())).thenReturn(Optional.of(placeholder));

        Room room = new Room();
        room.setTitle("测试直播间");
        room.setCover("https://pic.example/cover.jpg");
        when(api.getLiveInfoByRoomId(anyLong())).thenReturn(room);
        when(api.getGuardList(anyLong(), anyLong())).thenReturn(Optional.of(List.of()));

        liveDataService = new DefaultLiveDataService(new StarBotCoreProperties());
        roomInfoHistory = new LiveRoomInfoHistory(new StarBotStateStore(new StarBotCoreProperties()));
        painter = new BilibiliLiveReportPainter(factory, api, liveDataService, fontUtil,
                new StarBotBilibiliProperties(), roomInfoHistory);
    }

    /**
     * 造一场与 08-14 那张截图同形的数据：有维护缺口、有收益
     * @param uname 主播名，用来单独试长名字
     */
    private LiveStreamerInfo aSessionLikeTheScreenshot(String uname) {
        LiveStreamerInfo streamer = new LiveStreamerInfo(10001L, uname, 20002L, "https://pic.example/face.jpg");

        long start = 1_700_000_000_000L;
        liveDataService.setLiveStartTime(PLATFORM, streamer.getUid(), start);
        liveDataService.setLiveEndTime(PLATFORM, streamer.getUid(), start + 3 * 3600_000 + 1202_000);
        // 就是这一句把「本场收益」顶出了画布
        liveDataService.recordDowntime(start + 600_000, start + 600_000 + 8_000);
        liveDataService.incrementLiveMetric(PLATFORM, streamer.getUid(), BilibiliLiveMetric.DANMU_COUNT, 842);
        liveDataService.recordLiveMetricUser(PLATFORM, streamer.getUid(), BilibiliLiveMetric.DANMU_USERS, 1L);
        liveDataService.incrementLiveMetric(PLATFORM, streamer.getUid(), BilibiliLiveMetric.GIFT_VALUE, 55.3);
        liveDataService.recordLiveMetricUser(PLATFORM, streamer.getUid(), BilibiliLiveMetric.GIFT_USERS, 1L);
        liveDataService.maxLiveMetric(PLATFORM, streamer.getUid(), BilibiliLiveMetric.LIKE_TOTAL, 1191);

        return streamer;
    }

    private BufferedImage render(LiveStreamerInfo streamer) throws Exception {
        return render(streamer, "layout");
    }

    private BufferedImage render(LiveStreamerInfo streamer, String name) throws Exception {
        Optional<String> base64 = painter.paint(PLATFORM, streamer);
        assertTrue(base64.isPresent(), "报告没画出来, 版式判据无从谈起");

        byte[] png = Base64.getDecoder().decode(base64.get());
        try {
            // 留一份样张给人看。判据只说得出「右边距有 350 个墨点」，说不出「难看在哪」
            Path dir = Path.of("target", "painter-output");
            Files.createDirectories(dir);
            Files.write(dir.resolve("layout-" + name + ".png"), png);
        } catch (Exception ignored) {
            // 只为人工核对，写不出去不影响判据
        }

        return ImageIO.read(new ByteArrayInputStream(png));
    }

    /**
     * 取底色
     * <p>
     * 🔴 <b>不能拿右上角那一点当底色</b>——报告是<b>圆角</b>的，四个角是透明的。
     * 第一版就是这么写的，于是「底色」取到了透明，整条带子的白全被算成墨（37929 个）：
     * 判据红得很好看，红的却不是被测对象的毛病。<b>拿一点当基准之前，得先看一眼那里是什么。</b>
     * <p>
     * 改取带内出现最多的颜色：正常时这条带子几乎全是底色，溢出几个字也压不过它。
     */
    private int backgroundOf(BufferedImage image) {
        Map<Integer, Integer> histogram = new HashMap<>();
        for (int x = WIDTH - MARGIN; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                histogram.merge(image.getRGB(x, y), 1, Integer::sum);
            }
        }

        return histogram.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
    }

    /**
     * 这一点算不算墨
     * <p>
     * 🔴 <b>只认完全不透明的点。</b>报告是圆角的，那一圈抗锯齿留下的是<b>半透明</b>的白，
     * 逐点看颜色确实与底色不同——第二版判据就把它们全算成了墨，
     * 于是「最上面一处」永远报在 y=0（画布顶角），指的却不是任何一处真的溢出。
     * <p>
     * 而正文的字画在不透明的底上，出来就是不透明的。所以「alpha 满」这一条
     * 恰好把圆角那一圈挡在外面，又不会漏掉任何一个字。
     */
    private boolean isInk(int argb, int background) {
        return argb != background && (argb >>> 24) == 0xFF;
    }

    /**
     * 数右边距竖带里有多少墨
     * @return 墨的像素数与最上面那一处的 y
     */
    private int[] inkInRightMargin(BufferedImage image) {
        int background = backgroundOf(image);

        int count = 0;
        int topmost = -1;
        for (int x = WIDTH - MARGIN; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                if (isInk(image.getRGB(x, y), background)) {
                    count++;
                    if (topmost < 0 || y < topmost) {
                        topmost = y;
                    }
                }
            }
        }

        return new int[]{count, topmost};
    }


    /**
     * 🔴 阳性对照：这把尺子确实看得见墨
     * <p>
     * 少了它，下面那条判据在「图根本没渲染出来」或「底色取错、整张图都算底色」时同样是绿的。
     */
    @Test
    @DisplayName("判据自己先能在正文区域看见墨")
    void theRulerCanSeeInk() throws Exception {
        BufferedImage image = render(aSessionLikeTheScreenshot("测试主播"));

        int background = backgroundOf(image);
        int ink = 0;
        for (int x = MARGIN; x < WIDTH - MARGIN; x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                if (isInk(image.getRGB(x, y), background)) {
                    ink++;
                }
            }
        }

        assertTrue(ink > 1000, "正文区域一共才 " + ink + " 个非底色像素, 这把尺子多半没在量真图");
    }

    /**
     * 🔴 本体：与 08-14 那张截图同形的数据，右边距里不许有墨
     * <p>
     * 修之前这条是红的：概览行溢出画布 63px，`¥55.3` 只剩一个 `¥`。
     */
    @Test
    @DisplayName("有维护缺口又有收益时，概览行不会画进右边距")
    void overviewLineStaysInsideTheMargin() throws Exception {
        BufferedImage image = render(aSessionLikeTheScreenshot("测试主播"), "overview");

        int[] ink = inkInRightMargin(image);

        assertEquals(0, ink[0],
                "右边距里有 " + ink[0] + " 个墨点, 最上面一处在 y=" + ink[1] + ", 说明有内容画出了版心");
    }

    /**
     * 长主播名同样不许顶出去
     * <p>
     * B 站昵称没有长度上限，而页头那一行原先一个字都不截。
     * 现在没爆只是因为这位主播名字短——<b>没撞上不等于没有</b>。
     */
    @Test
    @DisplayName("超长主播名不会把页头顶出版心")
    void longStreamerNameStaysInsideTheMargin() throws Exception {
        BufferedImage image = render(aSessionLikeTheScreenshot("三十个字的超长主播名字这里再补上一些字凑够三十个字看看"), "long-name");

        int[] ink = inkInRightMargin(image);

        assertEquals(0, ink[0],
                "右边距里有 " + ink[0] + " 个墨点, 最上面一处在 y=" + ink[1] + ", 长名字顶出了版心");
    }
    /**
     * 四个可选段一起出现——这是概览行能长到的最长形态
     * <p>
     * 实测这一行的文本宽 1851px，<b>比画布本身还宽一倍</b>。
     * 只修「当前这场恰好溢出 63px」是不够的：这一行本来就没有上界。
     */
    @Test
    @DisplayName("维护缺口、断线缺口、图片未送达与收益同时出现时也不溢出")
    void overviewLineWithEveryOptionalClauseStaysInside() throws Exception {
        LiveStreamerInfo streamer = aSessionLikeTheScreenshot("测试主播");
        liveDataService.recordRoomOutage(PLATFORM, streamer.getUid(),
                1_700_000_000_000L + 1_200_000, 1_700_000_000_000L + 1_272_000);
        liveDataService.incrementLiveMetric(PLATFORM, streamer.getUid(), BilibiliLiveMetric.IMAGE_DEGRADED_COUNT, 3);

        BufferedImage image = render(streamer, "overview-full");

        int[] ink = inkInRightMargin(image);

        assertEquals(0, ink[0],
                "右边距里有 " + ink[0] + " 个墨点, 最上面一处在 y=" + ink[1] + ", 概览行最长形态顶出了版心");
    }

    /**
     * 数字很大的那一场
     * <p>
     * 卡片上的数值与排行榜右侧的得分都是<b>直接由数据决定长度</b>的，
     * 而版面是按「这位主播的数不大」排的。大主播的一场就能把它们撑破。
     */
    @Test
    @DisplayName("金额与人数都很大的场次不会撑破卡片与榜单")
    void bigNumbersStayInside() throws Exception {
        LiveStreamerInfo streamer = new LiveStreamerInfo(10001L, "测试主播", 20002L, "https://pic.example/face.jpg");

        long start = 1_700_000_000_000L;
        liveDataService.setLiveStartTime(PLATFORM, streamer.getUid(), start);
        liveDataService.setLiveEndTime(PLATFORM, streamer.getUid(), start + 8 * 3600_000);
        liveDataService.incrementLiveMetric(PLATFORM, streamer.getUid(), BilibiliLiveMetric.DANMU_COUNT, 1234567);
        for (long user = 1; user <= 3; user++) {
            liveDataService.recordLiveMetricUser(PLATFORM, streamer.getUid(), BilibiliLiveMetric.DANMU_USERS, user);
            liveDataService.recordLiveMetricUser(PLATFORM, streamer.getUid(), BilibiliLiveMetric.GIFT_USERS, user);
        }
        // 12 个字符的金额：卡片正文只有 223px 可用，这个数装不下
        liveDataService.incrementLiveMetric(PLATFORM, streamer.getUid(), BilibiliLiveMetric.GIFT_VALUE, 123456789.8);
        liveDataService.incrementLiveMetric(PLATFORM, streamer.getUid(), BilibiliLiveMetric.SUPER_CHAT_COUNT, 4321);
        liveDataService.incrementLiveMetric(PLATFORM, streamer.getUid(), BilibiliLiveMetric.SUPER_CHAT_VALUE, 987654.3);
        liveDataService.incrementLiveMetric(PLATFORM, streamer.getUid(), BilibiliLiveMetric.BOX_COUNT, 98765);
        liveDataService.incrementLiveMetric(PLATFORM, streamer.getUid(), BilibiliLiveMetric.BOX_PROFIT, -54321.6);
        liveDataService.maxLiveMetric(PLATFORM, streamer.getUid(), BilibiliLiveMetric.LIKE_TOTAL, 9876543);

        BufferedImage image = render(streamer, "big-numbers");

        int[] margin = inkInRightMargin(image);
        assertEquals(0, margin[0],
                "右边距里有 " + margin[0] + " 个墨点, 最上面一处在 y=" + margin[1] + ", 大数字撑破了版心");

        int[] gap = inkInCardGaps(image);
        assertEquals(0, gap[0],
                "卡片之间的缝里有 " + gap[0] + " 个墨点, 最上面一处在 y=" + gap[1] + ", 大数字盖到了隔壁卡片");
    }
    /**
     * 页头还有一套<b>不带封面</b>的版式，走的是另一个分支
     * <p>
     * 这条是被「先失败一次」逼出来的：我把不带封面那一支的截断去掉，
     * 五条判据<b>一条都没红</b>——因为它们渲染的全是带封面的那一支。
     * 判据没抓住不是它量错了，是<b>那条路它根本没走过</b>。
     */
    @Test
    @DisplayName("不带封面的页头版式，长主播名同样不顶出版心")
    void longStreamerNameStaysInsideWithoutCover() throws Exception {
        LiveStreamerInfo streamer = aSessionLikeTheScreenshot("三十个字的超长主播名字这里再补上一些字凑够三十个字看看");

        // 版式选项是只读的，按它自己的入口从推送参数解析
        com.alibaba.fastjson2.JSONObject params = new com.alibaba.fastjson2.JSONObject();
        params.put("cover", false);
        BilibiliLiveReportOptions options = BilibiliLiveReportOptions.of(params, true);
        assertFalse(options.isCover(), "这条判据要的就是不带封面的那一支, 选项没关掉就什么也没验到");

        Optional<String> base64 = painter.paint(PLATFORM, streamer, options);
        assertTrue(base64.isPresent(), "报告没画出来, 版式判据无从谈起");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(
                Base64.getDecoder().decode(base64.get())));

        int[] ink = inkInRightMargin(image);

        assertEquals(0, ink[0],
                "右边距里有 " + ink[0] + " 个墨点, 最上面一处在 y=" + ink[1] + ", 无封面版式的长名字顶出了版心");
    }

    /**
     * 🔴 换行要断在<b>片段边界</b>上，不能断在字中间
     * <p>
     * 右边距那把尺子看不出这件事：逐字换行同样不溢出、同样是绿的。
     * 但逐字断出来的是「本场收益 ¥55.」加下一行一个「3」——
     * <b>一个金额被劈成两行，比溢出还难认</b>。所以这条直接看断在哪。
     */
    @Test
    @DisplayName("换行断在片段之间，不把一句话劈成两半")
    void wrappingBreaksBetweenSegments() {
        List<TextWithStyle> line = List.of(
                new TextWithStyle("直播时长 ", 30, Color.BLACK, Font.PLAIN),
                new TextWithStyle("3 时 20 分 2 秒", 30, Color.BLACK, Font.BOLD),
                new TextWithStyle("（其中 8 秒因维护未采集）", 30, Color.BLACK, Font.PLAIN),
                new TextWithStyle("    本场收益 ", 30, Color.BLACK, Font.PLAIN),
                new TextWithStyle("¥55.3", 30, Color.BLACK, Font.BOLD));

        List<TextWithStyle> wrapped = painter.wrapAtSegments(factory.create(WIDTH, 400, false), line, MARGIN);

        assertEquals(line.size(), wrapped.size(), "片段不该被拆开或合并, 只是在其间插换行");

        int breaks = 0;
        for (int i = 0; i < wrapped.size(); i++) {
            String text = wrapped.get(i).getText();
            if (text.startsWith("\n")) {
                breaks++;
                assertEquals(line.get(i).getText().stripLeading(), text.substring(1),
                        "换行后那一段的文字必须原封不动（只去掉用来拉开间距的前导空格）");
            } else {
                assertEquals(line.get(i).getText(), text, "没换行的片段不该被改动");
            }
        }

        assertTrue(breaks > 0, "这一行实测宽 928px, 版心只有 830px, 应当断开一次");
        assertTrue(wrapped.get(4).getText().endsWith("¥55.3"), "金额必须完整留在一行里, 不许被劈开");
    }
    /**
     * 概览行第一行右侧应当留下明显空白
     * <p>
     * 🔴 这条补的是「片段边界换行」<b>有没有真的被用上</b>。
     * {@link #wrappingBreaksBetweenSegments()} 证的是那个函数断得对，
     * 但函数写对了、调用处没用它，右边距那把尺子照样全绿——
     * 逐字换行同样不溢出。这与「桶实现得很对但没人调它」是同一族。
     * <p>
     * 两种断法在像素上是分得出的：逐字断会<b>一直填到版心右边界才断</b>，
     * 片段边界断则在最后一个装得下的片段之后就停，右边留出一大截空白。
     */
    @Test
    @DisplayName("概览行第一行在片段处就断开，不是填满到边界才断")
    void overviewFirstRowBreaksEarlyNotAtTheEdge() throws Exception {
        BufferedImage image = render(aSessionLikeTheScreenshot("测试主播"), "overview-break");

        int background = backgroundOf(image);
        int[] ink = firstOverviewRow(image, background);
        int rowTop = ink[0];
        int rightmost = ink[1];

        assertTrue(rowTop > 0, "没找到概览行, 这条判据什么也没验到");
        // 逐字断法会一直画到 865 附近；片段断法停在「（其中 8 秒因维护未采集）」之后
        assertTrue(rightmost < WIDTH - MARGIN - 60,
                "概览行第一行画到了 x=" + rightmost + ", 几乎顶到版心右边界 "
                        + (WIDTH - MARGIN) + "——像是逐字断的, 不是在片段边界断的");
    }

    /**
     * 找概览行的第一行：从页头往下第一段有墨的行
     * @return {行首 y, 该行最右侧墨点的 x}
     */
    private int[] firstOverviewRow(BufferedImage image, int background) {
        // 概览行在页头之后。从卡片顶端往上找不方便，直接从「直播时长」那一行的已知区间扫：
        // 页头最高 COVER_HEIGHT + 头像，取 350 起够安全，卡片在 470 之后
        for (int y = 380; y < 470; y++) {
            int rightmost = -1;
            for (int x = MARGIN; x < WIDTH - MARGIN; x++) {
                if (isInk(image.getRGB(x, y), background)) {
                    rightmost = x;
                }
            }
            if (rightmost > 0) {
                // 找到行首后，取这一行整段（约一个字高）里最右的墨
                int widest = rightmost;
                for (int yy = y; yy < Math.min(y + 40, image.getHeight()); yy++) {
                    for (int x = WIDTH - MARGIN - 1; x > widest; x--) {
                        if (isInk(image.getRGB(x, yy), background)) {
                            widest = x;
                            break;
                        }
                    }
                }
                return new int[]{y, widest};
            }
        }

        return new int[]{-1, -1};
    }

    /**
     * 卡片上的数字大到撑破卡片时，不许盖到隔壁或画出版心
     * <p>
     * 卡片溢出与概览行溢出不是一回事：它<b>盖到旁边那张卡片上</b>，比被画布干净切掉更难认。
     * 实测余量只剩个位数像素——「粉丝团 · 本场 +12345」离撑破只差 9px。
     */
    @Test
    @DisplayName("本场变化里出现夸张涨幅时不撑破卡片")
    void hugeFansChangeStaysInsideItsCard() throws Exception {
        LiveStreamerInfo streamer = aSessionLikeTheScreenshot("测试主播");

        // 三张卡片里最右那张落在版心右沿，撑破它就会画进右边距
        liveDataService.setLiveMetric(PLATFORM, streamer.getUid(), BilibiliLiveMetric.FANS_AT_START, 1);
        liveDataService.setLiveMetric(PLATFORM, streamer.getUid(), BilibiliLiveMetric.FANS_MEDAL_AT_START, 1);
        liveDataService.setLiveMetric(PLATFORM, streamer.getUid(), BilibiliLiveMetric.GUARD_AT_START, 1);
        when(api.getFansCount(anyLong())).thenReturn(Optional.of(123456789L));
        when(api.getFansMedalCount(anyLong())).thenReturn(Optional.of(123456789));
        when(api.getGuardCount(anyLong(), anyLong())).thenReturn(Optional.of(123456789));

        BufferedImage image = render(streamer, "huge-change");

        int[] margin = inkInRightMargin(image);
        assertEquals(0, margin[0],
                "右边距里有 " + margin[0] + " 个墨点, 最上面一处在 y=" + margin[1] + ", 卡片撑到了版心外");

        int[] gap = inkInCardGaps(image);
        assertEquals(0, gap[0],
                "卡片之间的缝里有 " + gap[0] + " 个墨点, 最上面一处在 y=" + gap[1] + ", 有卡片盖到了隔壁");
    }

    @Test
    @DisplayName("全名单段写出三位长名字时不画进右边距")
    void fullGuardRosterStaysInsideTheCanvas() throws Exception {
        String longName = "甲".repeat(40);
        painter = new BilibiliLiveReportPainter(factory, api, liveDataService, fontUtil,
                new StarBotBilibiliProperties(), roomInfoHistory) {
            @Override
            protected Optional<List<GuardMember>> guardList(Long roomId, Long uid) {
                return Optional.of(List.of(
                        new GuardMember(11L, longName + "总督", 1, 300),
                        new GuardMember(22L, longName + "提督", 2, 200),
                        new GuardMember(33L, longName + "舰长", 3, 100)));
            }
        };

        BufferedImage image = render(aSessionLikeTheScreenshot("测试主播"), "guard-roster");
        int[] margin = inkInRightMargin(image);
        assertEquals(0, margin[0],
                "右边距里有 " + margin[0] + " 个墨点, 最上面一处在 y=" + margin[1] + ", 全名单昵称画到了版心外");
    }

    /**
     * 数「卡片之间那道缝」里有多少墨
     * <p>
     * 🔴 右边距那把尺子看不见这件事：卡片撑破时字是<b>盖到旁边那张卡片上</b>的，
     * 只有最右一列才会画到版心外。这条补的就是中间两列。
     * <p>
     * 只在卡片所在的那些行上看：判定方式是「这一行在第一张卡片内部有卡片底色」，
     * 否则同样的 x 区间在别处会撞上比例条与词云。
     * @return 墨的像素数与最上面那一处的 y
     */
    private int[] inkInCardGaps(BufferedImage image) {
        int background = backgroundOf(image);

        // 与画笔里的常量对齐：内容宽 830、三列、列间距 18
        int cardWidth = (830 - 18 * 2) / 3;
        int[] gapStarts = {MARGIN + cardWidth, MARGIN + cardWidth * 2 + 18};

        int count = 0;
        int topmost = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            // 只认第一张卡片左内缘正好是卡片底色的那些行。
            // 换成「不是页底色就算」会把封面横幅与排行榜的比例条一起收进来（试过，9759 个假墨点）
            if (image.getRGB(MARGIN + 6, y) != BilibiliLiveReportPainter.COLOR_CARD.getRGB()) {
                continue;
            }

            for (int gapStart : gapStarts) {
                for (int x = gapStart; x < gapStart + 18; x++) {
                    if (isInk(image.getRGB(x, y), background)) {
                        count++;
                        if (topmost < 0) {
                            topmost = y;
                        }
                    }
                }
            }
        }

        return new int[]{count, topmost};
    }
}
