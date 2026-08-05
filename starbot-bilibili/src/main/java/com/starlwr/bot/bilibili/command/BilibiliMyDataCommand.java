package com.starlwr.bot.bilibili.command;

import com.starlwr.bot.bilibili.model.BilibiliDataScope;
import com.starlwr.bot.bilibili.model.BilibiliLiveMetric;
import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.bilibili.painter.BilibiliDataQueryPainter;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.CommandReply;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.UserBindingService;
import com.starlwr.bot.core.util.StringUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 「我的数据」类命令
 * <p>
 * 查询绑定账号在某位主播直播间里的互动数据与名次。数据的来源是直播间弹幕流，
 * 也就是说这些互动本来就在直播间里公开可见，机器人只是替人算了个总账。
 */
@Slf4j
public abstract class BilibiliMyDataCommand extends BilibiliScopedDataCommand {
    private final UserBindingService bindings;

    private final BilibiliApiUtil api;

    protected BilibiliMyDataCommand(AbstractDataSource dataSource, LiveDataService liveDataService,
                                    BilibiliDataQueryPainter painter, UserBindingService bindings,
                                    BilibiliApiUtil api) {
        super(dataSource, liveDataService, painter);
        this.bindings = bindings;
        this.api = api;
    }

    @Override
    public String usage() {
        return "[主播 uid 或昵称]";
    }

    @Override
    public CommandReply execute(CommandContext context) {
        if (context.getSenderUid() == null) {
            return CommandReply.none();
        }

        CommandReply unavailable = checkScopeAvailable();
        if (unavailable != null) {
            return unavailable;
        }

        Long userUid = bindings.get(context.getPlatform(), LivePlatform.BILIBILI.getName(), context.getSenderUid())
                .orElse(null);
        if (userUid == null) {
            return CommandReply.of("你还没有绑定哔哩哔哩账号，请先发送「绑定 你的哔哩哔哩 uid」");
        }

        Resolved resolved = resolve(context, context.arg(0));
        if (resolved.failed()) {
            return resolved.error();
        }

        PushUser streamer = resolved.streamer();
        String platform = LivePlatform.BILIBILI.getName();
        BilibiliDataScope scope = scope();

        List<BilibiliDataQueryPainter.DataCard> cards = new ArrayList<>();
        addCard(cards, scope, platform, streamer.getUid(), BilibiliLiveMetric.DANMU_USERS, userUid,
                score -> Math.round(score) + " 条", "弹幕");
        addCard(cards, scope, platform, streamer.getUid(), BilibiliLiveMetric.GIFT_USERS, userUid,
                score -> "¥" + yuan(score), "礼物");
        addCard(cards, scope, platform, streamer.getUid(), BilibiliLiveMetric.SUPER_CHAT_USERS, userUid,
                score -> "¥" + yuan(score), "醒目留言");
        addCard(cards, scope, platform, streamer.getUid(), BilibiliLiveMetric.BOX_USERS, userUid,
                score -> Math.round(score) + " 个", "盲盒");
        addCard(cards, scope, platform, streamer.getUid(), BilibiliLiveMetric.BOX_PROFIT_USERS, userUid,
                score -> (score >= 0 ? "+¥" : "-¥") + yuan(Math.abs(score)), "盲盒盈亏");
        addCard(cards, scope, platform, streamer.getUid(), BilibiliLiveMetric.GUARD_USERS, userUid,
                score -> Math.round(score) + " 次", "大航海");

        if (cards.isEmpty()) {
            return CommandReply.of("你在" + nameOf(streamer) + "的直播间还没有" + scope.getLabel() + "互动数据");
        }

        Up self = selfInfo(userUid);
        BilibiliDataQueryPainter.Header header = new BilibiliDataQueryPainter.Header(
                StringUtil.isBlank(self.getUname()) ? String.valueOf(userUid) : self.getUname(),
                scope.getLabel() + "数据 · " + nameOf(streamer) + "的直播间",
                self.getFace());

        return painter.paintCards(header, cards, null).map(CommandReply::image).orElseGet(this::paintFailed);
    }

    /**
     * 追加一张卡片，得分为零时不追加
     * <p>
     * 零值卡片没有信息量，六项里只玩过一项的人不该看到五个「0」。
     * 盲盒盈亏恰好为零属于同样情况，一并省略。
     */
    private void addCard(List<BilibiliDataQueryPainter.DataCard> cards, BilibiliDataScope scope, String platform,
                         Long uid, String metric, Long userUid,
                         java.util.function.DoubleFunction<String> valueText, String label) {
        double score = scope.userMetric(liveDataService, platform, uid, metric, userUid);
        if (score == 0) {
            return;
        }

        int rank = scope.userRank(liveDataService, platform, uid, metric, userUid);
        cards.add(new BilibiliDataQueryPainter.DataCard(valueText.apply(score), label + rankText(rank)));
    }

    /**
     * 取查询者自己的昵称与头像，失败时退化为只显示 uid
     */
    private Up selfInfo(Long userUid) {
        try {
            return api.getUpInfoByUid(userUid);
        } catch (Exception e) {
            log.debug("获取 uid {} 的信息失败: {}", userUid, e.getMessage());
            return new Up(userUid, null, null);
        }
    }
}
