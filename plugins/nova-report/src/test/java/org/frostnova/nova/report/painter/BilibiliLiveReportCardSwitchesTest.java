package org.frostnova.nova.report.painter;

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
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 数据卡片逐张开关
 * <p>
 * 版式参数一律写成 JSON 键喂给 {@link BilibiliLiveReportOptions#of}，与推送配置里存的同一形态。
 * 每张卡认的是副行标签「 · 」之前那一段（流水、弹幕、点赞……），隐藏金额时流水卡写「付费互动」。
 */
@DisplayName("数据卡片逐张开关")
class BilibiliLiveReportCardSwitchesTest {
    private static final String PLATFORM = "bilibili";

    private static final LiveStreamerInfo STREAMER = new LiveStreamerInfo(10001L, "测试主播", 20002L, "https://pic.example/face.jpg");

    /**
     * 分人流水表；写面值的理由见 {@code BilibiliRevenueUsersMetricTest} 的类注释
     */
    private static final String REVENUE_USERS = "revenue_users";

    /**
     * 十张卡全有数时的顺序
     */
    private static final List<String> ALL_TEN = List.of(
            "流水", "弹幕", "点赞", "进入直播间", "新增关注", "醒目留言", "大航海", "盲盒", "免费礼物", "分享");

    /**
     * 十张卡各自的开关键，顺序同上
     */
    private static final List<String> CARD_KEYS = List.of(
            "card_revenue", "card_danmu", "card_like", "card_enter", "card_follow",
            "card_super_chat", "card_guard", "card_box", "card_free_gift", "card_share");

    private NovaCommonPainterFactory factory;

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

        FontUtil fontUtil = new FontUtil(new DefaultResourceLoader(), coreProperties);
        fontUtil.init();

        Properties buildInfo = new Properties();
        buildInfo.setProperty("version", "4.0.0");
        buildInfo.setProperty("group", "com." + "starlwr");
        buildInfo.setProperty("artifact", "nova-core");
        buildInfo.setProperty("name", "NovaBot");
        factory = new NovaCommonPainterFactory(new BuildProperties(buildInfo), coreProperties, fontUtil);

        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getBilibiliImage(anyString())).thenReturn(Optional.empty());
        Room room = new Room();
        room.setTitle("测试直播间");
        when(api.getLiveInfoByRoomId(anyLong())).thenReturn(room);
        when(api.getGuardList(anyLong(), anyLong())).thenReturn(Optional.of(List.of()));

        liveDataService = new DefaultLiveDataService(new NovaCoreProperties());
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L);
        liveDataService.setLiveEndTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L + 3600_000);
        painter = new BilibiliLiveReportPainter(factory, api, liveDataService, fontUtil,
                new NovaBilibiliProperties(), new LiveRoomInfoHistory(new NovaStateStore(new NovaCoreProperties())));
    }

    /**
     * 十张卡都有数的一场
     */
    private void feedAllTen() {
        Long uid = STREAMER.getUid();
        liveDataService.incrementLiveMetric(PLATFORM, uid, BilibiliLiveMetric.GIFT_VALUE, 30);
        liveDataService.incrementLiveUserMetric(PLATFORM, uid, REVENUE_USERS, 1L, 30);
        liveDataService.incrementLiveMetric(PLATFORM, uid, BilibiliLiveMetric.DANMU_COUNT, 12);
        liveDataService.recordLiveMetricUser(PLATFORM, uid, BilibiliLiveMetric.DANMU_USERS, 2L);
        liveDataService.incrementLiveMetric(PLATFORM, uid, BilibiliLiveMetric.LIKE_TOTAL, 300);
        liveDataService.recordLiveMetricUser(PLATFORM, uid, BilibiliLiveMetric.ENTER_USERS, 3L);
        liveDataService.incrementLiveMetric(PLATFORM, uid, BilibiliLiveMetric.FOLLOW_COUNT, 4);
        liveDataService.incrementLiveMetric(PLATFORM, uid, BilibiliLiveMetric.SUPER_CHAT_COUNT, 1);
        liveDataService.incrementLiveMetric(PLATFORM, uid, BilibiliLiveMetric.SUPER_CHAT_VALUE, 30);
        liveDataService.incrementLiveMetric(PLATFORM, uid, BilibiliLiveMetric.CAPTAIN_COUNT, 1);
        liveDataService.incrementLiveMetric(PLATFORM, uid, BilibiliLiveMetric.BOX_COUNT, 2);
        liveDataService.incrementLiveMetric(PLATFORM, uid, BilibiliLiveMetric.FREE_GIFT_COUNT, 5);
        liveDataService.incrementLiveMetric(PLATFORM, uid, BilibiliLiveMetric.SHARE_COUNT, 6);
    }

    /**
     * 卡片按画出的顺序读成「流水、弹幕……」
     */
    private List<String> kinds(JSONObject params, boolean showRevenue) {
        BilibiliLiveReportOptions options = BilibiliLiveReportOptions.of(params, showRevenue);
        CommonPainter canvas = factory.create(800, 600, true);
        return painter.buildCards(canvas, PLATFORM, STREAMER.getUid(), options).stream()
                .map(card -> card.label().split(" · ")[0])
                .toList();
    }

    /**
     * 只留数据卡片一块的版式：其余区块全关，图高的差只来自卡片那一块
     */
    private static JSONObject cardsOnly() {
        JSONObject params = new JSONObject();
        params.put("cover", false);
        params.put("fans_change", false);
        params.put("interaction_curve", false);
        params.put("highlights", false);
        params.put("gift_list", false);
        params.put("guard_list", false);
        params.put("guard_list_all", false);
        params.put("danmu_cloud", false);
        params.put("danmu_ranking", 0);
        params.put("gift_ranking", 0);
        params.put("super_chat_ranking", 0);
        params.put("box_ranking", 0);
        return params;
    }

    private int paintedHeight(JSONObject params) throws Exception {
        Optional<String> base64 = painter.paint(PLATFORM, STREAMER, BilibiliLiveReportOptions.of(params, true));
        assertTrue(base64.isPresent(), "报告没画出来");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64.get())));
        return image.getHeight();
    }

    @Test
    @DisplayName("关掉点赞和分享：这两张有数也不画，其余照画，顺序不变")
    void likeAndShareOffAreSkipped() {
        // 用户故障：主播想给某个群只放几张卡，点赞和分享不要，以前只能整块开或整块关
        feedAllTen();
        JSONObject params = new JSONObject();
        params.put("card_like", false);
        params.put("card_share", false);

        assertEquals(List.of("流水", "弹幕", "进入直播间", "新增关注", "醒目留言", "大航海", "盲盒", "免费礼物"),
                kinds(params, true));
    }

    @Test
    @DisplayName("关掉弹幕：0 条和有条数时都不画")
    void danmuOffIsSkippedEvenWhenZero() {
        // 用户故障：弹幕卡原本 0 条也总画，群里不想看弹幕卡的没有办法去掉它
        JSONObject params = new JSONObject();
        params.put("card_danmu", false);

        assertEquals(List.of(), kinds(params, true), "0 条弹幕、开关关着时仍画了弹幕卡");

        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.DANMU_COUNT, 8);
        assertEquals(List.of(), kinds(params, true), "有弹幕、开关关着时仍画了弹幕卡");
    }

    @Test
    @DisplayName("全关、或开着的全为 0：整块不画，不留空框也不多留间距")
    void nothingLeftDrawsNoBlock() throws Exception {
        // 用户故障：卡片全关了，报告上却还留着卡片那一块的空白
        feedAllTen();
        JSONObject allOff = cardsOnly();
        CARD_KEYS.forEach(key -> allOff.put(key, false));
        assertEquals(List.of(), kinds(allOff, true), "十张全关仍有卡");

        JSONObject blockOff = cardsOnly();
        blockOff.put("cards", false);
        JSONObject blockOn = cardsOnly();
        int withoutBlock = paintedHeight(blockOff);
        int withBlock = paintedHeight(blockOn);
        // 阴性对照：卡片那一块真画出来时图是更高的，下面两条相等才有意义
        assertTrue(withBlock > withoutBlock, "卡片块开着时图没变高（" + withBlock + " ≤ " + withoutBlock + "），本格量不到间距");
        assertEquals(withoutBlock, paintedHeight(allOff), "十张全关时图高与关掉整块不同，多留了间距");
    }

    @Test
    @DisplayName("开着的卡全为 0：整块不画，图高与关掉整块相同")
    void openButAllZeroDrawsNoBlock() throws Exception {
        // 用户故障：只留了几张卡、这场它们都是 0，报告上却还留着一块空白
        JSONObject zeroes = cardsOnly();
        zeroes.put("card_danmu", false);
        assertEquals(List.of(), kinds(zeroes, true), "开着的卡全为 0 仍有卡");

        JSONObject blockOff = cardsOnly();
        blockOff.put("cards", false);
        assertEquals(paintedHeight(blockOff), paintedHeight(zeroes), "开着的卡全为 0 时多留了间距");
    }

    @Test
    @DisplayName("总开关关着：单张全开也不画")
    void masterSwitchOffWins() throws Exception {
        // 用户故障：数据卡片整块关了，单张卡的开关不该把它们画回来
        feedAllTen();
        JSONObject blockOff = cardsOnly();
        blockOff.put("cards", false);
        JSONObject blockOffCardsOn = cardsOnly();
        blockOffCardsOn.put("cards", false);
        CARD_KEYS.forEach(key -> blockOffCardsOn.put(key, true));
        JSONObject blockOn = cardsOnly();

        int off = paintedHeight(blockOff);
        assertTrue(paintedHeight(blockOn) > off, "卡片块开着时图没变高，本格量不到卡片画没画");
        assertEquals(off, paintedHeight(blockOffCardsOn), "总开关关着、单张全开时卡片被画了出来");
    }

    @Test
    @DisplayName("隐藏金额：流水开着画付费人数，关了不画")
    void hiddenRevenueCardFollowsItsSwitch() {
        // 用户故障：给大群隐藏了金额、又不想放付费人数那张卡，以前去不掉
        feedAllTen();
        List<String> on = kinds(new JSONObject(), false);
        assertEquals("付费互动", on.get(0), "隐藏金额、流水卡开着时第一张该是付费人数：" + on);

        JSONObject params = new JSONObject();
        params.put("card_revenue", false);
        List<String> off = kinds(params, false);
        assertTrue(off.stream().noneMatch(kind -> kind.equals("付费互动") || kind.equals("流水")),
                "隐藏金额、流水卡关着时仍画了：" + off);
        assertEquals(ALL_TEN.subList(1, ALL_TEN.size()), off);
    }

    @Test
    @DisplayName("老配置没有新键：十张照画")
    void legacyParamsDrawAllTen() {
        // 用户故障：升级之后老群的报告不能因为配置里没有新键就少了卡
        feedAllTen();
        JSONObject legacy = new JSONObject();
        legacy.put("cards", true);
        legacy.put("danmu_ranking", 5);
        legacy.put("gift_ranking", 5);

        assertEquals(ALL_TEN, kinds(legacy, true));
        assertEquals(ALL_TEN, kinds(null, true));
    }
}
