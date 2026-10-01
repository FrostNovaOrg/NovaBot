package org.frostnova.nova.report.painter;

import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.model.BilibiliLiveMetric;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.frostnova.nova.bilibili.model.GuardMember;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 下播报告改版·乙：流水卡与大航海三处的说法（对现有入口可编译的那一半判据）
 * <p>
 * 这里每一格都走<b>现已存在</b>的入口（{@code buildCards}、{@code textReport}、{@code paint}），
 * 为的是改版之前它们红在<b>断言</b>上——「图上还没有这块」，而不是红在编译器上。
 * 需要新入口的那一半（曲线合成、醒目留言名单原文、流水榜回落）在
 * {@code BilibiliLiveReportRevenueLayoutTest}，那一把先红是编译红。
 * <p>
 * 指标名写字面量的理由见 {@code BilibiliRevenueUsersMetricTest} 的类注释。
 */
@DisplayName("下播报告流水卡与大航海三处说法")
class BilibiliLiveReportRevenueCardsTest {
    private static final String PLATFORM = "bilibili";

    private static final LiveStreamerInfo STREAMER = new LiveStreamerInfo(10001L, "测试主播", 20002L, "https://pic.example/face.jpg");

    /**
     * 分人流水表；与礼物、醒目留言的分人表同族。写面值的理由见类注释
     */
    private static final String REVENUE_USERS = "revenue_users";

    private BilibiliApiUtil api;

    private NovaCommonPainterFactory factory;

    private FontUtil fontUtil;

    private DefaultLiveDataService liveDataService;

    private BilibiliLiveReportPainter painter;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void setUp() {
        NovaCoreProperties coreProperties = new NovaCoreProperties();
        coreProperties.getPaint().getFonts().add("内置");

        fontUtil = new FontUtil(new DefaultResourceLoader(), coreProperties);
        fontUtil.init();

        Properties buildInfo = new Properties();
        buildInfo.setProperty("version", "4.0.0");
        buildInfo.setProperty("group", "com." + "starlwr");
        buildInfo.setProperty("artifact", "nova-core");
        buildInfo.setProperty("name", "NovaBot");
        factory = new NovaCommonPainterFactory(new BuildProperties(buildInfo), coreProperties, fontUtil);

        BufferedImage placeholder = new BufferedImage(96, 96, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = placeholder.createGraphics();
        graphics.setColor(new Color(120, 170, 220));
        graphics.fillRect(0, 0, 96, 96);
        graphics.dispose();
        api = mock(BilibiliApiUtil.class);
        // 大航海标志（hdslb 那三个地址）取不到：收到的礼物那一格走色块兜底，判据靠颜色认出它；
        // 其余地址照常给占位图
        when(api.getBilibiliImage(anyString())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0);
            return url.contains("hdslb.com") ? Optional.empty() : Optional.of(placeholder);
        });
        Room room = new Room();
        room.setTitle("测试直播间");
        room.setCover("https://pic.example/cover.jpg");
        when(api.getLiveInfoByRoomId(anyLong())).thenReturn(room);
        when(api.getGuardList(anyLong(), anyLong())).thenReturn(Optional.of(List.of()));

        liveDataService = new DefaultLiveDataService(new NovaCoreProperties());
        painter = new BilibiliLiveReportPainter(factory, api, liveDataService, fontUtil,
                new NovaBilibiliProperties(), new LiveRoomInfoHistory(new NovaStateStore(new NovaCoreProperties())));
    }

    /**
     * 喂一场四路都有、且互相重叠的付费互动：
     * 用户 1 礼物＋醒目留言、用户 2 礼物、用户 3 醒目留言、用户 4 上舰。
     * 流水总额 214.6＋210＋996＝1420.6，付费互动去重 4 人。
     */
    private void feedRevenueSession() {
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L);
        liveDataService.setLiveEndTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L + 3600_000);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GIFT_VALUE, 214.6);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.SUPER_CHAT_COUNT, 2);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.SUPER_CHAT_VALUE, 210);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.CAPTAIN_COUNT, 1);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GUARD_VALUE, 996);
        liveDataService.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(), REVENUE_USERS, 1L, 130);
        liveDataService.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(), REVENUE_USERS, 2L, 84.6);
        liveDataService.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(), REVENUE_USERS, 3L, 210);
        liveDataService.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(), REVENUE_USERS, 4L, 996);
        liveDataService.recordLiveUserName(PLATFORM, STREAMER.getUid(), 1L, "甲乙丙");
        liveDataService.recordLiveUserName(PLATFORM, STREAMER.getUid(), 2L, "丁戊己");
        liveDataService.recordLiveUserName(PLATFORM, STREAMER.getUid(), 3L, "庚辛壬");
        liveDataService.recordLiveUserName(PLATFORM, STREAMER.getUid(), 4L, "癸子丑");
    }

    @Test
    @DisplayName("流水卡：总额为礼物＋醒目留言＋大航海，副行是去重人数")
    void revenueCardSumsGiftSuperChatAndGuard() {
        feedRevenueSession();

        List<BilibiliLiveReportPainter.Card> cards = painter.buildCards(measuringPainter(), PLATFORM, STREAMER.getUid(),
                BilibiliLiveReportOptions.of(null, true));

        BilibiliLiveReportPainter.Card revenue = cards.stream()
                .filter(card -> card.label().startsWith("流水"))
                .findFirst().orElse(null);
        List<String> red = new ArrayList<>();
        try {
            assertTrue(revenue != null, "没有以「流水」起头的卡片：" + cards);
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        if (revenue != null) {
            try {
                assertEquals("¥1420.6", revenue.value(), "214.6＋210＋996，整数位不补小数");
            } catch (Throwable t) {
                red.add("② " + t.getMessage());
            }
            try {
                assertTrue(revenue.label().contains("4 人"), revenue.label());
            } catch (Throwable t) {
                red.add("③ " + t.getMessage());
            }
        }
        try {
            assertEquals(0, cards.indexOf(revenue), "流水卡放第一格：" + cards);
        } catch (Throwable t) {
            red.add("⑤ " + t.getMessage());
        }
        try {
            assertTrue(cards.stream().noneMatch(card -> card.label().startsWith("礼物")),
                    "原「礼物」卡不再单列：" + cards);
        } catch (Throwable t) {
            red.add("④ " + t.getMessage());
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    @Test
    @DisplayName("隐藏金额时流水卡只写人数，整排卡片没有一个金额符号")
    void revenueCardCountsPeopleOnlyWhenHidden() {
        feedRevenueSession();

        List<BilibiliLiveReportPainter.Card> cards = painter.buildCards(measuringPainter(), PLATFORM, STREAMER.getUid(),
                BilibiliLiveReportOptions.of(null, false));

        List<String> red = new ArrayList<>();
        try {
            assertTrue(cards.stream().anyMatch(card -> "4 人".equals(card.value()) && card.label().contains("付费互动")),
                    "隐藏金额时流水卡应换成人数形态：" + cards);
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        try {
            BilibiliLiveReportPainter.Card first = cards.isEmpty() ? null : cards.get(0);
            assertTrue(first != null && "付费互动".equals(first.label()),
                    "隐藏金额时付费互动卡也在第一格：" + cards);
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }
        try {
            assertTrue(cards.stream().noneMatch(card -> card.value().contains("¥") || card.label().contains("¥")),
                    "隐藏金额的卡片上不该出现金额符号：" + cards);
        } catch (Throwable t) {
            red.add("③ " + t.getMessage());
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    @Test
    @DisplayName("大航海卡写次数，新开与续费分得清时随行写明")
    void guardCardCountsPurchasesWithOpenAndRenew() {
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.CAPTAIN_COUNT, 2);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.COMMANDER_COUNT, 1);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GUARD_VALUE, 996);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), "guard_open_count", 2);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), "guard_renew_count", 1);

        BilibiliLiveReportPainter.Card shown = guardCard(true);
        BilibiliLiveReportPainter.Card hidden = guardCard(false);

        List<String> red = new ArrayList<>();
        try {
            assertTrue(shown != null, "没有大航海卡");
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        if (shown != null) {
            try {
                assertEquals("+3 次", shown.value(), "写的是人次，不是金额：" + shown);
            } catch (Throwable t) {
                red.add("② " + t.getMessage());
            }
            try {
                // 标签只写「大航海」不写「开通」：数的是人次，写「开通」会把续费读成新开
                assertTrue(shown.label().startsWith("大航海"), shown.label());
                assertFalse(shown.label().contains("开通"), shown.label());
                assertTrue(shown.label().contains("新开 2"), shown.label());
                assertTrue(shown.label().contains("续费 1"), shown.label());
            } catch (Throwable t) {
                red.add("③ " + t.getMessage());
            }
            try {
                assertTrue(hidden.label().contains("新开 2") && hidden.label().contains("续费 1"),
                        "新开／续费是次数不是金额，隐藏金额时照写：" + hidden.label());
            } catch (Throwable t) {
                red.add("④ " + t.getMessage());
            }
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    private BilibiliLiveReportPainter.Card guardCard(boolean showRevenue) {
        return painter.buildCards(measuringPainter(), PLATFORM, STREAMER.getUid(), BilibiliLiveReportOptions.of(null, showRevenue))
                .stream()
                .filter(card -> card.label().startsWith("开通大航海") || card.label().startsWith("大航海"))
                .findFirst().orElse(null);
    }

    @Test
    @DisplayName("文字版：有流水一行、不再有「本场收益」；隐藏金额时换成人数说法且无金额符号")
    void textReportCarriesRevenueLine() {
        feedRevenueSession();

        String shown = painter.textReport(PLATFORM, STREAMER, BilibiliLiveReportOptions.of(null, true));
        String hidden = painter.textReport(PLATFORM, STREAMER, BilibiliLiveReportOptions.of(null, false));

        List<String> red = new ArrayList<>();
        try {
            assertTrue(shown.contains("流水 ¥1420.6 · 4 人"), shown);
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        try {
            assertFalse(shown.contains("本场收益"), "顶部收益行已去掉：" + shown);
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }
        try {
            assertTrue(hidden.contains("付费互动 4 人"), hidden);
        } catch (Throwable t) {
            red.add("③ " + t.getMessage());
        }
        try {
            assertFalse(hidden.contains("¥"), "降级不是放宽口径的理由：" + hidden);
        } catch (Throwable t) {
            red.add("④ " + t.getMessage());
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    @Test
    @DisplayName("收到的礼物：本场有人开的大航海档排在所有礼物前面，图标取不到时画该档色块")
    void guardTiersSitAboveEveryGiftRegardlessOfPrice() throws Exception {
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L);
        liveDataService.setLiveEndTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L + 3600_000);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GOVERNOR_COUNT, 1);
        // 一份 888 元的礼物：单价再高也得排在大航海下面。图标地址为空，走占位色块
        liveDataService.recordLiveGift(PLATFORM, STREAMER.getUid(), 11L, "贵重礼物", 888, 1, "");

        com.alibaba.fastjson2.JSONObject params = quietParams();
        Optional<String> base64 = painter.paint(PLATFORM, STREAMER, BilibiliLiveReportOptions.of(params, true));
        assertTrue(base64.isPresent(), "报告没画出来，这把尺无从谈起");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64.get())));

        // 总督色块的底色（画手里 guardColor(1)）；占位礼物色块是另一族颜色
        Color governor = new Color(0xE6, 0xB4, 0x22);
        Color giftPlaceholderBack = new Color(255, 236, 242);

        int governorTop = topOf(image, governor);
        int giftTop = topOf(image, giftPlaceholderBack);

        List<String> red = new ArrayList<>();
        try {
            assertTrue(governorTop >= 0, "图上没有总督色块——大航海没画进收到的礼物");
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        try {
            assertTrue(giftTop >= 0, "图上没有礼物占位色块——这份夹具没走到礼物那段");
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }
        if (governorTop >= 0 && giftTop >= 0) {
            try {
                assertTrue(governorTop < giftTop,
                        "总督格应排在 888 元礼物前面：总督顶 " + governorTop + "，礼物顶 " + giftTop);
            } catch (Throwable t) {
                red.add("③ " + t.getMessage());
            }
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    @Test
    @DisplayName("隐藏金额时盲盒榜照出、只写个数：有盲盒数据的隐藏图比没有的高出那一块")
    void hiddenSessionKeepsTheBoxBoardWithCountsOnly() throws Exception {
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L);
        liveDataService.setLiveEndTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L + 3600_000);
        com.alibaba.fastjson2.JSONObject params = quietParams();
        params.put("box_ranking", 5);
        BilibiliLiveReportOptions hidden = BilibiliLiveReportOptions.of(params, false);

        int without = heightOf(painter.paint(PLATFORM, STREAMER, hidden).orElseThrow());
        liveDataService.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(),
                BilibiliLiveMetric.BOX_USERS, 1L, 18);
        liveDataService.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(),
                BilibiliLiveMetric.BOX_PROFIT_USERS, 1L, 18.6);
        liveDataService.recordLiveUserName(PLATFORM, STREAMER.getUid(), 1L, "甲乙丙");
        int with = heightOf(painter.paint(PLATFORM, STREAMER, hidden).orElseThrow());

        assertTrue(with > without,
                "隐藏金额时盲盒榜该照出（只写个数），喂了盲盒数据图却没长高：" + with + " vs " + without);
    }

    private int heightOf(String base64) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64))).getHeight();
    }

    @Test
    @DisplayName("版式键：盲盒两榜并成一榜，box_profit_ranking 不再是版式项；醒目留言名单默认放宽到 10 人")
    void boxProfitRankingIsGoneFromLayoutOptions() {
        List<String> keys = BilibiliLiveReportOptions.layoutOptions().stream().map(option -> option.key()).toList();

        List<String> red = new ArrayList<>();
        try {
            assertFalse(keys.contains("box_profit_ranking"), "盲盒盈亏榜已并入盲盒榜：" + keys);
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        try {
            assertTrue(keys.contains("box_ranking"), "合并后沿用 box_ranking 键：" + keys);
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }
        try {
            assertEquals(10, BilibiliLiveReportOptions.of(null, true).getSuperChatRanking(),
                    "名单不是名次：一行顶榜一行两倍高，默认 5 人截得住一场中型直播的开花时段");
        } catch (Throwable t) {
            red.add("③ " + t.getMessage());
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    @Test
    @DisplayName("大航海全名单：本场开过或续过的人名字旁带「本场」，没开过的不带")
    void fullRosterMarksThisSessionBuyers() {
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L);
        liveDataService.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(),
                BilibiliLiveMetric.GUARD_USERS, 11L, 1);
        BilibiliLiveReportPainter hooked = painterWithGuards(List.of(
                new GuardMember(11L, "甲总督", 1, 300),
                new GuardMember(22L, "乙提督", 2, 200)));

        String text = hooked.textReport(PLATFORM, STREAMER, BilibiliLiveReportOptions.of(null, true));

        List<String> red = new ArrayList<>();
        try {
            assertTrue(text.contains("甲总督 · 本场"), "本场上过舰的人要带标记：" + text);
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        try {
            assertFalse(text.contains("乙提督 · 本场"), "本场没上过的人不许带标记：" + text);
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    /**
     * 这一色最靠上的 y。找不到为 -1
     */
    private static int topOf(BufferedImage image, Color color) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) == (color.getRGB() & 0xFFFFFF)) {
                    return y;
                }
            }
        }
        return -1;
    }

    private BilibiliLiveReportPainter painterWithGuards(List<GuardMember> members) {
        return new BilibiliLiveReportPainter(factory, api, liveDataService, fontUtil,
                new NovaBilibiliProperties(), new LiveRoomInfoHistory(new NovaStateStore(new NovaCoreProperties()))) {
            @Override
            protected Optional<List<GuardMember>> guardList(Long roomId, Long uid) {
                return Optional.of(members);
            }
        };
    }

    /**
     * 一只只用来量宽度的画坊画手：buildCards 里大航海卡副行的取舍要量宽度
     */
    private CommonPainter measuringPainter() {
        return factory.create(ReportSharedStyle.WIDTH, 1600, true);
    }

    private static com.alibaba.fastjson2.JSONObject quietParams() {
        com.alibaba.fastjson2.JSONObject params = new com.alibaba.fastjson2.JSONObject();
        params.put("cover", false);
        params.put("cards", false);
        params.put("fans_change", false);
        params.put("interaction_curve", false);
        params.put("highlights", false);
        params.put("danmu_cloud", false);
        params.put("guard_list", false);
        params.put("guard_list_all", false);
        params.put("danmu_ranking", 0);
        params.put("gift_ranking", 0);
        params.put("super_chat_ranking", 0);
        params.put("box_ranking", 0);
        params.put("box_profit_ranking", 0);
        return params;
    }
}
