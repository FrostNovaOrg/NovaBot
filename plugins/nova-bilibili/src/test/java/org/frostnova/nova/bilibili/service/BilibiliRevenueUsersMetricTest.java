package org.frostnova.nova.bilibili.service;

import org.frostnova.nova.bilibili.event.live.BilibiliCaptainEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliCommanderEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliGovernorEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliPaidGiftEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliRandomGiftEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliSuperChatEvent;
import org.frostnova.nova.bilibili.enums.GuardOperateType;
import org.frostnova.nova.bilibili.model.BilibiliLiveMetric;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.GiftInfo;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.UserInfo;
import org.frostnova.nova.core.service.DefaultLiveDataService;
import org.frostnova.nova.core.service.LiveDetailArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 流水的三张新表：分人流水、醒目留言条数、大航海开／续
 * <p>
 * 下播报告改版要按人回答「这一场谁贡献了多少流水」。流水＝礼物＋醒目留言＋上舰金额，
 * 其中礼物的分人表（{@code GIFT_USERS}）与醒目留言的分人金额表（{@code SUPER_CHAT_USERS}）早就有，
 * 缺的是：把三者按人合起来的那张表、每人醒目留言的<b>条数</b>、以及大航海的开通／续费各几次。
 * <p>
 * 🔴 指标名在这里写字面量而不是引用 {@code BilibiliLiveMetric} 的常量：
 * 这把尺要在指标<b>还不存在</b>的那一版上先红一次——引用常量的写法在那一刻编不过，
 * 红的是编译器而不是「没记上」这件事本身。
 */
@DisplayName("分人流水、醒目留言条数与大航海开续")
class BilibiliRevenueUsersMetricTest {
    private static final String PLATFORM = "bilibili";

    private static final LiveStreamerInfo STREAMER = new LiveStreamerInfo(10001L, "主播甲", 20002L);

    /**
     * 分人流水表。与 {@code GIFT_USERS} 一族：得分为该用户本场贡献的流水（元）
     */
    private static final String REVENUE_USERS = "revenue_users";

    /**
     * 每人醒目留言条数表
     */
    private static final String SUPER_CHAT_USERS_COUNT = "super_chat_users_count";

    /**
     * 大航海开通（新开）与续费的人次
     */
    private static final String GUARD_OPEN_COUNT = "guard_open_count";

    private static final String GUARD_RENEW_COUNT = "guard_renew_count";

    /**
     * 弹幕原文要落盘，给它一个临时目录——不给的话明细会落到进程当前目录
     */
    @TempDir
    Path dir;

    private DefaultLiveDataService liveDataService;

    private BilibiliLiveStatsAggregator aggregator;

    @BeforeEach
    void setUp() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setSaveLiveData(false);
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        liveDataService = new DefaultLiveDataService(properties);
        aggregator = new BilibiliLiveStatsAggregator(liveDataService, new LiveDetailArchive(properties));
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L);
    }

    @Test
    @DisplayName("礼物、盲盒开出物、醒目留言与上舰都按人记进流水表，四路之和等于三张总量相加")
    void everyMoneyEventScoresTheRevenueTable() {
        aggregator.onPaidGift(new BilibiliPaidGiftEvent(STREAMER, user(1L), gift(11L, "小电视", 10.0, 2), 20.0));
        aggregator.onRandomGift(new BilibiliRandomGiftEvent(STREAMER, user(2L),
                gift(99L, "幸运盲盒", 10.0, 1), gift(33L, "摩天大楼", 50.0, 1), 10.0, 50.0));
        aggregator.onSuperChat(new BilibiliSuperChatEvent(STREAMER, user(1L), "唱得真好", 30.0));
        aggregator.onSuperChat(new BilibiliSuperChatEvent(STREAMER, user(1L), "再来一首", 10.0));
        aggregator.onCaptain(captain(user(3L), 138.0, GuardOperateType.RENEWAL));
        aggregator.onGovernor(governor(user(4L), 1998.0, GuardOperateType.ACTIVATION));
        aggregator.onCommander(commander(user(3L), 998.0, GuardOperateType.UNKNOWN));

        double user1 = liveDataService.getLiveUserMetric(PLATFORM, STREAMER.getUid(), REVENUE_USERS, 1L);
        double user2 = liveDataService.getLiveUserMetric(PLATFORM, STREAMER.getUid(), REVENUE_USERS, 2L);
        double user3 = liveDataService.getLiveUserMetric(PLATFORM, STREAMER.getUid(), REVENUE_USERS, 3L);
        double user4 = liveDataService.getLiveUserMetric(PLATFORM, STREAMER.getUid(), REVENUE_USERS, 4L);

        List<String> red = new java.util.ArrayList<>();
        try {
            assertEquals(60.0, user1, 0.0001, "礼物 20 ＋醒目留言 30＋10");
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        try {
            assertEquals(50.0, user2, 0.0001, "盲盒按开出物价值计，不按盲盒的价");
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }
        try {
            assertEquals(1136.0, user3, 0.0001, "舰长 138 ＋提督 998，同一人多路相加");
        } catch (Throwable t) {
            red.add("③ " + t.getMessage());
        }
        try {
            assertEquals(1998.0, user4, 0.0001);
        } catch (Throwable t) {
            red.add("④ " + t.getMessage());
        }
        try {
            double total = liveDataService.getLiveUserRanking(PLATFORM, STREAMER.getUid(), REVENUE_USERS, 10)
                    .stream().mapToDouble(org.frostnova.nova.core.model.UserScore::score).sum();
            double expected = liveDataService.getLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GIFT_VALUE)
                    + liveDataService.getLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.SUPER_CHAT_VALUE)
                    + liveDataService.getLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GUARD_VALUE);
            assertEquals(expected, total, 0.0001,
                    "分人之和必须与「礼物＋醒目留言＋大航海」三张总量对得上——对不上就是流水榜在撒谎");
        } catch (Throwable t) {
            red.add("⑤ " + t.getMessage());
        }
        if (!red.isEmpty()) {
            org.junit.jupiter.api.Assertions.fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    @Test
    @DisplayName("匿名送礼照进总量、不进分人流水表")
    void anonymousGiftStaysOutOfThePerUserTable() {
        aggregator.onPaidGift(new BilibiliPaidGiftEvent(STREAMER, user(0L), gift(11L, "小电视", 5.0, 1), 5.0));

        assertEquals(5.0, liveDataService.getLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GIFT_VALUE), 0.0001,
                "总量照记：认不出是谁不等于没发生");
        assertEquals(0, liveDataService.getLiveMetricUserCount(PLATFORM, STREAMER.getUid(), REVENUE_USERS),
                "uid 抹成 0 的那位不许进分人表——进了就是所有匿名并成一个人");
    }

    @Test
    @DisplayName("每人醒目留言条数按人累计，金额表与条数表各归各")
    void superChatCountIsScoredPerUser() {
        aggregator.onSuperChat(new BilibiliSuperChatEvent(STREAMER, user(1L), "第一条", 30.0));
        aggregator.onSuperChat(new BilibiliSuperChatEvent(STREAMER, user(1L), "第二条", 10.0));
        aggregator.onSuperChat(new BilibiliSuperChatEvent(STREAMER, user(2L), "隔空一条", 50.0));

        List<String> red = new java.util.ArrayList<>();
        try {
            assertEquals(2.0, liveDataService.getLiveUserMetric(PLATFORM, STREAMER.getUid(), SUPER_CHAT_USERS_COUNT, 1L), 0.0001);
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        try {
            assertEquals(1.0, liveDataService.getLiveUserMetric(PLATFORM, STREAMER.getUid(), SUPER_CHAT_USERS_COUNT, 2L), 0.0001);
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }
        try {
            assertEquals(40.0, liveDataService.getLiveUserMetric(PLATFORM, STREAMER.getUid(),
                    BilibiliLiveMetric.SUPER_CHAT_USERS, 1L), 0.0001, "金额表照旧按金额计，两表互不串门");
        } catch (Throwable t) {
            red.add("③ " + t.getMessage());
        }
        if (!red.isEmpty()) {
            org.junit.jupiter.api.Assertions.fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    @Test
    @DisplayName("大航海开通与续费各记各的人次，认不出的不硬归一边")
    void guardOpenAndRenewAreCountedSeparately() {
        aggregator.onCaptain(captain(user(3L), 138.0, GuardOperateType.RENEWAL));
        aggregator.onGovernor(governor(user(4L), 1998.0, GuardOperateType.ACTIVATION));
        aggregator.onCommander(commander(user(3L), 998.0, GuardOperateType.UNKNOWN));
        aggregator.onCaptain(captain(user(5L), 138.0, null));

        List<String> red = new java.util.ArrayList<>();
        try {
            assertEquals(1.0, liveDataService.getLiveMetric(PLATFORM, STREAMER.getUid(), GUARD_OPEN_COUNT), 0.0001,
                    "只有总督那一单是开通");
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        try {
            assertEquals(1.0, liveDataService.getLiveMetric(PLATFORM, STREAMER.getUid(), GUARD_RENEW_COUNT), 0.0001,
                    "只有舰长那一单是续费");
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }
        try {
            assertEquals(4, Math.round(liveDataService.getLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.CAPTAIN_COUNT)
                    + liveDataService.getLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.COMMANDER_COUNT)
                    + liveDataService.getLiveMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GOVERNOR_COUNT)),
                    "人次总量照旧含认不出开续的那两单——分开的只是口径说明，不是总数");
        } catch (Throwable t) {
            red.add("③ " + t.getMessage());
        }
        if (!red.isEmpty()) {
            org.junit.jupiter.api.Assertions.fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    private static GiftInfo gift(Long id, String name, double price, int count) {
        return new GiftInfo(id, name, price, count, "https://img.example/" + id + ".png");
    }

    private static BilibiliCaptainEvent captain(UserInfo sender, double value, GuardOperateType operateType) {
        BilibiliCaptainEvent event = new BilibiliCaptainEvent(STREAMER, sender, value, 1, "月");
        event.setOperateType(operateType);
        return event;
    }

    private static BilibiliCommanderEvent commander(UserInfo sender, double value, GuardOperateType operateType) {
        BilibiliCommanderEvent event = new BilibiliCommanderEvent(STREAMER, sender, value, 1, "月");
        event.setOperateType(operateType);
        return event;
    }

    private static BilibiliGovernorEvent governor(UserInfo sender, double value, GuardOperateType operateType) {
        BilibiliGovernorEvent event = new BilibiliGovernorEvent(STREAMER, sender, value, 1, "月");
        event.setOperateType(operateType);
        return event;
    }

    private static UserInfo user(long uid) {
        return new UserInfo(uid, "用户" + uid, null);
    }
}
