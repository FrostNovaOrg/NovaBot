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
import com.starlwr.bot.bilibili.event.live.BilibiliRandomGiftEvent;
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
