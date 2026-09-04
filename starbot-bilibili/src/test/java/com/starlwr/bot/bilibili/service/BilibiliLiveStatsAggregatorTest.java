package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.bilibili.event.live.BilibiliCaptainEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliCommanderEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliEmojiEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliGovernorEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliDanmuEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliEnterRoomEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliFollowEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliLikeUpdateEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliPaidGiftEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliOnlineRankCountUpdateEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliRandomGiftEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliWatchedUpdateEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliSuperChatEvent;
import com.starlwr.bot.bilibili.model.BilibiliLiveMetric;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.model.GiftInfo;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserScore;
import com.starlwr.bot.core.model.UserInfo;
import com.starlwr.bot.core.model.DanmuRecord;
import com.starlwr.bot.core.service.DefaultLiveDataService;
import com.starlwr.bot.core.service.LiveDetailArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本场直播数据聚合器测试
 * <p>
 * 直接对接真实的数据服务实现（不落盘），逐类事件验证累计口径。
 */
@DisplayName("直播数据聚合器")
class BilibiliLiveStatsAggregatorTest {
    private static final String PLATFORM = "bilibili";

    private static final LiveStreamerInfo STREAMER = new LiveStreamerInfo(10001L, "主播甲", 20002L);

    /**
     * 弹幕原文要落盘，给它一个临时目录
     * <p>
     * 🔴 不给的话，{@code liveDataPath} 的默认值 {@code data.json} 没有父目录，
     * 明细会落到<b>进程的当前目录</b>——跑一次测试就在仓库里长出一个 {@code details/}。
     */
    @TempDir
    Path dir;

    private DefaultLiveDataService liveDataService;

    private LiveDetailArchive details;

    private BilibiliLiveStatsAggregator aggregator;

    @BeforeEach
    void setUp() {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());

        liveDataService = new DefaultLiveDataService(properties);
        details = new LiveDetailArchive(properties);
        aggregator = new BilibiliLiveStatsAggregator(liveDataService, details);
    }

    @Test
    @DisplayName("弹幕应累计条数与独立人数")
    void danmuCountsMessagesAndUsers() {
        aggregator.onDanmu(new BilibiliDanmuEvent(STREAMER, user(1L), "你好", "你好"));
        aggregator.onDanmu(new BilibiliDanmuEvent(STREAMER, user(2L), "晚上好", "晚上好"));
        aggregator.onDanmu(new BilibiliDanmuEvent(STREAMER, user(1L), "又来了", "又来了"));

        assertEquals(3.0, metric(BilibiliLiveMetric.DANMU_COUNT));
        assertEquals(2, users(BilibiliLiveMetric.DANMU_USERS));
    }

    @Test
    @DisplayName("付费礼物应累计价值与送礼人数")
    void paidGiftCountsValueAndUsers() {
        aggregator.onPaidGift(new BilibiliPaidGiftEvent(STREAMER, user(1L), gift(5.2, 1), 5.2));
        aggregator.onPaidGift(new BilibiliPaidGiftEvent(STREAMER, user(2L), gift(2.4, 2), 4.8));

        assertEquals(10.0, metric(BilibiliLiveMetric.GIFT_VALUE));
        assertEquals(2, users(BilibiliLiveMetric.GIFT_USERS));
    }

    @Test
    @DisplayName("礼物计分表应记价值而非次数，供排行榜按金额排序")
    void giftScoreIsValueNotCount() {
        // 用户 1 送两次共 3 元，用户 2 送一次 10 元：按次数排是用户 1 在前，按价值排应是用户 2
        aggregator.onPaidGift(new BilibiliPaidGiftEvent(STREAMER, user(1L), gift(1.5, 1), 1.5));
        aggregator.onPaidGift(new BilibiliPaidGiftEvent(STREAMER, user(1L), gift(1.5, 1), 1.5));
        aggregator.onPaidGift(new BilibiliPaidGiftEvent(STREAMER, user(2L), gift(10.0, 1), 10.0));

        List<UserScore> ranking = liveDataService.getLiveUserRanking(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GIFT_USERS, 10);

        assertEquals(2L, ranking.get(0).userUid(), "金额高者应排在前");
        assertEquals(10.0, ranking.get(0).score());
        assertEquals(3.0, ranking.get(1).score());
    }

    @Test
    @DisplayName("醒目留言应按用户计分，供 SC 排行榜使用")
    void superChatScoresUsers() {
        aggregator.onSuperChat(new BilibiliSuperChatEvent(STREAMER, user(1L), "加油", 30.0));
        aggregator.onSuperChat(new BilibiliSuperChatEvent(STREAMER, user(1L), "再来", 20.0));
        aggregator.onSuperChat(new BilibiliSuperChatEvent(STREAMER, user(2L), "好耶", 100.0));

        List<UserScore> ranking = liveDataService.getLiveUserRanking(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.SUPER_CHAT_USERS, 10);

        assertEquals(2, ranking.size());
        assertEquals(100.0, ranking.get(0).score());
        assertEquals(50.0, ranking.get(1).score());
    }

    @Test
    @DisplayName("盲盒应同时按个数与盈亏两个维度计分")
    void randomGiftScoresCountAndProfit() {
        // 亏 3.3
        aggregator.onRandomGift(new BilibiliRandomGiftEvent(STREAMER, user(1L), gift(9.9, 1), gift(6.6, 1), 9.9, 6.6));
        // 赚 5.0
        aggregator.onRandomGift(new BilibiliRandomGiftEvent(STREAMER, user(2L), gift(5.0, 1), gift(10.0, 1), 5.0, 10.0));

        assertEquals(1.0, liveDataService.getLiveUserMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.BOX_USERS, 1L));
        assertEquals(-3.3, liveDataService.getLiveUserMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.BOX_PROFIT_USERS, 1L), 0.0001);
        assertEquals(5.0, liveDataService.getLiveUserMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.BOX_PROFIT_USERS, 2L), 0.0001);

        // 盈亏排行榜：赚的排在亏的前面
        List<UserScore> ranking = liveDataService.getLiveUserRanking(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.BOX_PROFIT_USERS, 10);
        assertEquals(2L, ranking.get(0).userUid());
    }

    @Test
    @DisplayName("三种大航海应汇入同一份用户计分表")
    void guardLevelsShareOneScoreTable() {
        aggregator.onCaptain(new BilibiliCaptainEvent(STREAMER, user(1L), 138.0, 1, "月"));
        aggregator.onCommander(new BilibiliCommanderEvent(STREAMER, user(1L), 1998.0, 1, "月"));
        aggregator.onGovernor(new BilibiliGovernorEvent(STREAMER, user(2L), 19998.0, 1, "月"));

        assertEquals(2.0, liveDataService.getLiveUserMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GUARD_USERS, 1L));
        assertEquals(2, users(BilibiliLiveMetric.GUARD_USERS));
    }

    @Test
    @DisplayName("弹幕计分表的得分即该用户的弹幕条数")
    void danmuScoreIsMessageCount() {
        aggregator.onDanmu(new BilibiliDanmuEvent(STREAMER, user(1L), "一", "一"));
        aggregator.onDanmu(new BilibiliDanmuEvent(STREAMER, user(1L), "二", "二"));
        aggregator.onEmoji(new BilibiliEmojiEvent(STREAMER, user(1L), null));

        assertEquals(3.0, liveDataService.getLiveUserMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.DANMU_USERS, 1L));
    }

    @Test
    @DisplayName("盲盒应分别累计个数、盈亏与开出礼物的价值")
    void randomGiftCountsBoxAndProfit() {
        // 花 9.9 买盲盒，开出价值 6.6 的礼物：亏 3.3
        aggregator.onRandomGift(new BilibiliRandomGiftEvent(STREAMER, user(1L), gift(9.9, 1), gift(6.6, 1), 9.9, 6.6));

        assertEquals(1.0, metric(BilibiliLiveMetric.BOX_COUNT));
        assertEquals(-3.3, metric(BilibiliLiveMetric.BOX_PROFIT), 0.0001);
        assertEquals(6.6, metric(BilibiliLiveMetric.GIFT_VALUE));
        assertEquals(1, users(BilibiliLiveMetric.GIFT_USERS));
    }

    @Test
    @DisplayName("⚠️ 背包礼物必须上礼物榜：它实扣为零，但主播确实收到了")
    void bagGiftIsOnTheRankingNotOnlyInTheTotal() {
        // 2026-08-10 的实况：观众送出一份 80 元的背包礼物（抢红包白得的，实扣 0），
        // 礼物榜显示 ¥1.1、礼物总额 ¥86.6——同一份报告里同一件事两个数。
        // 这里把那一份礼物单独喂进去，榜与总额必须都是 80
        BilibiliPaidGiftEvent bagGift = new BilibiliPaidGiftEvent(STREAMER, user(1L), gift(80.0, 1), 80.0);
        bagGift.setCharged(0.0);

        aggregator.onPaidGift(bagGift);

        List<UserScore> ranking = liveDataService.getLiveUserRanking(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GIFT_USERS, 10);

        // 旧口径下这个人并不是从计分表里消失（记 0 也会建条目，所以「N 人送出」一直是对的），
        // 而是得分为 0、排到榜尾——于是在只出前几名的榜上看不见，看见的也是 ¥0.00
        assertEquals(1, ranking.size());
        assertEquals(80.0, ranking.get(0).score(), 0.0001, "榜上记的是主播到手价值，不是 0");
        assertEquals(80.0, metric(BilibiliLiveMetric.GIFT_VALUE), 0.0001);
        assertEquals(0.0, metric(BilibiliLiveMetric.GIFT_PAID), 0.0001, "实扣仍然如实记 0，只是不再拿它排榜");
    }

    @Test
    @DisplayName("⚠️ 礼物榜逐行相加应当等于礼物总额")
    void giftRankingSumsToTheTotal() {
        // 一份普通礼物、一份背包礼物、一次盲盒：三种口径会打架的形状凑在一场里
        aggregator.onPaidGift(new BilibiliPaidGiftEvent(STREAMER, user(1L), gift(5.2, 1), 5.2));

        BilibiliPaidGiftEvent bagGift = new BilibiliPaidGiftEvent(STREAMER, user(2L), gift(80.0, 1), 80.0);
        bagGift.setCharged(0.0);
        aggregator.onPaidGift(bagGift);

        aggregator.onRandomGift(new BilibiliRandomGiftEvent(STREAMER, user(3L), gift(9.9, 1), gift(6.6, 1), 9.9, 6.6));

        List<UserScore> ranking = liveDataService.getLiveUserRanking(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GIFT_USERS, 10);
        double sum = ranking.stream().mapToDouble(UserScore::score).sum();

        // 卡片与榜单能相加对上，是主播读得懂报告的前提；对不上的话，
        // 差额既可能是「有人没上榜」也可能是「榜只取了前几名」，读的人无从分辨
        assertEquals(metric(BilibiliLiveMetric.GIFT_VALUE), sum, 0.0001, "榜与总额必须同口径");
        assertEquals(91.8, sum, 0.0001);
    }

    @Test
    @DisplayName("盲盒「按运气排名」的顾虑由盲盒盈亏榜表达，不靠改礼物榜")
    void luckIsExpressedByTheBoxProfitRankingInstead() {
        // 甲花 100 元开盲盒，只开出价值 1 元的东西
        aggregator.onRandomGift(new BilibiliRandomGiftEvent(STREAMER, user(1L), gift(100.0, 1), gift(1.0, 1), 100.0, 1.0));
        // 乙花 10 元开盲盒，运气好开出 500 元
        aggregator.onRandomGift(new BilibiliRandomGiftEvent(STREAMER, user(2L), gift(10.0, 1), gift(500.0, 1), 10.0, 500.0));

        List<UserScore> gifts = liveDataService.getLiveUserRanking(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GIFT_USERS, 10);
        assertEquals(2L, gifts.get(0).userUid(), "礼物榜按到手价值排，开出 500 的在前");
        assertEquals(500.0, gifts.get(0).score(), 0.0001);
        assertEquals(1.0, gifts.get(1).score(), 0.0001);

        // 「谁真的掏了钱」这件事没有丢，它在盲盒盈亏榜上：甲亏 99，乙赚 490
        List<UserScore> profit = liveDataService.getLiveUserRanking(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.BOX_PROFIT_USERS, 10);
        assertEquals(2L, profit.get(0).userUid());
        assertEquals(490.0, profit.get(0).score(), 0.0001);
        assertEquals(-99.0, profit.get(1).score(), 0.0001, "花 100 只开出 1 的那位，亏在这张榜上一眼可见");
    }

    @Test
    @DisplayName("盲盒的实扣与到手价值应各归各的口径")
    void randomGiftSplitsPaidAndValue() {
        // 花 9.9 买盲盒，开出价值 6.6 的礼物
        aggregator.onRandomGift(new BilibiliRandomGiftEvent(STREAMER, user(1L), gift(9.9, 1), gift(6.6, 1), 9.9, 6.6));

        assertEquals(6.6, metric(BilibiliLiveMetric.GIFT_VALUE), 0.0001, "到手价值是开出物的价值");
        assertEquals(9.9, metric(BilibiliLiveMetric.GIFT_PAID), 0.0001, "实扣是盲盒本身的价");
    }

    @Test
    @DisplayName("普通礼物的实扣与到手价值相等")
    void paidGiftHasSamePaidAndValue() {
        aggregator.onPaidGift(new BilibiliPaidGiftEvent(STREAMER, user(1L), gift(0.1, 1), 0.1));

        assertEquals(0.1, metric(BilibiliLiveMetric.GIFT_VALUE), 0.0001);
        assertEquals(0.1, metric(BilibiliLiveMetric.GIFT_PAID), 0.0001);
    }

    @Test
    @DisplayName("实扣取不到时应回退到到手价值，而不是当作没花钱")
    void missingPaidFallsBackToValue() {
        BilibiliPaidGiftEvent event = new BilibiliPaidGiftEvent(STREAMER, user(1L), gift(5.0, 1), 5.0);
        event.setCharged(null);

        aggregator.onPaidGift(event);

        assertEquals(5.0, metric(BilibiliLiveMetric.GIFT_PAID), 0.0001, "记成 0 会让营收凭空少一截");
    }

    @Test
    @DisplayName("事件带了实扣时以实扣为准——背包礼物正是靠这条区分开的")
    void explicitPaidWins() {
        // 背包礼物：主播收到 5 元的价值，而观众一分钱没花
        BilibiliPaidGiftEvent event = new BilibiliPaidGiftEvent(STREAMER, user(1L), gift(5.0, 1), 5.0);
        event.setCharged(0.0);

        aggregator.onPaidGift(event);

        assertEquals(5.0, metric(BilibiliLiveMetric.GIFT_VALUE), 0.0001, "主播确实收到了");
        assertEquals(0.0, metric(BilibiliLiveMetric.GIFT_PAID), 0.0001, "但观众没花钱");
    }

    @Test
    @DisplayName("看过人数是瞬时读数，重复下发只取最大而不是累加")
    void watchedCountTakesMaxNotSum() {
        // 平台每分钟要下发好几次，每次给的都是「当前累计是多少」。
        // 累加的话，8000 这个真实值下发三次就成了 24000——而这个数看起来完全合理
        aggregator.onWatchedUpdate(new BilibiliWatchedUpdateEvent(STREAMER, 8000, "8000人看过"));
        aggregator.onWatchedUpdate(new BilibiliWatchedUpdateEvent(STREAMER, 8000, "8000人看过"));
        aggregator.onWatchedUpdate(new BilibiliWatchedUpdateEvent(STREAMER, 8376, "8376人看过"));

        assertEquals(8376.0, metric(BilibiliLiveMetric.WATCHED_COUNT), 0.0001);
    }

    @Test
    @DisplayName("高能用户数会涨落，取本场峰值")
    void onlineRankCountTakesPeak() {
        aggregator.onOnlineRankCountUpdate(new BilibiliOnlineRankCountUpdateEvent(STREAMER, 1305, 1305, "1305"));
        aggregator.onOnlineRankCountUpdate(new BilibiliOnlineRankCountUpdateEvent(STREAMER, 3831, 3831, "3831"));
        // 高能榜人数会掉下去，峰值不该跟着掉
        aggregator.onOnlineRankCountUpdate(new BilibiliOnlineRankCountUpdateEvent(STREAMER, 2100, 2100, "2100"));

        assertEquals(3831.0, metric(BilibiliLiveMetric.ONLINE_RANK_COUNT), 0.0001);
    }

    @Test
    @DisplayName("计数缺失时不应写入，免得把没有的数据记成 0")
    void missingCountIsIgnored() {
        aggregator.onWatchedUpdate(new BilibiliWatchedUpdateEvent(STREAMER, 8376, "8376人看过"));
        aggregator.onWatchedUpdate(new BilibiliWatchedUpdateEvent(STREAMER, null, null));

        assertEquals(8376.0, metric(BilibiliLiveMetric.WATCHED_COUNT), 0.0001);
    }

    @Test
    @DisplayName("醒目留言应累计条数与价值")
    void superChatCountsAndValue() {
        aggregator.onSuperChat(new BilibiliSuperChatEvent(STREAMER, user(1L), "加油", 30.0));
        aggregator.onSuperChat(new BilibiliSuperChatEvent(STREAMER, user(2L), "好耶", 50.0));

        assertEquals(2.0, metric(BilibiliLiveMetric.SUPER_CHAT_COUNT));
        assertEquals(80.0, metric(BilibiliLiveMetric.SUPER_CHAT_VALUE));
    }

    @Test
    @DisplayName("舰长应累计人次与价值（价格乘以月数）")
    void captainCountsAndValue() {
        aggregator.onCaptain(new BilibiliCaptainEvent(STREAMER, user(1L), 138.0, 3, "月"));

        assertEquals(1.0, metric(BilibiliLiveMetric.CAPTAIN_COUNT));
        assertEquals(414.0, metric(BilibiliLiveMetric.GUARD_VALUE));
    }

    @Test
    @DisplayName("点赞总数应取服务端下发的最大值")
    void likeTotalKeepsMax() {
        aggregator.onLikeUpdate(new BilibiliLikeUpdateEvent(STREAMER, 100));
        aggregator.onLikeUpdate(new BilibiliLikeUpdateEvent(STREAMER, 300));
        aggregator.onLikeUpdate(new BilibiliLikeUpdateEvent(STREAMER, 200));

        assertEquals(300.0, metric(BilibiliLiveMetric.LIKE_TOTAL));
    }

    @Test
    @DisplayName("进房与关注应分别累计独立人数与人次")
    void enterAndFollow() {
        aggregator.onEnterRoom(new BilibiliEnterRoomEvent(STREAMER, user(1L)));
        aggregator.onEnterRoom(new BilibiliEnterRoomEvent(STREAMER, user(1L)));
        aggregator.onEnterRoom(new BilibiliEnterRoomEvent(STREAMER, user(2L)));
        aggregator.onFollow(new BilibiliFollowEvent(STREAMER, user(3L)));

        assertEquals(2, users(BilibiliLiveMetric.ENTER_USERS));
        assertEquals(1.0, metric(BilibiliLiveMetric.FOLLOW_COUNT));
    }

    @Test
    @DisplayName("发送者缺失时应跳过独立人数统计而不抛异常")
    void toleratesMissingSender() {
        aggregator.onDanmu(new BilibiliDanmuEvent(STREAMER, null, "路人弹幕", "路人弹幕"));

        assertEquals(1.0, metric(BilibiliLiveMetric.DANMU_COUNT));
        assertEquals(0, users(BilibiliLiveMetric.DANMU_USERS));
    }

    // ---------------------------------------------------------------- 弹幕原文留档

    @Test
    @DisplayName("弹幕原文逐条留下：文字、表情、付费留言各标各的类型")
    void keepsRawDanmuByType() {
        long start = 1_700_000_000_000L;
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), start);

        aggregator.onDanmu(new BilibiliDanmuEvent(STREAMER, user(1L), "好听", "好听"));
        aggregator.onEmoji(new BilibiliEmojiEvent(STREAMER, user(2L),
                new com.starlwr.bot.core.model.EmojiInfo("1", "笑哭", "https://pic.example.invalid/e.png")));
        aggregator.onSuperChat(new BilibiliSuperChatEvent(STREAMER, user(3L), "谢谢主播", 30.0));

        List<DanmuRecord> records = details.readDanmu(PLATFORM, STREAMER.getUid(), start);

        assertEquals(3, records.size());
        assertEquals(List.of("好听", "笑哭", "谢谢主播"), records.stream().map(DanmuRecord::text).toList());
        assertEquals(List.of(DanmuRecord.Type.DANMU, DanmuRecord.Type.EMOJI, DanmuRecord.Type.SUPER_CHAT),
                records.stream().map(DanmuRecord::type).toList());
        assertEquals("用户1", records.get(0).uname(), "昵称要记当时的值——昵称会改, 事后再查是另一个名字");
    }

    @Test
    @DisplayName("按原文数出来的弹幕密度，与弹幕条数同口径")
    void rawDanmuMatchesTheDanmuCount() {
        long start = 1_700_000_000_000L;
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), start);

        aggregator.onDanmu(new BilibiliDanmuEvent(STREAMER, user(1L), "一", "一"));
        aggregator.onEmoji(new BilibiliEmojiEvent(STREAMER, user(2L),
                new com.starlwr.bot.core.model.EmojiInfo("1", "笑哭", "https://pic.example.invalid/e.png")));
        aggregator.onSuperChat(new BilibiliSuperChatEvent(STREAMER, user(3L), "谢谢主播", 30.0));

        long counted = details.readDanmu(PLATFORM, STREAMER.getUid(), start).stream()
                .filter(DanmuRecord::countsAsDanmu).count();

        assertEquals((long) metric(BilibiliLiveMetric.DANMU_COUNT), counted,
                "表情包计入弹幕条数, 付费留言不计——原文这一侧漏了表情, "
                        + "按原文算出来的高能时刻就会比曲线上的峰低一截");
    }

    @Test
    @DisplayName("没记到开播时刻就不留原文——留下的原文没有一场直播认领得了")
    void noStartTimeNoRawDanmu() {
        aggregator.onDanmu(new BilibiliDanmuEvent(STREAMER, user(1L), "开播前", "开播前"));

        assertTrue(details.list().isEmpty());
    }

    @Test
    @DisplayName("空弹幕不占一行：一条没有内容的记录答不出任何问题")
    void blankTextIsNotArchived() {
        long start = 1_700_000_000_000L;
        liveDataService.setLiveStartTime(PLATFORM, STREAMER.getUid(), start);

        aggregator.onDanmu(new BilibiliDanmuEvent(STREAMER, user(1L), "", ""));
        aggregator.onEmoji(new BilibiliEmojiEvent(STREAMER, user(2L), null));

        assertTrue(details.readDanmu(PLATFORM, STREAMER.getUid(), start).isEmpty());
    }

    private double metric(String name) {
        return liveDataService.getLiveMetric(PLATFORM, STREAMER.getUid(), name);
    }

    private int users(String name) {
        return liveDataService.getLiveMetricUserCount(PLATFORM, STREAMER.getUid(), name);
    }

    private UserInfo user(Long uid) {
        return new UserInfo(uid, "用户" + uid, null);
    }

    private GiftInfo gift(double price, int count) {
        return new GiftInfo(1L, "礼物", price, count, null);
    }
}
