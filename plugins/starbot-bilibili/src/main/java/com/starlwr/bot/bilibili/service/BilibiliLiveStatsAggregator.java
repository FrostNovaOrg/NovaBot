package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.bilibili.event.live.BilibiliCaptainEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliCommanderEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliDanmuEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliOnlineRankCountUpdateEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliWatchedUpdateEvent;
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
import com.starlwr.bot.core.event.live.common.MembershipEvent;
import com.starlwr.bot.core.model.DanmuRecord;
import com.starlwr.bot.core.model.UserInfo;
import com.starlwr.bot.core.plugin.NovaComponent;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.LiveDetailArchive;
import com.starlwr.bot.core.lang.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;

import java.util.LinkedHashMap;
import java.util.Map;
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
@NovaComponent
public class BilibiliLiveStatsAggregator {
    private final LiveDataService liveDataService;

    /**
     * 弹幕原文留档。逐条追加，与指标累计并列——那边记「有多少」，这边记「说了什么」
     */
    private final LiveDetailArchive details;

    @Autowired
    public BilibiliLiveStatsAggregator(LiveDataService liveDataService, LiveDetailArchive details) {
        this.liveDataService = liveDataService;
        this.details = details;

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
        String text = StringUtil.isNotBlank(event.getContentText()) ? event.getContentText() : event.getContent();
        recordWords(event, text);
        recordDanmu(event, event.getSender(), text, DanmuRecord.Type.DANMU);
    }

    /**
     * 表情包弹幕，计入弹幕
     */
    @EventListener(BilibiliEmojiEvent.class)
    public void onEmoji(BilibiliEmojiEvent event) {
        increment(event, BilibiliLiveMetric.DANMU_COUNT, 1);
        recordUser(event, BilibiliLiveMetric.DANMU_USERS, event.getSender());
        // 表情包留的是表情的名字而不是图片地址：地址会失效，而名字是这条弹幕的意思所在。
        // 它与文字弹幕一同计入弹幕条数，所以也一同留原文，否则按原文数出来的密度会比曲线低一截
        recordDanmu(event, event.getSender(),
                event.getEmoji() == null ? null : event.getEmoji().getName(), DanmuRecord.Type.EMOJI);
    }

    /**
     * 付费礼物
     */
    @EventListener(BilibiliPaidGiftEvent.class)
    public void onPaidGift(BilibiliPaidGiftEvent event) {
        double value = Optional.ofNullable(event.getValue()).orElse(0.0);
        // 取不到实扣时回退到到手价值，而不是当作 0：把「不知道」记成「没花钱」会让营收凭空少一截
        double charged = Optional.ofNullable(event.getCharged()).orElse(value);

        increment(event, BilibiliLiveMetric.GIFT_VALUE, value);
        increment(event, BilibiliLiveMetric.GIFT_PAID, charged);
        // 计分表记金额而非次数：礼物排行榜比的是送了多少，而人数仍是表的大小。
        // 记到手价值而不是实扣——与 GIFT_VALUE 同口径，卡片与榜单必须能相加对上，
        // 理由与背包礼物那个反例见 BilibiliLiveMetric.GIFT_USERS
        scoreUser(event, BilibiliLiveMetric.GIFT_USERS, event.getSender(), value);
        var gift = event.getGiftInfo();
        recordEvent(event, "gift", eventFields(event.getSender(),
                "gid", gift == null ? null : gift.getId(),
                "gn", gift == null ? null : gift.getName(),
                "n", gift == null ? null : gift.getCount(),
                "val", value, "pay", charged, "bag", event.isFromBag()));
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
     * <b>礼物排行榜按到手价值排，不按实扣。</b>这一条改过一次，改的理由要连着反例一起记住：
     * <p>
     * 原先按实扣排，论据是「按到手价值排等于按运气排名：花 100 元开出一堆小心心的人排榜尾，
     * 花 10 元中了大奖的人排榜首」。这个论据<b>只对盲盒成立</b>，
     * 而礼物榜同时还要装下背包礼物——背包礼物实扣恒为 0，于是一份 80 元的背包礼物
     * 在榜上记 0、在总额里记 80。2026-08-10 就这样出过事：礼物榜 ¥1.1，礼物总额 ¥86.6，
     * 同一份报告里同一件事两个数，界面上没有一处说明它们不同口径。
     * <p>
     * <b>只对着盲盒论证，就会得出一个把背包礼物送礼人清零的结论。</b>
     * 盲盒那份顾虑现在由下面的盲盒盈亏榜（{@code BOX_PROFIT_USERS}）单独表达，
     * 它本来就是「按运气」那张榜；为它把主榜改成实扣，代价要每个送背包礼物的观众来付。
     * <p>
     * 事件里 {@code price} 是盲盒实扣、{@code value} 是开出物面值。
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
        double charged = Optional.ofNullable(event.getCharged()).orElse(price);

        increment(event, BilibiliLiveMetric.BOX_COUNT, count);
        increment(event, BilibiliLiveMetric.BOX_PROFIT, value - price);
        increment(event, BilibiliLiveMetric.GIFT_VALUE, value);
        increment(event, BilibiliLiveMetric.GIFT_PAID, charged);
        scoreUser(event, BilibiliLiveMetric.GIFT_USERS, event.getSender(), value);
        scoreUser(event, BilibiliLiveMetric.BOX_USERS, event.getSender(), count);
        scoreUser(event, BilibiliLiveMetric.BOX_PROFIT_USERS, event.getSender(), value - price);
        var box = event.getRandomGiftInfo();
        var gift = event.getGiftInfo();
        recordEvent(event, "box", eventFields(event.getSender(),
                "bn", box == null ? null : box.getName(), "bp", price,
                "gid", gift == null ? null : gift.getId(),
                "gn", gift == null ? null : gift.getName(),
                "n", gift == null ? null : gift.getCount(),
                "val", value, "pft", value - price));
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
        // 付费留言也是一句话，同样留原文；类型分开标，密度统计据此把它排除在外
        recordDanmu(event, event.getSender(), event.getContent(), DanmuRecord.Type.SUPER_CHAT);
    }

    /**
     * 舰长
     */
    @EventListener(BilibiliCaptainEvent.class)
    public void onCaptain(BilibiliCaptainEvent event) {
        increment(event, BilibiliLiveMetric.CAPTAIN_COUNT, 1);
        increment(event, BilibiliLiveMetric.GUARD_VALUE, Optional.ofNullable(event.getValue()).orElse(0.0));
        scoreUser(event, BilibiliLiveMetric.GUARD_USERS, event.getSender(), 1);
        recordGuard(event, 3);
    }

    /**
     * 提督
     */
    @EventListener(BilibiliCommanderEvent.class)
    public void onCommander(BilibiliCommanderEvent event) {
        increment(event, BilibiliLiveMetric.COMMANDER_COUNT, 1);
        increment(event, BilibiliLiveMetric.GUARD_VALUE, Optional.ofNullable(event.getValue()).orElse(0.0));
        scoreUser(event, BilibiliLiveMetric.GUARD_USERS, event.getSender(), 1);
        recordGuard(event, 2);
    }

    /**
     * 总督
     */
    @EventListener(BilibiliGovernorEvent.class)
    public void onGovernor(BilibiliGovernorEvent event) {
        increment(event, BilibiliLiveMetric.GOVERNOR_COUNT, 1);
        increment(event, BilibiliLiveMetric.GUARD_VALUE, Optional.ofNullable(event.getValue()).orElse(0.0));
        scoreUser(event, BilibiliLiveMetric.GUARD_USERS, event.getSender(), 1);
        recordGuard(event, 1);
    }

    /**
     * 关注
     */
    @EventListener(BilibiliFollowEvent.class)
    public void onFollow(BilibiliFollowEvent event) {
        increment(event, BilibiliLiveMetric.FOLLOW_COUNT, 1);
        recordEvent(event, "follow", eventFields(event.getSender()));
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
     * 看过人数更新
     * <p>
     * 平台每分钟下发数次，每次给的是当前累计值。<b>只能取最大，不能累加</b>——
     * 一个真实值 8000 的读数在一分钟内下发 5 次，累加就成了 40000，
     * 而这个数看起来完全合理，不会有任何地方报错。
     */
    @EventListener(BilibiliWatchedUpdateEvent.class)
    public void onWatchedUpdate(BilibiliWatchedUpdateEvent event) {
        Integer count = event.getCount();
        if (count != null) {
            max(event, BilibiliLiveMetric.WATCHED_COUNT, count);
            maxSeries(event, BilibiliLiveMetric.WATCHED_COUNT, count);
        }
    }

    /**
     * 高能用户数与在线人数更新
     * <p>
     * {@code online_count} 是高能榜头部的登录观众数；缺这个字段时回落到 {@code count}。
     * 两个字段同时在时以 {@code online_count} 为准，不取两者较大。
     */
    @EventListener(BilibiliOnlineRankCountUpdateEvent.class)
    public void onOnlineRankCountUpdate(BilibiliOnlineRankCountUpdateEvent event) {
        Integer count = event.getCount();
        if (count != null) {
            max(event, BilibiliLiveMetric.ONLINE_RANK_COUNT, count);
            maxSeries(event, BilibiliLiveMetric.ONLINE_RANK_COUNT, count);
        }
        Integer online = event.getOnlineCount() != null ? event.getOnlineCount() : count;
        if (online != null) {
            max(event, BilibiliLiveMetric.ONLINE_COUNT, online);
            maxSeries(event, BilibiliLiveMetric.ONLINE_COUNT, online);
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

    /**
     * 把一次瞬时读数计入曲线，同一分钟内取最大
     * <p>
     * 与 {@link #increment} 里那段曲线写入的区别在这里：那边是累加增量，
     * 而这些指标每次给的都是「当前是多少」，累加会得出天文数字。
     */
    private void maxSeries(StarBotBaseLiveEvent event, String metric, double value) {
        if (event.getSource() == null || event.getSource().getUid() == null) {
            return;
        }
        liveDataService.maxLiveSeries(event.getPlatform(), event.getSource().getUid(),
                metric, event.getTimestamp(), value);
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
     * 留下这一条的原文
     * <p>
     * <b>词频表答不出「那一句是什么」</b>：它存的是分词后的结果，
     * 从「好听×37」拼不回任何一句原话，而这一步是不可逆的。原文留档补上的正是这一段。
     * <p>
     * 以<b>开播时刻</b>归入某一场，与场次归档、报告图缓存三者同一个键。
     * 取不到开播时刻就不留：那种情况下这一场<b>本来也不会被归档</b>
     * （见下播事件监听器），留下的原文永远没有一场直播认领得了。
     * <p>
     * 空文本不留：一条没有内容的记录占着行数，却答不出任何问题。
     */
    private void recordDanmu(StarBotBaseLiveEvent event, UserInfo sender, String text, DanmuRecord.Type type) {
        if (event.getSource() == null || event.getSource().getUid() == null || StringUtil.isBlank(text)) {
            return;
        }

        Long uid = event.getSource().getUid();
        Optional<Long> start = liveDataService.getLiveStartTime(event.getPlatform(), uid);
        if (start.isEmpty()) {
            return;
        }

        details.appendDanmu(event.getPlatform(), uid, start.get(), new DanmuRecord(
                event.getTimestamp(),
                sender == null ? null : sender.getUid(),
                sender == null ? null : sender.getUname(),
                text,
                type));
    }

    /** 与 {@link #recordDanmu} 同守卫：无开播时刻即不留。 */
    private void recordEvent(StarBotBaseLiveEvent event, String type, Map<String, Object> fields) {
        if (event.getSource() == null || event.getSource().getUid() == null) {
            return;
        }
        Long uid = event.getSource().getUid();
        liveDataService.getLiveStartTime(event.getPlatform(), uid).ifPresent(start ->
                details.appendEvent(event.getPlatform(), uid, start, event.getTimestamp(), type, fields));
    }

    private void recordGuard(MembershipEvent event, int level) {
        recordEvent(event, "guard", eventFields(event.getSender(),
                "lv", level, "n", event.getCount(), "u", event.getUnit(),
                "days", event.getCompanionDays(), "val", event.getValue(),
                "pay", Optional.ofNullable(event.getCharged()).orElse(event.getValue())));
    }

    private static Map<String, Object> eventFields(UserInfo sender, Object... kv) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("uid", sender == null ? null : sender.getUid());
        fields.put("un", sender == null ? null : sender.getUname());
        for (int i = 0; i < kv.length; i += 2) {
            fields.put((String) kv[i], kv[i + 1]);
        }
        return fields;
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
