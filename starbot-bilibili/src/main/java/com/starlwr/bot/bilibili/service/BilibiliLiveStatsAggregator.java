package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.bilibili.event.live.BilibiliCaptainEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliCommanderEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliDanmuEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliEmojiEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliEnterRoomEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliFollowEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliFreeGiftEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliGovernorEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliLikeEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliLikeUpdateEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliPaidGiftEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliRandomGiftEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliShareEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliSuperChatEvent;
import com.starlwr.bot.bilibili.model.BilibiliLiveMetric;
import com.starlwr.bot.bilibili.util.DanmuWordUtil;
import com.starlwr.bot.core.event.live.StarBotBaseLiveEvent;
import com.starlwr.bot.core.model.UserInfo;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;

import java.util.Optional;
import java.util.Set;

/**
 * 本场直播数据聚合器
 * <p>
 * 订阅直播间事件流，把弹幕、礼物、醒目留言、大航海等数据累计为本场直播的统计指标，
 * 供下播报告绘制使用。指标存放于 {@link LiveDataService}（随直播数据一并持久化，
 * 程序中途重启不丢），并在开播时由 {@code resetLiveData} 清零。
 * <p>
 * 不判断直播间是否处于直播中：下播到次日开播之间累计的少量数据会在开播清零时一并丢弃，
 * 不会出现在任何一场报告里。
 */
@Slf4j
@StarBotComponent
public class BilibiliLiveStatsAggregator {
    private final LiveDataService liveDataService;

    @Autowired
    public BilibiliLiveStatsAggregator(LiveDataService liveDataService) {
        this.liveDataService = liveDataService;

        // jieba 词典首次加载约一秒，事件在直播间消息线程上同步分发，
        // 放到后台线程预热，避免首条弹幕把消息处理卡住
        Thread warmUp = new Thread(DanmuWordUtil::warmUp, "jieba-warm-up");
        warmUp.setDaemon(true);
        warmUp.start();
    }

    /**
     * 弹幕
     */
    @EventListener(BilibiliDanmuEvent.class)
    public void onDanmu(BilibiliDanmuEvent event) {
        increment(event, BilibiliLiveMetric.DANMU_COUNT, 1);
        recordUser(event, BilibiliLiveMetric.DANMU_USERS, event.getSender());
        recordWords(event, StringUtil.isNotBlank(event.getContentText()) ? event.getContentText() : event.getContent());
    }

    /**
     * 表情包弹幕，计入弹幕
     */
    @EventListener(BilibiliEmojiEvent.class)
    public void onEmoji(BilibiliEmojiEvent event) {
        increment(event, BilibiliLiveMetric.DANMU_COUNT, 1);
        recordUser(event, BilibiliLiveMetric.DANMU_USERS, event.getSender());
    }

    /**
     * 付费礼物
     */
    @EventListener(BilibiliPaidGiftEvent.class)
    public void onPaidGift(BilibiliPaidGiftEvent event) {
        double value = Optional.ofNullable(event.getValue()).orElse(0.0);
        // 取不到实付时回退到到手价值，而不是当作 0：把「不知道」记成「没花钱」会让营收凭空少一截
        double paid = Optional.ofNullable(event.getPaid()).orElse(value);

        increment(event, BilibiliLiveMetric.GIFT_VALUE, value);
        increment(event, BilibiliLiveMetric.GIFT_PAID, paid);
        // 计分表记金额而非次数：礼物排行榜比的是送了多少钱，而人数仍是表的大小
        scoreUser(event, BilibiliLiveMetric.GIFT_USERS, event.getSender(), paid);
    }

    /**
     * 免费礼物
     */
    @EventListener(BilibiliFreeGiftEvent.class)
    public void onFreeGift(BilibiliFreeGiftEvent event) {
        int count = Optional.ofNullable(event.getGiftInfo())
                .map(gift -> Optional.ofNullable(gift.getCount()).orElse(1))
                .orElse(1);
        increment(event, BilibiliLiveMetric.FREE_GIFT_COUNT, count);
    }

    /**
     * 盲盒
     * <p>
     * <b>盲盒是唯一一处「观众付的」与「主播收的」会差很远的地方</b>，两个数各归各的口径：
     * 开出物的价值计入 {@code GIFT_VALUE}（主播确实收到了那么多），
     * 盲盒本身的价计入 {@code GIFT_PAID}（观众确实只花了那么多），差额记为盲盒盈亏。
     * <p>
     * <b>礼物排行榜按实付排。</b>按到手价值排的话，花 100 元开出一堆小心心的人会排在榜尾，
     * 花 10 元中了大奖的人排到榜首——那是在按运气排名，而不是按心意。
     * <p>
     * 事件里 {@code price} 是盲盒实付、{@code value} 是开出物面值。
     * 这两个字段名相当反直觉（{@code randomGiftInfo} 指的是<b>投入的盲盒</b>而不是开出的东西），
     * 改动此处前请先确认方向。
     */
    @EventListener(BilibiliRandomGiftEvent.class)
    public void onRandomGift(BilibiliRandomGiftEvent event) {
        int count = Optional.ofNullable(event.getRandomGiftInfo())
                .map(gift -> Optional.ofNullable(gift.getCount()).orElse(1))
                .orElse(1);
        double value = Optional.ofNullable(event.getValue()).orElse(0.0);
        double price = Optional.ofNullable(event.getPrice()).orElse(0.0);
        double paid = Optional.ofNullable(event.getPaid()).orElse(price);

        increment(event, BilibiliLiveMetric.BOX_COUNT, count);
        increment(event, BilibiliLiveMetric.BOX_PROFIT, value - price);
        increment(event, BilibiliLiveMetric.GIFT_VALUE, value);
        increment(event, BilibiliLiveMetric.GIFT_PAID, paid);
        scoreUser(event, BilibiliLiveMetric.GIFT_USERS, event.getSender(), paid);
        scoreUser(event, BilibiliLiveMetric.BOX_USERS, event.getSender(), count);
        scoreUser(event, BilibiliLiveMetric.BOX_PROFIT_USERS, event.getSender(), value - price);
    }

    /**
     * 醒目留言
     */
    @EventListener(BilibiliSuperChatEvent.class)
    public void onSuperChat(BilibiliSuperChatEvent event) {
        double value = Optional.ofNullable(event.getValue()).orElse(0.0);
        increment(event, BilibiliLiveMetric.SUPER_CHAT_COUNT, 1);
        increment(event, BilibiliLiveMetric.SUPER_CHAT_VALUE, value);
        scoreUser(event, BilibiliLiveMetric.SUPER_CHAT_USERS, event.getSender(), value);
    }

    /**
     * 舰长
     */
    @EventListener(BilibiliCaptainEvent.class)
    public void onCaptain(BilibiliCaptainEvent event) {
        increment(event, BilibiliLiveMetric.CAPTAIN_COUNT, 1);
        increment(event, BilibiliLiveMetric.GUARD_VALUE, Optional.ofNullable(event.getValue()).orElse(0.0));
        scoreUser(event, BilibiliLiveMetric.GUARD_USERS, event.getSender(), 1);
    }

    /**
     * 提督
     */
    @EventListener(BilibiliCommanderEvent.class)
    public void onCommander(BilibiliCommanderEvent event) {
        increment(event, BilibiliLiveMetric.COMMANDER_COUNT, 1);
        increment(event, BilibiliLiveMetric.GUARD_VALUE, Optional.ofNullable(event.getValue()).orElse(0.0));
        scoreUser(event, BilibiliLiveMetric.GUARD_USERS, event.getSender(), 1);
    }

    /**
     * 总督
     */
    @EventListener(BilibiliGovernorEvent.class)
    public void onGovernor(BilibiliGovernorEvent event) {
        increment(event, BilibiliLiveMetric.GOVERNOR_COUNT, 1);
        increment(event, BilibiliLiveMetric.GUARD_VALUE, Optional.ofNullable(event.getValue()).orElse(0.0));
        scoreUser(event, BilibiliLiveMetric.GUARD_USERS, event.getSender(), 1);
    }

    /**
     * 关注
     */
    @EventListener(BilibiliFollowEvent.class)
    public void onFollow(BilibiliFollowEvent event) {
        increment(event, BilibiliLiveMetric.FOLLOW_COUNT, 1);
    }

    /**
     * 进入直播间
     */
    @EventListener(BilibiliEnterRoomEvent.class)
    public void onEnterRoom(BilibiliEnterRoomEvent event) {
        recordUser(event, BilibiliLiveMetric.ENTER_USERS, event.getSender());
    }

    /**
     * 点赞（单次点击）
     */
    @EventListener(BilibiliLikeEvent.class)
    public void onLike(BilibiliLikeEvent event) {
        recordUser(event, BilibiliLiveMetric.LIKE_USERS, event.getSender());
    }

    /**
     * 点赞总数更新（服务端下发的单调累计值）
     */
    @EventListener(BilibiliLikeUpdateEvent.class)
    public void onLikeUpdate(BilibiliLikeUpdateEvent event) {
        Integer count = event.getCount();
        if (count != null) {
            max(event, BilibiliLiveMetric.LIKE_TOTAL, count);
        }
    }

    /**
     * 分享直播间
     */
    @EventListener(BilibiliShareEvent.class)
    public void onShare(BilibiliShareEvent event) {
        increment(event, BilibiliLiveMetric.SHARE_COUNT, 1);
    }

    /**
     * 需要画成曲线的指标
     * <p>
     * 只挑「能看出直播节奏」的那几项：弹幕看热度，礼物与醒目留言看收益，
     * 盲盒与大航海看爆发点。进场、点赞、分享之类画出来只是一条噪声带，不值得占版面。
     */
    private static final Set<String> SERIES_METRICS = Set.of(
            BilibiliLiveMetric.DANMU_COUNT,
            BilibiliLiveMetric.GIFT_VALUE,
            BilibiliLiveMetric.SUPER_CHAT_VALUE,
            BilibiliLiveMetric.BOX_COUNT,
            BilibiliLiveMetric.BOX_PROFIT,
            BilibiliLiveMetric.GUARD_VALUE);

    private void increment(StarBotBaseLiveEvent event, String metric, double delta) {
        if (event.getSource() == null || event.getSource().getUid() == null) {
            return;
        }
        liveDataService.incrementLiveMetric(event.getPlatform(), event.getSource().getUid(), metric, delta);

        // 曲线与总量共用指标名，且由同一次调用写入：两者天然对得上，
        // 不会出现「卡片说 100 条弹幕、曲线加起来只有 80」这种自相矛盾
        if (SERIES_METRICS.contains(metric)) {
            liveDataService.incrementLiveSeries(event.getPlatform(), event.getSource().getUid(),
                    metric, event.getTimestamp(), delta);
        }
    }

    private void max(StarBotBaseLiveEvent event, String metric, double value) {
        if (event.getSource() == null || event.getSource().getUid() == null) {
            return;
        }
        liveDataService.maxLiveMetric(event.getPlatform(), event.getSource().getUid(), metric, value);
    }

    private void recordUser(StarBotBaseLiveEvent event, String metric, UserInfo sender) {
        scoreUser(event, metric, sender, 1);
    }

    /**
     * 为用户在某项指标上计分，供排行榜与个人数据查询使用
     * <p>
     * 计分表的大小即独立人数，因此计人数与计分共用同一份数据。
     */
    private void scoreUser(StarBotBaseLiveEvent event, String metric, UserInfo sender, double delta) {
        if (event.getSource() == null || event.getSource().getUid() == null
                || sender == null || sender.getUid() == null) {
            return;
        }
        liveDataService.incrementLiveUserMetric(event.getPlatform(), event.getSource().getUid(), metric, sender.getUid(), delta);
        // 昵称与头像地址在事件里现成带着，此时记下，绘制榜单时便不必再逐个请求接口——
        // 一张榜十几个人就是十几次请求，那正是排行榜迟迟没能带上头像的原因
        liveDataService.recordLiveUserName(event.getPlatform(), event.getSource().getUid(), sender.getUid(), sender.getUname());
        liveDataService.recordLiveUserFace(event.getPlatform(), event.getSource().getUid(), sender.getUid(), sender.getFace());
    }

    /**
     * 弹幕分词入词频表，供弹幕词云绘制
     */
    private void recordWords(StarBotBaseLiveEvent event, String text) {
        if (event.getSource() == null || event.getSource().getUid() == null) {
            return;
        }
        for (String word : DanmuWordUtil.extractWords(text)) {
            liveDataService.incrementLiveWordFrequency(event.getPlatform(), event.getSource().getUid(), word);
        }
    }
}
