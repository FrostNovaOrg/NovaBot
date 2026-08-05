package com.starlwr.bot.bilibili.command;

import com.starlwr.bot.bilibili.model.BilibiliDataScope;
import com.starlwr.bot.bilibili.model.BilibiliLiveMetric;
import com.starlwr.bot.bilibili.painter.BilibiliDataQueryPainter;
import com.starlwr.bot.bilibili.util.DurationFormatUtil;
import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.CommandReply;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.service.LiveDataService;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 「直播间数据」类命令
 * <p>
 * 查询直播间的整体数据。与「直播报告」的分工：报告是一张完整的大图（封面、排行榜、词云），
 * 这里只出一屏卡片，用于随手一问；且累计范围下报告本就无从谈起——
 * 封面与词云都属于某一场直播。
 */
public abstract class BilibiliRoomDataCommand extends BilibiliScopedDataCommand {
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

    protected BilibiliRoomDataCommand(AbstractDataSource dataSource, LiveDataService liveDataService,
                                      BilibiliDataQueryPainter painter) {
        super(dataSource, liveDataService, painter);
    }

    @Override
    public String usage() {
        return "[主播 uid 或昵称]";
    }

    @Override
    public CommandReply execute(CommandContext context) {
        CommandReply unavailable = checkScopeAvailable();
        if (unavailable != null) {
            return unavailable;
        }

        Resolved resolved = resolve(context, context.arg(0));
        if (resolved.failed()) {
            return resolved.error();
        }

        PushUser streamer = resolved.streamer();
        String platform = LivePlatform.BILIBILI.getName();
        Long uid = streamer.getUid();
        BilibiliDataScope scope = scope();

        List<BilibiliDataQueryPainter.DataCard> cards = buildCards(scope, platform, uid);
        if (cards.isEmpty()) {
            return CommandReply.of(nameOf(streamer) + "的直播间还没有" + scope.getLabel() + "数据");
        }

        BilibiliDataQueryPainter.Header header = new BilibiliDataQueryPainter.Header(
                nameOf(streamer), subtitle(scope, platform, uid), streamer.getFace());

        return painter.paintCards(header, cards, footnote(scope, platform, uid))
                .map(CommandReply::image)
                .orElseGet(this::paintFailed);
    }

    /**
     * 排出直播间的数据卡片，为零的项自动省略
     */
    private List<BilibiliDataQueryPainter.DataCard> buildCards(BilibiliDataScope scope, String platform, Long uid) {
        long danmu = count(scope, platform, uid, BilibiliLiveMetric.DANMU_COUNT);
        int danmuUsers = scope.userCount(liveDataService, platform, uid, BilibiliLiveMetric.DANMU_USERS);
        double giftValue = scope.metric(liveDataService, platform, uid, BilibiliLiveMetric.GIFT_VALUE);
        int giftUsers = scope.userCount(liveDataService, platform, uid, BilibiliLiveMetric.GIFT_USERS);
        long superChat = count(scope, platform, uid, BilibiliLiveMetric.SUPER_CHAT_COUNT);
        double superChatValue = scope.metric(liveDataService, platform, uid, BilibiliLiveMetric.SUPER_CHAT_VALUE);
        long guards = count(scope, platform, uid, BilibiliLiveMetric.CAPTAIN_COUNT)
                + count(scope, platform, uid, BilibiliLiveMetric.COMMANDER_COUNT)
                + count(scope, platform, uid, BilibiliLiveMetric.GOVERNOR_COUNT);
        double guardValue = scope.metric(liveDataService, platform, uid, BilibiliLiveMetric.GUARD_VALUE);
        long box = count(scope, platform, uid, BilibiliLiveMetric.BOX_COUNT);
        double boxProfit = scope.metric(liveDataService, platform, uid, BilibiliLiveMetric.BOX_PROFIT);
        long freeGift = count(scope, platform, uid, BilibiliLiveMetric.FREE_GIFT_COUNT);
        long follow = count(scope, platform, uid, BilibiliLiveMetric.FOLLOW_COUNT);
        int enterUsers = scope.userCount(liveDataService, platform, uid, BilibiliLiveMetric.ENTER_USERS);
        long likeTotal = count(scope, platform, uid, BilibiliLiveMetric.LIKE_TOTAL);
        long share = count(scope, platform, uid, BilibiliLiveMetric.SHARE_COUNT);

        List<BilibiliDataQueryPainter.DataCard> cards = new ArrayList<>();
        if (danmu > 0) {
            cards.add(card(String.valueOf(danmu), "弹幕 · " + danmuUsers + " 人参与"));
        }
        if (giftValue > 0) {
            cards.add(card("¥" + yuan(giftValue), "礼物 · " + giftUsers + " 人送出"));
        }
        if (superChat > 0) {
            cards.add(card(superChat + " 条", "醒目留言 · ¥" + yuan(superChatValue)));
        }
        if (guards > 0) {
            cards.add(card("+" + guards, "大航海 · ¥" + yuan(guardValue)));
        }
        if (box > 0) {
            cards.add(card(box + " 个", "盲盒 · " + (boxProfit >= 0 ? "盈利" : "亏损") + " ¥" + yuan(Math.abs(boxProfit))));
        }
        if (likeTotal > 0) {
            cards.add(card(String.valueOf(likeTotal), "点赞"));
        }
        if (enterUsers > 0) {
            cards.add(card(enterUsers + " 人", "进入直播间"));
        }
        if (follow > 0) {
            cards.add(card("+" + follow, "新增关注"));
        }
        if (freeGift > 0) {
            cards.add(card(freeGift + " 个", "免费礼物"));
        }
        if (share > 0) {
            cards.add(card(share + " 次", "分享"));
        }
        return cards;
    }

    private BilibiliDataQueryPainter.DataCard card(String value, String label) {
        return new BilibiliDataQueryPainter.DataCard(value, label);
    }

    private long count(BilibiliDataScope scope, String platform, Long uid, String metric) {
        return Math.round(scope.metric(liveDataService, platform, uid, metric));
    }

    /**
     * 副标题：本场标出起止时间，累计只说明范围
     */
    private String subtitle(BilibiliDataScope scope, String platform, Long uid) {
        if (scope.isTotal()) {
            return "累计数据 · 历次直播合计";
        }

        Optional<Long> start = liveDataService.getLiveStartTime(platform, uid);
        if (start.isEmpty()) {
            return "本场数据";
        }
        boolean living = liveDataService.getLiveStatus(platform, uid).orElse(false);
        return "本场数据 · " + TIME_FORMATTER.format(Instant.ofEpochMilli(start.get())) + (living ? " 起 · 直播中" : " 起");
    }

    /**
     * 脚注：本场补一句直播时长
     */
    private String footnote(BilibiliDataScope scope, String platform, Long uid) {
        if (scope.isTotal()) {
            return null;
        }

        Optional<Long> start = liveDataService.getLiveStartTime(platform, uid);
        if (start.isEmpty()) {
            return null;
        }

        boolean living = liveDataService.getLiveStatus(platform, uid).orElse(false);
        Optional<Long> end = living
                ? Optional.of(System.currentTimeMillis())
                : liveDataService.getLiveEndTime(platform, uid).filter(time -> time >= start.get());
        return end.map(time -> "直播时长 " + DurationFormatUtil.format((time - start.get()) / 1000)).orElse(null);
    }
}
