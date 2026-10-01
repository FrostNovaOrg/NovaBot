package org.frostnova.nova.report.painter;

import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.model.BilibiliLiveMetric;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.frostnova.nova.bilibili.model.Room;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.DanmuRecord;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.UserScore;
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

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 下播报告改版·乙：要新入口的那一半判据
 * <p>
 * 曲线合成、醒目留言名单、流水榜的旧数据回落、本场变化的「在舰」说法——这些在旧码上
 * 没有可走的入口，所以这一把的先红是<b>编译红</b>：入口本身就是要造的东西。
 * 可在旧码上走通的那一半见 {@code BilibiliLiveReportRevenueCardsTest}。
 * <p>
 * 指标名写字面量的理由见 {@code BilibiliRevenueUsersMetricTest} 的类注释。
 */
@DisplayName("下播报告流水曲线、名单与回落")
class BilibiliLiveReportRevenueLayoutTest {
    private static final String PLATFORM = "bilibili";

    private static final LiveStreamerInfo STREAMER = new LiveStreamerInfo(10001L, "测试主播", 20002L, "https://pic.example/face.jpg");

    /**
     * 分人流水表
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
        when(api.getBilibiliImage(anyString())).thenReturn(Optional.of(placeholder));
        Room room = new Room();
        room.setTitle("测试直播间");
        room.setCover("https://pic.example/cover.jpg");
        when(api.getLiveInfoByRoomId(anyLong())).thenReturn(room);
        when(api.getGuardList(anyLong(), anyLong())).thenReturn(Optional.of(List.of()));

        liveDataService = new DefaultLiveDataService(new NovaCoreProperties());
        painter = new BilibiliLiveReportPainter(factory, api, liveDataService, fontUtil,
                new NovaBilibiliProperties(), new LiveRoomInfoHistory(new NovaStateStore(new NovaCoreProperties())));
    }

    @Test
    @DisplayName("流水曲线：每分钟为礼物＋醒目留言＋大航海之和，盲盒的钱已在礼物里")
    void revenueSeriesSumsTheThreeMoneySeriesPerMinute() {
        // 时间格的键按绝对分钟对齐：开播落在半分钟上时，喂进去的时刻与格键会错开一格
        long start = 1_700_000_000_000L / 60_000L * 60_000L;
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), start);
        liveDataService.setLiveEndTime(PLATFORM, STREAMER.getUid(), start + 3 * 60_000L);
        liveDataService.incrementLiveSeries(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GIFT_VALUE, start, 10);
        liveDataService.incrementLiveSeries(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.SUPER_CHAT_VALUE, start, 5);
        liveDataService.incrementLiveSeries(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GUARD_VALUE, start + 60_000L, 138);
        liveDataService.incrementLiveSeries(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.BOX_COUNT, start + 60_000L, 3);

        Map<Long, Double> series = painter.revenueSeries(PLATFORM, STREAMER.getUid());

        List<String> red = new ArrayList<>();
        try {
            assertEquals(15.0, series.get(start), 0.0001, "第 0 分钟：礼物 10＋醒目留言 5");
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        try {
            assertEquals(138.0, series.get(start + 60_000L), 0.0001,
                    "第 1 分钟：大航海 138；盲盒那 3 个是次数不是钱，其金额已在礼物曲线里");
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }
        try {
            assertEquals(2, series.size(), "没有互动的分钟不出现，交给画图那侧补零：" + series);
        } catch (Throwable t) {
            red.add("③ " + t.getMessage());
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    @Test
    @DisplayName("曲线清单：弹幕、流水、看过、在线四条；流水单色、峰值带金额，隐藏金额时不标峰值")
    void curveListHasFourCurvesWithMergedRevenue() {
        // 与上一格同一条：分钟对齐，免得喂的时刻与格键错开一格
        long start = 1_700_000_000_000L / 60_000L * 60_000L;
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), start);
        liveDataService.setLiveEndTime(PLATFORM, STREAMER.getUid(), start + 60_000L);
        liveDataService.incrementLiveSeries(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GIFT_VALUE, start, 10);
        liveDataService.incrementLiveSeries(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.SUPER_CHAT_VALUE, start, 5);

        List<BilibiliLiveReportPainter.Curve> shown =
                painter.buildCurves(PLATFORM, STREAMER.getUid(), BilibiliLiveReportOptions.of(null, true));
        List<BilibiliLiveReportPainter.Curve> hidden =
                painter.buildCurves(PLATFORM, STREAMER.getUid(), BilibiliLiveReportOptions.of(null, false));

        List<String> red = new ArrayList<>();
        try {
            assertEquals(List.of("弹幕", "流水", "看过人数", "在线人数"),
                    shown.stream().map(BilibiliLiveReportPainter.Curve::title).toList(),
                    "礼物、醒目留言、盲盒、大航海四条并成一条流水，其余三条不动");
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        BilibiliLiveReportPainter.Curve revenue = shown.stream()
                .filter(curve -> "流水".equals(curve.title())).findFirst().orElse(null);
        try {
            assertTrue(revenue != null, "没有流水曲线");
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }
        if (revenue != null) {
            try {
                assertEquals("¥15/分", revenue.peakText().apply(15.0), "峰值按每分钟的金额合计标");
            } catch (Throwable t) {
                red.add("③ " + t.getMessage());
            }
            try {
                assertEquals(15.0, revenue.series().get(start), 0.0001, "曲线用的是合成后的那份时序");
            } catch (Throwable t) {
                red.add("④ " + t.getMessage());
            }
        }
        try {
            BilibiliLiveReportPainter.Curve hiddenRevenue = hidden.stream()
                    .filter(curve -> "流水".equals(curve.title())).findFirst().orElse(null);
            assertTrue(hiddenRevenue != null && hiddenRevenue.peakText() == null,
                    "隐藏金额时流水曲线照列、但不标峰值");
        } catch (Throwable t) {
            red.add("⑤ " + t.getMessage());
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    @Test
    @DisplayName("本场变化的大航海：写「在舰 X 人」与「较开播 ±Y」，没有快照时只写在舰")
    void guardChangeSaysOnBoardAndDeltaSinceStart() {
        List<String> red = new ArrayList<>();
        try {
            liveDataService.setLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GUARD_AT_START, 22);
            BilibiliLiveReportPainter.Card card = painter.guardChangeCard(PLATFORM, STREAMER.getUid(), 27);
            assertEquals("在舰 27 人", card.value(), card.toString());
            assertEquals("大航海 · 较开播 +5", card.label(), card.toString());
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        try {
            liveDataService.setLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GUARD_AT_START, 0);
            BilibiliLiveReportPainter.Card noSnapshot = painter.guardChangeCard(PLATFORM, STREAMER.getUid(), 27);
            assertEquals("在舰 27 人", noSnapshot.value(), noSnapshot.toString());
            assertEquals("大航海", noSnapshot.label(), "拿不到开播快照就别编一个涨幅出来：" + noSnapshot);
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    @Test
    @DisplayName("醒目留言名单：每人一行带合计、条数与按时间接起来的原文")
    void superChatListCarriesTotalsCountsAndJoinedTexts() {
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L);
        liveDataService.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.SUPER_CHAT_USERS, 1L, 40);
        liveDataService.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(), "super_chat_users_count", 1L, 2);
        liveDataService.recordLiveUserName(PLATFORM, STREAMER.getUid(), 1L, "甲乙丙");
        liveDataService.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.SUPER_CHAT_USERS, 2L, 50);
        liveDataService.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(), "super_chat_users_count", 2L, 1);
        liveDataService.recordLiveUserName(PLATFORM, STREAMER.getUid(), 2L, "丁戊己");

        long base = 1_700_000_000_000L;
        BilibiliLiveReportPainter hooked = painterWithSuperChats(List.of(
                new DanmuRecord(base + 60_000L, 1L, "甲乙丙", "第一条", DanmuRecord.Type.SUPER_CHAT),
                new DanmuRecord(base + 120_000L, 2L, "丁戊己", "隔空一条", DanmuRecord.Type.SUPER_CHAT),
                new DanmuRecord(base + 180_000L, 1L, "甲乙丙", "第二条", DanmuRecord.Type.SUPER_CHAT),
                new DanmuRecord(base + 240_000L, 1L, "甲乙丙", "普通弹幕不算", DanmuRecord.Type.DANMU)));

        List<BilibiliLiveReportPainter.SuperChatEntry> entries =
                hooked.superChatList(PLATFORM, STREAMER.getUid(), 10);

        List<String> red = new ArrayList<>();
        try {
            assertEquals(2, entries.size(), "两个人都上榜");
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        if (entries.size() == 2) {
            try {
                assertEquals(2L, entries.get(0).uid(), "按合计金额降序，50 在前");
                assertEquals(50.0, entries.get(0).total(), 0.0001);
                assertEquals(1, entries.get(0).count());
                assertEquals("隔空一条", entries.get(0).joinedText());
            } catch (Throwable t) {
                red.add("② " + t.getMessage());
            }
            try {
                assertEquals(40.0, entries.get(1).total(), 0.0001);
                assertEquals(2, entries.get(1).count());
                assertEquals("第一条／第二条", entries.get(1).joinedText(), "多条按时间接起来，普通弹幕不进来");
            } catch (Throwable t) {
                red.add("③ " + t.getMessage());
            }
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    @Test
    @DisplayName("流水排行：没有分人流水表的旧场次按礼物＋醒目留言的分人数据回落")
    void revenueRankingFallsBackToLegacyTablesForOldSessions() {
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L);
        // 旧场次：只有礼物与醒目留言的分人金额，没有分人流水表
        liveDataService.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GIFT_USERS, 1L, 100);
        liveDataService.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.SUPER_CHAT_USERS, 1L, 30);
        liveDataService.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GIFT_USERS, 2L, 200);
        liveDataService.recordLiveUserName(PLATFORM, STREAMER.getUid(), 1L, "甲乙丙");
        liveDataService.recordLiveUserName(PLATFORM, STREAMER.getUid(), 2L, "丁戊己");

        List<String> red = new ArrayList<>();
        try {
            assertFalse(painter.hasRevenueUserData(PLATFORM, STREAMER.getUid()), "旧场次没有新表，回落的那一支要认得出它");
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        try {
            List<UserScore> ranking = painter.revenueRanking(PLATFORM, STREAMER.getUid(), 5);
            assertEquals(2, ranking.size());
            assertEquals(2L, ranking.get(0).userUid(), "礼物 200 的排在礼物 130 的前面");
            assertEquals(200.0, ranking.get(0).score(), 0.0001);
            assertEquals(130.0, ranking.get(1).score(), 0.0001, "礼物＋醒目留言按人相加");
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }
        try {
            // 新表一旦有数，主路照走：回落只属于旧数据
            liveDataService.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(), REVENUE_USERS, 3L, 500);
            liveDataService.recordLiveUserName(PLATFORM, STREAMER.getUid(), 3L, "庚辛壬");
            List<UserScore> ranking = painter.revenueRanking(PLATFORM, STREAMER.getUid(), 5);
            assertEquals(1, ranking.size(), "新表只记到一个人时，榜就是那一个人：" + ranking);
            assertEquals(500.0, ranking.get(0).score(), 0.0001);
        } catch (Throwable t) {
            red.add("③ " + t.getMessage());
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    private BilibiliLiveReportPainter painterWithSuperChats(List<DanmuRecord> records) {
        return new BilibiliLiveReportPainter(factory, api, liveDataService, fontUtil,
                new NovaBilibiliProperties(), new LiveRoomInfoHistory(new NovaStateStore(new NovaCoreProperties()))) {
            @Override
            protected List<DanmuRecord> superChatRecords(String platform, Long uid) {
                return records;
            }
        };
    }
}
