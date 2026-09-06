package com.starlwr.bot.bilibili.painter;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.model.BilibiliLiveMetric;
import com.starlwr.bot.bilibili.model.BilibiliLiveReportOptions;
import com.starlwr.bot.bilibili.model.GuardMember;
import com.starlwr.bot.bilibili.model.Room;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.factory.StarBotCommonPainterFactory;
import com.starlwr.bot.core.model.LiveGap;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.service.DefaultLiveDataService;
import com.starlwr.bot.core.service.LiveRoomInfoHistory;
import com.starlwr.bot.core.service.StarBotStateStore;
import com.starlwr.bot.core.util.FontUtil;
import org.springframework.boot.info.BuildProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 下播报告绘制测试
 * <p>
 * 与动态图片绘制测试同一套桩：内置字体、占位头像，不依赖网络与本机字体。
 */
@DisplayName("下播报告绘制")
class BilibiliLiveReportPainterTest {
    private static final String PLATFORM = "bilibili";

    private static final LiveStreamerInfo STREAMER = new LiveStreamerInfo(10001L, "测试主播", 20002L, "https://pic.example/face.jpg");

    private BilibiliApiUtil api;

    private StarBotCommonPainterFactory factory;

    private FontUtil fontUtil;

    private DefaultLiveDataService liveDataService;

    private LiveRoomInfoHistory roomInfoHistory;

    private BilibiliLiveReportPainter painter;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void setUp() {
        StarBotCoreProperties coreProperties = new StarBotCoreProperties();
        // 使用核心内置的字体，避免测试结果依赖运行环境已安装的字体
        coreProperties.getPaint().getFonts().add("内置");

        fontUtil = new FontUtil(new DefaultResourceLoader(), coreProperties);
        // 字体在 @PostConstruct 中加载，脱离 Spring 容器时需手动触发
        fontUtil.init();

        Properties buildInfo = new Properties();
        buildInfo.setProperty("version", "4.0.0");
        buildInfo.setProperty("group", "com.starlwr");
        buildInfo.setProperty("artifact", "starbot-core");
        buildInfo.setProperty("name", "StarBotCore");

        factory = new StarBotCommonPainterFactory(new BuildProperties(buildInfo), coreProperties, fontUtil);

        BufferedImage placeholder = new BufferedImage(640, 360, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = placeholder.createGraphics();
        graphics.setColor(new Color(120, 170, 220));
        graphics.fillRect(0, 0, 640, 360);
        graphics.setColor(new Color(90, 140, 190));
        graphics.fillOval(180, 60, 280, 240);
        graphics.dispose();
        api = mock(BilibiliApiUtil.class);
        when(api.getBilibiliImage(anyString())).thenReturn(Optional.of(placeholder));

        // 直播间信息返回带封面的房间，覆盖封面横幅版式
        Room room = new Room();
        room.setTitle("测试直播间");
        room.setCover("https://pic.example/cover.jpg");
        when(api.getLiveInfoByRoomId(anyLong())).thenReturn(room);
        when(api.getGuardList(anyLong(), anyLong())).thenReturn(Optional.of(List.of()));

        liveDataService = new DefaultLiveDataService(new StarBotCoreProperties());
        roomInfoHistory = new LiveRoomInfoHistory(new StarBotStateStore(new StarBotCoreProperties()));
        painter = new BilibiliLiveReportPainter(factory, api, liveDataService, fontUtil,
                new com.starlwr.bot.bilibili.config.StarBotBilibiliProperties(), roomInfoHistory);
    }

    @Test
    @DisplayName("有完整数据时应绘制出报告")
    void paintsFullReport() {
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L);
        liveDataService.setLiveEndTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L + 2 * 3600_000 + 128_000);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.DANMU_COUNT, 106);
        liveDataService.recordLiveMetricUser(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.DANMU_USERS, 1L);
        liveDataService.recordLiveMetricUser(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.DANMU_USERS, 2L);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GIFT_VALUE, 52.0);
        liveDataService.recordLiveMetricUser(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GIFT_USERS, 1L);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.BOX_COUNT, 5);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.BOX_PROFIT, -2.3);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.SUPER_CHAT_COUNT, 2);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.SUPER_CHAT_VALUE, 80.0);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.CAPTAIN_COUNT, 1);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GUARD_VALUE, 138.0);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.FOLLOW_COUNT, 5);
        liveDataService.recordLiveMetricUser(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.ENTER_USERS, 3L);
        liveDataService.maxLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.LIKE_TOTAL, 1024);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.SHARE_COUNT, 3);

        // 词频喂满词云的最低词数门槛，覆盖词云版式
        String[] words = {"晚上好", "唱歌", "好听", "打游戏", "厉害", "加油", "可爱", "再来一首", "笑死", "太强了", "岁月史书", "下次一定"};
        for (int i = 0; i < words.length; i++) {
            for (int j = 0; j <= i * 2; j++) {
                liveDataService.incrementLiveWordFrequency(PLATFORM, STREAMER.getUid(), words[i]);
            }
        }

        Optional<String> base64 = painter.paint(PLATFORM, STREAMER);

        assertTrue(base64.isPresent(), "应生成报告图片");
        assertFalse(base64.get().isBlank());
        dump("full", base64.get());
    }

    @Test
    @DisplayName("零数据的冷清场次也应能绘制出报告")
    void paintsEmptyReport() {
        Optional<String> base64 = painter.paint(PLATFORM, STREAMER);

        assertTrue(base64.isPresent(), "无数据也应生成报告图片");
        dump("empty", base64.get());
    }

    @Test
    @DisplayName("盲盒盈亏三分：零持平、正盈利、负亏损")
    void boxCardShowsBreakEvenProfitAndLoss() {
        assertAll(
                () -> {
                    String label = boxLabel(0);
                    assertEquals("盲盒 · 持平", label);
                    assertFalse(label.contains("¥"), label);
                },
                () -> {
                    String label = boxLabel(150);
                    assertTrue(label.contains("盈利"), label);
                    assertTrue(label.contains("150"), label);
                },
                () -> {
                    String label = boxLabel(-150);
                    assertTrue(label.contains("亏损"), label);
                    assertTrue(label.contains("150"), label);
                }
        );
    }

    @Test
    @DisplayName("盲盒盈亏排行：零无号、正负带号")
    void profitLabelZeroUnsignedPositiveAndNegativeSigned() {
        assertAll(
                () -> {
                    String label = BilibiliLiveReportPainter.profitLabel(0);
                    assertEquals("¥0", label);
                    assertFalse(label.startsWith("+"), label);
                    assertFalse(label.startsWith("-"), label);
                },
                () -> {
                    String label = BilibiliLiveReportPainter.profitLabel(150);
                    assertTrue(label.contains("+¥"), label);
                },
                () -> {
                    String label = BilibiliLiveReportPainter.profitLabel(-150);
                    assertTrue(label.contains("-¥"), label);
                }
        );
    }

    @Test
    @DisplayName("本场变化三分：零持平、正负带号、接线持平")
    void deltaLabelZeroIsTiePositiveAndNegativeSignedAndWired() {
        List<String> red = new ArrayList<>();
        try {
            assertEquals("持平", BilibiliLiveReportPainter.deltaLabel(0));
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        try {
            assertEquals("+5", BilibiliLiveReportPainter.deltaLabel(5));
            assertEquals("-5", BilibiliLiveReportPainter.deltaLabel(-5));
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }
        try {
            String label = fansChangeLabel(243);
            assertTrue(label.contains("本场 持平"), label);
            assertFalse(label.contains("+0"), label);
        } catch (Throwable t) {
            red.add("③ " + t.getMessage());
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    /**
     * 只喂盲盒数量与盈亏，从建卡结果里取出盲盒卡文案。
     */
    private String boxLabel(double boxProfit) {
        DefaultLiveDataService data = new DefaultLiveDataService(new StarBotCoreProperties());
        data.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.BOX_COUNT, 3);
        data.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.BOX_PROFIT, boxProfit);
        BilibiliLiveReportPainter reportPainter = new BilibiliLiveReportPainter(
                factory, api, data, fontUtil, new StarBotBilibiliProperties(), roomInfoHistory);
        return reportPainter.buildCards(PLATFORM, STREAMER.getUid(), BilibiliLiveReportOptions.of(null, true))
                .stream()
                .map(BilibiliLiveReportPainter.Card::label)
                .filter(label -> label.startsWith("盲盒"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有盲盒卡"));
    }

    /**
     * 开播快照与当前粉丝数相等时，粉丝变化卡副标题。
     */
    private String fansChangeLabel(long fans) {
        DefaultLiveDataService data = new DefaultLiveDataService(new StarBotCoreProperties());
        data.setLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.FANS_AT_START, fans);
        BilibiliLiveReportPainter reportPainter = new BilibiliLiveReportPainter(
                factory, api, data, fontUtil, new StarBotBilibiliProperties(), roomInfoHistory);
        return reportPainter.changeCard(PLATFORM, STREAMER.getUid(), fans,
                BilibiliLiveMetric.FANS_AT_START, "粉丝").label();
    }

    @Test
    @DisplayName("有排行榜数据时应画出榜单")
    void paintsRankings() {
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L);
        liveDataService.setLiveEndTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L + 3600_000);

        String[] names = {"甲乙丙", "丁戊己", "庚辛壬", "癸子丑", "寅卯辰", "巳午未"};
        for (int i = 0; i < names.length; i++) {
            long uid = 100L + i;
            double weight = names.length - i;
            liveDataService.recordLiveUserName(PLATFORM, STREAMER.getUid(), uid, names[i]);
            liveDataService.recordLiveUserFace(PLATFORM, STREAMER.getUid(), uid, "https://pic.example/face" + i + ".jpg");
            liveDataService.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.DANMU_USERS, uid, weight * 7);
            liveDataService.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GIFT_USERS, uid, weight * 13.5);
            liveDataService.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.SUPER_CHAT_USERS, uid, weight * 30);
        }
        liveDataService.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GUARD_USERS, 100L, 1);
        // 总量与按用户计分要一起喂：生产中聚合器同时写两者，样张也应自洽
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.DANMU_COUNT, 147);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GIFT_VALUE, 283.5);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.SUPER_CHAT_COUNT, 6);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.SUPER_CHAT_VALUE, 630.0);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.CAPTAIN_COUNT, 1);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GUARD_VALUE, 138.0);

        Optional<String> base64 = painter.paint(PLATFORM, STREAMER, BilibiliLiveReportOptions.of(null, true));

        assertTrue(base64.isPresent());
        dump("rankings", base64.get());
    }

    @Test
    @DisplayName("有时间序列时应画出互动曲线")
    void paintsInteractionCurves() {
        long start = 1_700_000_000_000L;
        long end = start + 3 * 3600_000;
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), start);
        liveDataService.setLiveEndTime(PLATFORM, STREAMER.getUid(), end);

        // 造一场有节奏的直播：弹幕全程有、中段有个高峰，礼物集中在两处，SC 只有零星几次
        for (int minute = 0; minute < 180; minute++) {
            long at = start + minute * 60_000L;
            int danmu = 3 + (int) (12 * Math.exp(-Math.pow(minute - 95, 2) / 400.0));
            liveDataService.incrementLiveSeries(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.DANMU_COUNT, at, danmu);
            liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.DANMU_COUNT, danmu);

            if (minute == 40 || minute == 96 || minute == 97) {
                liveDataService.incrementLiveSeries(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GIFT_VALUE, at, 66.0);
                liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GIFT_VALUE, 66.0);
            }
            if (minute == 96) {
                liveDataService.incrementLiveSeries(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.SUPER_CHAT_VALUE, at, 30.0);
                liveDataService.incrementLiveSeries(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GUARD_VALUE, at, 138.0);
            }
        }

        Optional<String> base64 = painter.paint(PLATFORM, STREAMER);

        assertTrue(base64.isPresent());
        dump("curves", base64.get());
    }

    @Test
    @DisplayName("有开播快照时应画出本场变化，涨幅为实时值减快照")
    void paintsFansChange() {
        when(api.getFansCount(anyLong())).thenReturn(Optional.of(243L));
        when(api.getFansMedalCount(anyLong())).thenReturn(Optional.of(36));
        when(api.getGuardCount(anyLong(), anyLong())).thenReturn(Optional.of(3));

        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L);
        liveDataService.setLiveEndTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L + 3600_000);
        liveDataService.setLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.FANS_AT_START, 231);
        liveDataService.setLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.FANS_MEDAL_AT_START, 36);
        liveDataService.setLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GUARD_AT_START, 2);

        Optional<String> base64 = painter.paint(PLATFORM, STREAMER);

        assertTrue(base64.isPresent());
        dump("fans-change", base64.get());
    }

    @Test
    @DisplayName("接口取不到时本场变化区块应整体跳过，不应画出占位的空卡片")
    void skipsFansChangeWhenApiUnavailable() {
        // 默认桩即三个接口都返回空
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L);

        assertTrue(painter.paint(PLATFORM, STREAMER).isPresent(), "接口不可用也应能出图");
    }

    @Test
    @DisplayName("在线人数画成折线而不是面积：峰值列只占约笔宽，基线到折线之间有空白")
    void paintsOnlineCurveAsPolylineNotArea() throws Exception {
        long start = 1_700_000_000_000L / 60_000L * 60_000L;
        int minutes = 60;
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), start);
        liveDataService.setLiveEndTime(PLATFORM, STREAMER.getUid(), start + minutes * 60_000L);

        for (int minute = 0; minute <= minutes; minute++) {
            double value = (minute >= 20 && minute <= 40) ? 100.0 : 10.0;
            liveDataService.maxLiveSeries(PLATFORM, STREAMER.getUid(),
                    BilibiliLiveMetric.ONLINE_COUNT, start + minute * 60_000L, value);
        }

        Optional<String> base64 = painter.paint(PLATFORM, STREAMER, curvesOnly());
        assertTrue(base64.isPresent(), "只喂在线人数序列也应出图");
        dump("online-polyline", base64.get());

        BufferedImage image = imageOf(base64.get());
        Color online = BilibiliLiveReportPainter.COLOR_CURVE_ONLINE;

        int peakX = -1;
        int peakY = image.getHeight();
        for (int x = GAUGE_MARGIN; x < GAUGE_MARGIN + GAUGE_CONTENT_WIDTH; x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                if (isColor(image, x, y, online) && y < peakY) {
                    peakY = y;
                    peakX = x;
                }
            }
        }
        assertTrue(peakX >= 0, "图上没有在线人数曲线色：第七条没画出来");

        int run = 0;
        int longest = 0;
        int longestEnd = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            if (isColor(image, peakX, y, online)) {
                run++;
                if (run > longest) {
                    longest = run;
                    longestEnd = y;
                }
            } else {
                run = 0;
            }
        }

        int blanks = 0;
        int scanTo = Math.min(image.getHeight(), longestEnd + 50);
        for (int y = longestEnd + 1; y < scanTo; y++) {
            if (!isColor(image, peakX, y, online)) {
                blanks++;
            }
        }

        assertTrue(longest >= 1 && longest <= 8,
                "折线色应只占约笔宽的一段连续像素, 实测连续 " + longest + "（面积图会从基线填到峰）");
        assertTrue(blanks >= 10,
                "基线到折线之间应有空白像素, 实测 " + blanks);
    }

    @Test
    @DisplayName("没有时间序列时曲线区块应整体跳过")
    void skipsCurvesWithoutSeries() {
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L);
        liveDataService.setLiveEndTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L + 3600_000);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.DANMU_COUNT, 50);

        assertTrue(painter.paint(PLATFORM, STREAMER).isPresent(), "无曲线数据也应能出图");
    }

    @Test
    @DisplayName("配了自定义标识时应画在署名之上")
    void paintsCustomLogo() throws Exception {
        BufferedImage mark = new BufferedImage(300, 90, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = mark.createGraphics();
        g.setColor(new Color(251, 114, 153));
        g.fillRoundRect(0, 0, 300, 90, 20, 20);
        g.dispose();
        Path file = Files.createTempDirectory("novabot-logo").resolve("logo.png");
        javax.imageio.ImageIO.write(mark, "png", file.toFile());

        StarBotBilibiliProperties withLogo = new StarBotBilibiliProperties();
        withLogo.getLive().setReportLogoPath(file.toString());

        Optional<String> base64 = new BilibiliLiveReportPainter(factory, api, liveDataService, fontUtil, withLogo, roomInfoHistory)
                .paint(PLATFORM, STREAMER);

        assertTrue(base64.isPresent());
        dump("logo", base64.get());
    }

    @Test
    @DisplayName("标识路径写错时应跳过绘制而非让整张报告失败")
    void survivesMissingLogo() {
        StarBotBilibiliProperties badPath = new StarBotBilibiliProperties();
        badPath.getLive().setReportLogoPath("/nowhere/does-not-exist.png");

        assertTrue(new BilibiliLiveReportPainter(factory, api, liveDataService, fontUtil, badPath, roomInfoHistory)
                .paint(PLATFORM, STREAMER).isPresent(), "标识读不到也应出图");
    }

    @Test
    @DisplayName("关闭全部区块时应只剩概览，不应绘制失败")
    void paintsWithAllSectionsDisabled() {
        com.alibaba.fastjson2.JSONObject params = new com.alibaba.fastjson2.JSONObject();
        params.put("cover", false);
        params.put("cards", false);
        params.put("danmu_ranking", 0);
        params.put("gift_ranking", 0);
        params.put("super_chat_ranking", 0);
        params.put("guard_list", false);
        params.put("guard_list_all", false);
        params.put("danmu_cloud", false);
        params.put("highlights", false);
        params.put("title_changes", false);

        Optional<String> base64 = painter.paint(PLATFORM, STREAMER, BilibiliLiveReportOptions.of(params, true));

        assertTrue(base64.isPresent(), "全部关闭也应能出图");
        dump("minimal", base64.get());
    }

    @Test
    @DisplayName("不展示金额时报告应更短：三张金额榜整榜不出")
    void hidesMoneyRankingsWithoutRevenue() throws Exception {
        feedRankingData();

        int withRevenue = heightOf(painter.paint(PLATFORM, STREAMER, BilibiliLiveReportOptions.of(null, true)).orElseThrow());
        String hidden = painter.paint(PLATFORM, STREAMER, BilibiliLiveReportOptions.of(null, false)).orElseThrow();

        assertTrue(heightOf(hidden) < withRevenue,
                "礼物榜与醒目留言榜整榜不出，图应明显变矮：" + heightOf(hidden) + " vs " + withRevenue);
        dump("no-revenue-rankings", hidden);
    }

    @Test
    @DisplayName("不展示金额时卡片与曲线仍在，只是不带数额")
    void keepsAtmosphereWithoutRevenue() {
        feedRankingData();
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.FOLLOW_COUNT, 5);
        liveDataService.recordLiveMetricUser(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.ENTER_USERS, 3L);
        liveDataService.maxLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.LIKE_TOTAL, 1024);

        String[] words = {"晚上好", "唱歌", "好听", "打游戏", "厉害", "加油", "可爱", "再来一首", "笑死", "太强了", "岁月史书", "下次一定"};
        for (int i = 0; i < words.length; i++) {
            for (int j = 0; j <= i * 2; j++) {
                liveDataService.incrementLiveWordFrequency(PLATFORM, STREAMER.getUid(), words[i]);
            }
        }

        Optional<String> base64 = painter.paint(PLATFORM, STREAMER, BilibiliLiveReportOptions.of(null, false));

        assertTrue(base64.isPresent(), "不展示金额也应能出图");
        // 图里有没有 ¥ 只能人工看，这里存一份样张；断言留给选项与命令层
        dump("no-revenue-full", base64.get());
    }

    @Test
    @DisplayName("有明显高峰时应画出高能时刻，冷场时整块不出现")
    void drawsHighlightsOnlyWhenThereIsAPeak() throws Exception {
        long start = 1_700_000_000_000L;
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), start);
        liveDataService.setLiveEndTime(PLATFORM, STREAMER.getUid(), start + 60 * 60_000L);

        // 冷场：全程每分钟一条弹幕，没有任何高峰
        for (int i = 0; i < 60; i++) {
            liveDataService.incrementLiveSeries(PLATFORM, STREAMER.getUid(),
                    BilibiliLiveMetric.DANMU_COUNT, start + i * 60_000L, 1);
        }
        int quiet = heightOf(painter.paint(PLATFORM, STREAMER).orElseThrow());

        // 同一场再叠一个第 30 分钟的高峰
        liveDataService.incrementLiveSeries(PLATFORM, STREAMER.getUid(),
                BilibiliLiveMetric.DANMU_COUNT, start + 30 * 60_000L, 200);
        String peaked = painter.paint(PLATFORM, STREAMER).orElseThrow();

        assertTrue(heightOf(peaked) > quiet, "出现高峰后报告应多出一块高能时刻");
        dump("highlights", peaked);
    }

    @Test
    @DisplayName("标题没改过时不应占版面，改过之后才出现")
    void drawsTitleChangesOnlyAfterAnActualChange() throws Exception {
        long start = 1_700_000_000_000L;
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), start);
        liveDataService.setLiveEndTime(PLATFORM, STREAMER.getUid(), start + 60 * 60_000L);

        // 只有开播时的初始标题：一条记录不等于改过一次
        roomInfoHistory.record(PLATFORM, STREAMER.getUid(), start, "早八人的自习室", "");
        int unchanged = heightOf(painter.paint(PLATFORM, STREAMER).orElseThrow());

        roomInfoHistory.record(PLATFORM, STREAMER.getUid(), start + 20 * 60_000L, "睡前杂谈", "娱乐 · 视频聊天");
        String changed = painter.paint(PLATFORM, STREAMER).orElseThrow();

        assertTrue(heightOf(changed) > unchanged, "改过标题后报告应多出一块标题变化");
        dump("title-changes", changed);
    }

    @Test
    @DisplayName("原样保存不算改动，重复内容不应被记成一次变化")
    void repeatedIdenticalTitleIsNotAChange() throws Exception {
        long start = 1_700_000_000_000L;
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), start);
        liveDataService.setLiveEndTime(PLATFORM, STREAMER.getUid(), start + 60 * 60_000L);

        roomInfoHistory.record(PLATFORM, STREAMER.getUid(), start, "早八人的自习室", "");
        int before = heightOf(painter.paint(PLATFORM, STREAMER).orElseThrow());

        roomInfoHistory.record(PLATFORM, STREAMER.getUid(), start + 10 * 60_000L, "早八人的自习室", "");

        assertEquals(before, heightOf(painter.paint(PLATFORM, STREAMER).orElseThrow()),
                "内容相同的下发不应让报告多出一块");
    }

    /**
     * 喂一份带金额的榜单数据
     */
    private void feedRankingData() {
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L);
        liveDataService.setLiveEndTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L + 3600_000);

        String[] names = {"甲乙丙", "丁戊己", "庚辛壬", "癸子丑", "寅卯辰", "巳午未"};
        for (int i = 0; i < names.length; i++) {
            long uid = 100L + i;
            double weight = names.length - i;
            liveDataService.recordLiveUserName(PLATFORM, STREAMER.getUid(), uid, names[i]);
            liveDataService.recordLiveUserFace(PLATFORM, STREAMER.getUid(), uid, "https://pic.example/face" + i + ".jpg");
            liveDataService.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.DANMU_USERS, uid, weight * 7);
            liveDataService.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GIFT_USERS, uid, weight * 13.5);
            liveDataService.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.SUPER_CHAT_USERS, uid, weight * 30);
        }
        liveDataService.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GUARD_USERS, 100L, 1);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.DANMU_COUNT, 147);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GIFT_VALUE, 283.5);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.SUPER_CHAT_COUNT, 6);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.SUPER_CHAT_VALUE, 630.0);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.CAPTAIN_COUNT, 1);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GUARD_VALUE, 138.0);
    }

    @Test
    @DisplayName("本场有停机缺口时应标注缺了多久，并画得出报告")
    void annotatesMaintenanceGap() {
        // 时刻取近期真实时间：停机记录有 30 天保留期，2023 年的区间会被当成过期
        long start = System.currentTimeMillis() - 2 * 3600_000;
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), start);
        liveDataService.setLiveEndTime(PLATFORM, STREAMER.getUid(), start + 2 * 3600_000);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.DANMU_COUNT, 106);
        // 开播 30 分钟后重启，缺了 12 分 34 秒
        liveDataService.recordDowntime(start + 1800_000, start + 1800_000 + 754_000,
                LiveGap.Reason.RESTART);

        assertEquals("采集缺口 共 12 分 34 秒·重启",
                painter.collectionGapText(PLATFORM, STREAMER.getUid()));

        Optional<String> base64 = painter.paint(PLATFORM, STREAMER);
        assertTrue(base64.isPresent());
        dump("gap", base64.get());
    }

    @Test
    @DisplayName("文字版报告应带上时长、弹幕与缺口标注")
    void textReportCarriesTheNumbers() {
        long start = System.currentTimeMillis() - 2 * 3600_000;
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), start);
        liveDataService.setLiveEndTime(PLATFORM, STREAMER.getUid(), start + 2 * 3600_000);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.DANMU_COUNT, 106);
        liveDataService.recordLiveMetricUser(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.DANMU_USERS, 1L);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GIFT_VALUE, 52.0);
        liveDataService.recordLiveMetricUser(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GIFT_USERS, 1L);
        liveDataService.recordDowntime(start + 600_000, start + 600_000 + 754_000,
                LiveGap.Reason.MAINTENANCE);

        String text = painter.textReport(PLATFORM, STREAMER, BilibiliLiveReportOptions.of(new com.alibaba.fastjson2.JSONObject(), true));

        assertTrue(text.contains("测试主播"), text);
        assertTrue(text.contains("直播时长 2 时"), text);
        assertTrue(text.contains("采集缺口 共 12 分 34 秒·维护"), text);
        assertTrue(text.contains("弹幕 106 条 · 1 人参与"), text);
        // 金额格式与图片版共用 yuan()，整数不补两位小数——两版说的必须是同一个数
        assertTrue(text.contains("本场收益 ¥52"), text);
        assertTrue(text.contains("绘制失败"), "要说清这是降级来的，别让人以为报告一直长这样");
        assertFalse(text.contains("图片未送达"), "本场没有图片降级时不该占版面");
    }

    @Test
    @DisplayName("本场有推送的图片没送到时，报告要注明；为零时不显示")
    void reportsImageDegradedOnlyWhenNonZero() {
        long start = System.currentTimeMillis() - 3600_000;
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), start);
        liveDataService.setLiveEndTime(PLATFORM, STREAMER.getUid(), start + 3600_000);

        // 先证明这个断言真的分得出有无——零的那一遍必须读不到这句话
        String before = painter.textReport(PLATFORM, STREAMER,
                BilibiliLiveReportOptions.of(new com.alibaba.fastjson2.JSONObject(), true));
        assertFalse(before.contains("图片未送达"), "为零时不该显示: " + before);

        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(),
                BilibiliLiveMetric.IMAGE_DEGRADED_COUNT, 2);

        String after = painter.textReport(PLATFORM, STREAMER,
                BilibiliLiveReportOptions.of(new com.alibaba.fastjson2.JSONObject(), true));
        assertTrue(after.contains("本场有 2 条推送的图片未送达"), after);
        assertTrue(after.contains("文字已送达"), "要说清丢的只有图，别让人以为整条没发出去: " + after);
    }

    @Test
    @DisplayName("文字版同样受金额可见性约束：该给大群看的不带金额")
    void textReportHidesRevenueWhenAsked() {
        long start = System.currentTimeMillis() - 3600_000;
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), start);
        liveDataService.setLiveEndTime(PLATFORM, STREAMER.getUid(), start + 3600_000);
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GIFT_VALUE, 52.0);
        liveDataService.recordLiveMetricUser(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GIFT_USERS, 3L);

        String text = painter.textReport(PLATFORM, STREAMER, BilibiliLiveReportOptions.of(new com.alibaba.fastjson2.JSONObject(), false));

        assertFalse(text.contains("¥"), "降级不是放宽口径的理由: " + text);
        assertFalse(text.contains("52"), text);
        assertTrue(text.contains("礼物 1 人送出"), "热闹程度照样看得见: " + text);
    }

    @Test
    @DisplayName("零数据时文字版也要成句，不能是一堆空行")
    void textReportSurvivesEmptySession() {
        String text = painter.textReport(PLATFORM, STREAMER, new BilibiliLiveReportOptions());

        assertTrue(text.contains("测试主播"), text);
        assertTrue(text.contains("直播时长 未知"), text);
        assertTrue(text.contains("弹幕 0 条"), text);
    }

    @Test
    @DisplayName("全名单段画出覆写钩子给的三位")
    void paintsFullGuardRosterFromHook() throws Exception {
        BilibiliLiveReportPainter hooked = painterWithGuards(Optional.of(List.of(
                new GuardMember(11L, "甲总督", 1, 300),
                new GuardMember(22L, "乙提督", 2, 200),
                new GuardMember(33L, "丙舰长", 3, 100))));

        String text = hooked.textReport(PLATFORM, STREAMER, BilibiliLiveReportOptions.of(null, true));
        assertAll(
                () -> assertTrue(text.contains("大航海名单（全部）"), text),
                () -> assertTrue(text.contains("甲总督"), text),
                () -> assertTrue(text.contains("乙提督"), text),
                () -> assertTrue(text.contains("丙舰长"), text));

        Optional<String> image = hooked.paint(PLATFORM, STREAMER, BilibiliLiveReportOptions.of(null, true));
        assertTrue(image.isPresent(), "有名单也应出图");
        dump("guard-roster", image.get());

        int without = heightOf(painter.paint(PLATFORM, STREAMER,
                BilibiliLiveReportOptions.of(null, true)).orElseThrow());
        assertTrue(heightOf(image.get()) > without, "全名单段应让报告变高");
    }

    @Test
    @DisplayName("名单拉不到时画一行说明，整张报告仍然出得来")
    void paintsUnavailableGuardRosterWithoutFailing() {
        BilibiliLiveReportPainter hooked = painterWithGuards(Optional.empty());

        String text = hooked.textReport(PLATFORM, STREAMER, BilibiliLiveReportOptions.of(null, true));
        assertTrue(text.contains("名单暂时拉不到"), text);
        assertTrue(hooked.paint(PLATFORM, STREAMER, BilibiliLiveReportOptions.of(null, true)).isPresent(),
                "拉不到名单也不该让整张报告失败");
    }

    private BilibiliLiveReportPainter painterWithGuards(Optional<List<GuardMember>> members) {
        return new BilibiliLiveReportPainter(factory, api, liveDataService, fontUtil,
                new StarBotBilibiliProperties(), roomInfoHistory) {
            @Override
            protected Optional<List<GuardMember>> guardList(Long roomId, Long uid) {
                return members;
            }
        };
    }

    @Test
    @DisplayName("没有缺口时不该多出一句话")
    void saysNothingWithoutGap() {
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L);
        liveDataService.setLiveEndTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L + 3600_000);

        assertEquals("", painter.collectionGapText(PLATFORM, STREAMER.getUid()));
    }

    @Test
    @DisplayName("开播之前那一段停机不算进本场")
    void gapBeforeStartIsNotCounted() {
        long start = System.currentTimeMillis() - 3600_000;
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), start);
        liveDataService.setLiveEndTime(PLATFORM, STREAMER.getUid(), start + 3600_000);
        // 停机跨过开播时刻：开播前 10 分钟停到开播后 1 分钟，本场只该算那 1 分钟
        liveDataService.recordDowntime(start - 600_000, start + 60_000, LiveGap.Reason.MAINTENANCE);

        assertEquals("采集缺口 共 1 分·维护", painter.collectionGapText(PLATFORM, STREAMER.getUid()));
    }

    /**
     * 取图片高度
     */
    private int heightOf(String base64) throws Exception {
        return imageOf(base64).getHeight();
    }

    /**
     * 把绘制结果解回一张图，供像素判据取样
     */
    private BufferedImage imageOf(String base64) throws Exception {
        return javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(base64)));
    }

    // ================ 缺口斜纹的像素判据 ================
    // 版式常量在被测类里是私有的，这里按同一份定义抄一份，不从别处推算：
    // 判据要量的是「画出来的那张图」，拿被测自己的算法去算取样点，等于让它自己给自己出题
    private static final int GAUGE_MARGIN = 35;

    private static final int GAUGE_CONTENT_WIDTH = 900 - 35 * 2;

    private static final int GAUGE_COLUMN_WIDTH = 2;

    private static final int GAUGE_HATCH_PERIOD = 10;

    /**
     * 夹具场次的长度（分钟）与时间格数
     * <p>
     * 格数是「首尾都算」的，与被测同一条算式但各算各的：夹具知道自己造了多长的一场。
     */
    private static final int GAUGE_SESSION_MINUTES = 180;

    private static final int GAUGE_BUCKETS = GAUGE_SESSION_MINUTES + 1;

    /**
     * 缺口起止（第几分钟）：第 100 分钟落过一次盘，随后进程停到第 120 分钟
     */
    private static final int GAUGE_GAP_FROM_MINUTE = 100;

    private static final int GAUGE_GAP_TO_MINUTE = 120;

    /**
     * 真没互动的那一段（第几分钟）：采集一直在，就是没人说话
     */
    private static final int GAUGE_QUIET_MINUTE = 50;

    /**
     * 一场三小时的直播，中间挖两个不同性质的洞：
     * <ul>
     *     <li>第 40～59 分钟<b>真的没人说话</b>——采集一直在，就是没有互动</li>
     *     <li>第 100～119 分钟<b>没在采</b>——第 100 分钟落过一次盘，随后进程停到第 120 分钟</li>
     * </ul>
     * 第 100 分钟这一格<b>两边都占</b>：它落在停机区间里，却因为落盘发生在这一分钟之内而留着数据。
     * 这一格正是判据的取样点——改之前它按数据画满格面积，缺口在图上根本不存在。
     * <p>
     * 开播时刻取整分：时间格的键按绝对分钟对齐，而重采样按开播时刻起算，
     * 开播落在半分钟上时两套格子会错开一格。<b>那是另一个问题</b>，别让它混进这把尺的读数里。
     */
    private long feedGapAndQuietCurve() {
        long start = (System.currentTimeMillis() - 3 * 3600_000) / 60_000 * 60_000;
        long end = start + GAUGE_SESSION_MINUTES * 60_000L;
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), start);
        liveDataService.setLiveEndTime(PLATFORM, STREAMER.getUid(), end);

        for (int minute = 0; minute < GAUGE_SESSION_MINUTES; minute++) {
            boolean quiet = minute >= 40 && minute <= 59;
            boolean notCollected = minute > GAUGE_GAP_FROM_MINUTE && minute < GAUGE_GAP_TO_MINUTE;
            if (quiet || notCollected) {
                continue;
            }
            liveDataService.incrementLiveSeries(PLATFORM, STREAMER.getUid(),
                    BilibiliLiveMetric.DANMU_COUNT, start + minute * 60_000L, 10);
        }
        liveDataService.incrementLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.DANMU_COUNT, 1610);

        liveDataService.recordDowntime(start + GAUGE_GAP_FROM_MINUTE * 60_000L,
                start + GAUGE_GAP_TO_MINUTE * 60_000L);
        return start;
    }

    /**
     * 只留互动曲线的版式，让图里除了那一条曲线之外没有别的东西用同一种颜色
     */
    private BilibiliLiveReportOptions curvesOnly() {
        com.alibaba.fastjson2.JSONObject params = new com.alibaba.fastjson2.JSONObject();
        params.put("cover", false);
        params.put("cards", false);
        params.put("fans_change", false);
        params.put("danmu_ranking", 0);
        params.put("gift_ranking", 0);
        params.put("super_chat_ranking", 0);
        params.put("box_ranking", 0);
        params.put("box_profit_ranking", 0);
        params.put("guard_list", false);
        params.put("guard_list_all", false);
        params.put("danmu_cloud", false);
        params.put("highlights", false);
        params.put("title_changes", false);
        return BilibiliLiveReportOptions.of(params, true);
    }

    /**
     * 第 bucket 个时间格最早落在第几像素列
     * <p>
     * 列比格多（415 列对 181 格），一格摊到两三列上。取<b>最早</b>那一列，
     * 才保证这一列量的确实是这一格而不是上一格。
     */
    private static int columnOfBucket(int bucket) {
        int columns = GAUGE_CONTENT_WIDTH / GAUGE_COLUMN_WIDTH;
        return (int) Math.ceil((double) bucket * columns / GAUGE_BUCKETS);
    }

    /**
     * 第 bucket 个时间格那一列的取样 x，取两个列顶点之间那一点
     * <p>
     * 顶点上的像素是多边形的边，会被抗锯齿抹成一个中间色——判据要读的是实心的那一点。
     */
    private static int sampleXOfBucket(int bucket) {
        return GAUGE_MARGIN + columnOfBucket(bucket) * GAUGE_COLUMN_WIDTH + 1;
    }

    /**
     * 弹幕面积在图上的基线所在行
     * <p>
     * 取第 2 像素列上那一段连续的面积色：那一列在夹具里是满格的，找得到就必然是曲线本体。
     */
    private static int baselineOf(BufferedImage image) {
        int x = GAUGE_MARGIN + 2 * GAUGE_COLUMN_WIDTH;
        int bottom = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            if (isColor(image, x, y, BilibiliLiveReportPainter.COLOR_CURVE_DANMU)) {
                bottom = y;
            }
        }
        assertTrue(bottom > 0, "夹具里第 2 像素列应当是满格面积，图上却一点面积色都没有");
        return bottom + 1;
    }

    private static boolean isColor(BufferedImage image, int x, int y, Color color) {
        return (image.getRGB(x, y) & 0xFFFFFF) == (color.getRGB() & 0xFFFFFF);
    }

    /**
     * 把绘制结果另存为 PNG，便于人工核对版面
     */
    private void dump(String name, String base64) {
        try {
            Path dir = Path.of("target", "painter-output");
            Files.createDirectories(dir);
            Files.write(dir.resolve("report-" + name + ".png"), Base64.getDecoder().decode(base64));
        } catch (Exception e) {
            // 仅用于人工核对，失败不影响测试结论
        }
    }

    @Test
    @DisplayName("缺口那一段画斜纹不画面积，真没互动的那一段仍画贴地面积")
    void hatchesGapAndKeepsFlatAreaWhenQuiet() throws Exception {
        feedGapAndQuietCurve();

        Optional<String> base64 = painter.paint(PLATFORM, STREAMER, curvesOnly());
        assertTrue(base64.isPresent());
        dump("gap-hatch", base64.get());

        BufferedImage image = imageOf(base64.get());
        int baseline = baselineOf(image);
        int midY = baseline - 45;

        int gapX = sampleXOfBucket(GAUGE_GAP_FROM_MINUTE);
        int quietX = sampleXOfBucket(GAUGE_QUIET_MINUTE);
        List<Integer> stripes = hatchStripesIn(image, midY);

        // 三处取样一次报齐，不许短路：一条断言拦下之后，另外两处是绿是红就没人知道了，
        // 而「先红」要的正是这三处各自红在哪
        assertAll(
                // 取样点一：缺口里那一格（第 100 分钟）——它有数据，改之前这里画的是满格面积
                () -> assertFalse(isColor(image, gapX, midY, BilibiliLiveReportPainter.COLOR_CURVE_DANMU),
                        "缺口那一段不该画面积：那几分钟没在采，画成面积等于把没有说成有"),
                // 取样点二：整条缺口带里应当看得见斜纹，且斜线是等间距的
                () -> assertTrue(stripes.size() >= 3, "缺口带里应当量得到至少三道斜线, 实测 " + stripes.size() + " 道"),
                () -> {
                    for (int i = 1; i < stripes.size(); i++) {
                        assertEquals(GAUGE_HATCH_PERIOD, stripes.get(i) - stripes.get(i - 1),
                                "斜线应当等间距, 第 " + i + " 道与上一道相隔 "
                                        + (stripes.get(i) - stripes.get(i - 1)) + " 像素");
                    }
                },
                // 取样点三：真没互动的那一段（第 50 分钟）——面积贴着基线，抬头看不见、低头看得见
                () -> assertFalse(isColor(image, quietX, midY, BilibiliLiveReportPainter.COLOR_CURVE_DANMU),
                        "没人说话的那几分钟不该有高面积"),
                () -> assertTrue(isColor(image, quietX, baseline - 1, BilibiliLiveReportPainter.COLOR_CURVE_DANMU),
                        "没人说话的那几分钟仍要画贴地面积：一片空白与斜纹区就分不出了"),
                () -> assertFalse(isColor(image, quietX, midY, BilibiliLiveReportPainter.COLOR_CURVE_GAP_HATCH),
                        "没人说话不是缺口, 不该画成斜纹"));
    }

    /**
     * 数出缺口带中线上那几道斜线各自起于第几像素
     * <p>
     * 两头各让开两像素：边界细线与被它压掉半截的那道斜线不参与间距核算。
     */
    private static List<Integer> hatchStripesIn(BufferedImage image, int midY) {
        int bandFrom = GAUGE_MARGIN + columnOfBucket(GAUGE_GAP_FROM_MINUTE) * GAUGE_COLUMN_WIDTH + 2;
        int bandTo = GAUGE_MARGIN + columnOfBucket(GAUGE_GAP_TO_MINUTE) * GAUGE_COLUMN_WIDTH - 2;

        List<Integer> stripes = new ArrayList<>();
        // 扫描起点若正落在一道斜线里，那一道算不出真正的起点，不计
        boolean inside = isColor(image, bandFrom, midY, BilibiliLiveReportPainter.COLOR_CURVE_GAP_HATCH);
        for (int x = bandFrom + 1; x < bandTo; x++) {
            boolean hatch = isColor(image, x, midY, BilibiliLiveReportPainter.COLOR_CURVE_GAP_HATCH);
            if (hatch && !inside) {
                stripes.add(x);
            }
            inside = hatch;
        }
        return stripes;
    }

    @Test
    @DisplayName("三种成因各一段时，概览按成因分栏且各栏之和等于总时长")
    void breaksTheGapDownByReason() {
        long start = System.currentTimeMillis() - 2 * 3600_000;
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), start);
        liveDataService.setLiveEndTime(PLATFORM, STREAMER.getUid(), start + 2 * 3600_000);

        // 先证明这句话在没有缺口时不出现——否则下面的「出现了」说明不了问题
        String before = painter.textReport(PLATFORM, STREAMER,
                BilibiliLiveReportOptions.of(new com.alibaba.fastjson2.JSONObject(), true));
        assertFalse(before.contains("采集缺口"), before);

        // 三段互不重叠的缺口，三个成因各一段
        liveDataService.recordDowntime(start + 600_000, start + 600_000 + 754_000, LiveGap.Reason.MAINTENANCE);
        liveDataService.recordDowntime(start + 2400_000, start + 2400_000 + 120_000, LiveGap.Reason.RESTART);
        liveDataService.recordRoomOutage(PLATFORM, STREAMER.getUid(),
                start + 3600_000, start + 3600_000 + 123_000);

        // 754 + 120 + 123 = 997 秒 = 16 分 37 秒：分栏是真的分栏，各栏加起来正好是那个总数
        String expected = "采集缺口 共 16 分 37 秒：维护 12 分 34 秒／重启 2 分／断流 2 分 3 秒";
        String after = painter.textReport(PLATFORM, STREAMER,
                BilibiliLiveReportOptions.of(new com.alibaba.fastjson2.JSONObject(), true));
        assertTrue(after.contains(expected), after);
        assertEquals(expected, painter.collectionGapText(PLATFORM, STREAMER.getUid()));
    }

    @Test
    @DisplayName("断线落在停机里时，那几秒只算一次，且算给停机")
    void overlappingOutageIsCountedOnce() {
        long start = System.currentTimeMillis() - 2 * 3600_000;
        long end = start + 2 * 3600_000;
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), start);
        liveDataService.setLiveEndTime(PLATFORM, STREAMER.getUid(), end);

        // 断线那 123 秒整个落在停机那 754 秒里——相加就是把同一秒数了两遍
        liveDataService.recordDowntime(start + 600_000, start + 600_000 + 754_000, LiveGap.Reason.MAINTENANCE);
        liveDataService.recordRoomOutage(PLATFORM, STREAMER.getUid(),
                start + 600_000, start + 600_000 + 123_000);

        assertEquals("采集缺口 共 12 分 34 秒·维护",
                painter.collectionGapText(PLATFORM, STREAMER.getUid()),
                "重叠的那 123 秒既然程序整个停着, 就该记在停机名下, 不另起一栏");

        // 阴性对照：两个总数各自的口径一个字都没变，改的只是「怎么把它们摆到一起」
        assertEquals(754_000, liveDataService.downtimeWithin(start, end));
        assertEquals(123_000, liveDataService.roomOutageWithin(PLATFORM, STREAMER.getUid(), start, end));
    }
}
