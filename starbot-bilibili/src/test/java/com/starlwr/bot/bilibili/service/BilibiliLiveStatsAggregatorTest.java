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
import com.starlwr.bot.core.service.DefaultLiveDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 本场直播数据聚合器测试
 * <p>
 * 直接对接真实的数据服务实现（不落盘），逐类事件验证累计口径。
 */
@DisplayName("直播数据聚合器")
class BilibiliLiveStatsAggregatorTest {
    private static final String PLATFORM = "bilibili";

    private static final LiveStreamerInfo STREAMER = new LiveStreamerInfo(10001L, "主播甲", 20002L);

    private DefaultLiveDataService liveDataService;

    private BilibiliLiveStatsAggregator aggregator;

    @BeforeEach
    void setUp() {
        liveDataService = new DefaultLiveDataService(new StarBotCoreProperties());
        aggregator = new BilibiliLiveStatsAggregator(liveDataService);
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
    @DisplayName("礼物排行榜按实付排，不能让运气好的盲盒玩家挤掉真金白银的人")
    void giftRankingUsesPaidNotOpenedValue() {
        // 甲花 100 元开盲盒，只开出价值 1 元的东西
        aggregator.onRandomGift(new BilibiliRandomGiftEvent(STREAMER, user(1L), gift(100.0, 1), gift(1.0, 1), 100.0, 1.0));
        // 乙花 10 元开盲盒，运气好开出 500 元
        aggregator.onRandomGift(new BilibiliRandomGiftEvent(STREAMER, user(2L), gift(10.0, 1), gift(500.0, 1), 10.0, 500.0));

        List<UserScore> ranking = liveDataService.getLiveUserRanking(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.GIFT_USERS, 10);

        assertEquals(1L, ranking.get(0).userUid(), "花了 100 元的应排第一，而不是运气好的那位");
        assertEquals(100.0, ranking.get(0).score(), 0.0001, "得分应是实付而非开出面值");
        assertEquals(10.0, ranking.get(1).score(), 0.0001);
    }

    @Test
    @DisplayName("盲盒的实付与到手价值应各归各的口径")
    void randomGiftSplitsPaidAndValue() {
        // 花 9.9 买盲盒，开出价值 6.6 的礼物
        aggregator.onRandomGift(new BilibiliRandomGiftEvent(STREAMER, user(1L), gift(9.9, 1), gift(6.6, 1), 9.9, 6.6));

        assertEquals(6.6, metric(BilibiliLiveMetric.GIFT_VALUE), 0.0001, "到手价值是开出物的价值");
        assertEquals(9.9, metric(BilibiliLiveMetric.GIFT_PAID), 0.0001, "实付是盲盒本身的价");
    }

    @Test
    @DisplayName("普通礼物的实付与到手价值相等")
    void paidGiftHasSamePaidAndValue() {
        aggregator.onPaidGift(new BilibiliPaidGiftEvent(STREAMER, user(1L), gift(0.1, 1), 0.1));

        assertEquals(0.1, metric(BilibiliLiveMetric.GIFT_VALUE), 0.0001);
        assertEquals(0.1, metric(BilibiliLiveMetric.GIFT_PAID), 0.0001);
    }

    @Test
    @DisplayName("实付取不到时应回退到到手价值，而不是当作没花钱")
    void missingPaidFallsBackToValue() {
        BilibiliPaidGiftEvent event = new BilibiliPaidGiftEvent(STREAMER, user(1L), gift(5.0, 1), 5.0);
        event.setPaid(null);

        aggregator.onPaidGift(event);

        assertEquals(5.0, metric(BilibiliLiveMetric.GIFT_PAID), 0.0001, "记成 0 会让营收凭空少一截");
    }

    @Test
    @DisplayName("事件带了实付时以实付为准——背包礼物正是靠这条区分开的")
    void explicitPaidWins() {
        // 背包礼物：主播收到 5 元的价值，而观众一分钱没花
        BilibiliPaidGiftEvent event = new BilibiliPaidGiftEvent(STREAMER, user(1L), gift(5.0, 1), 5.0);
        event.setPaid(0.0);

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
