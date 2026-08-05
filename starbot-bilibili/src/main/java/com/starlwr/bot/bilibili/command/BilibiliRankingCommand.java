package com.starlwr.bot.bilibili.command;

import com.starlwr.bot.bilibili.model.BilibiliDataScope;
import com.starlwr.bot.bilibili.model.BilibiliLiveMetric;
import com.starlwr.bot.bilibili.painter.BilibiliDataQueryPainter;
import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.CommandReply;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.model.UserScore;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.RevenueVisibilityService;

import java.util.List;
import java.util.function.DoubleFunction;

/**
 * 「数据排行榜」类命令
 * <p>
 * 下播报告里的排行榜只出前几名且随报告一次性发出，这里可以随时查、能翻页、能挑榜单。
 */
public abstract class BilibiliRankingCommand extends BilibiliScopedDataCommand {
    /**
     * 每页人数。一屏能看完，也不至于翻页翻到手酸
     */
    private static final int PAGE_SIZE = 10;

    /**
     * 页码上限。榜单再长也没人会翻到第一百页，设个上限防止有人拿超大页码刷图
     */
    private static final int MAX_PAGE = 50;

    protected BilibiliRankingCommand(AbstractDataSource dataSource, LiveDataService liveDataService,
                                     BilibiliDataQueryPainter painter, RevenueVisibilityService revenueVisibility) {
        super(dataSource, liveDataService, painter, revenueVisibility);
    }

    @Override
    public String usage() {
        return "<榜单> [页码] [主播 uid 或昵称]";
    }

    @Override
    public CommandReply execute(CommandContext context) {
        CommandReply unavailable = checkScopeAvailable();
        if (unavailable != null) {
            return unavailable;
        }

        boolean revenue = revenueVisible(context);
        String example = revenue ? "礼物" : "弹幕";

        Board board = Board.match(context.arg(0));
        if (board == null) {
            return CommandReply.of("请指明要看哪张榜：" + Board.names(revenue)
                    + "\n例如：" + name() + " " + example
                    + "\n翻页：" + name() + " " + example + " 2");
        }

        if (board.money && !revenue) {
            // 说清是「本会话不展示」而不是「没这张榜」，否则只会被反复重试
            return CommandReply.of("本会话不展示金额相关的榜单，可查：" + Board.names(false));
        }

        int page = 1;
        String streamerKeyword = null;
        for (int i = 1; i < context.getArgs().size(); i++) {
            String arg = context.getArgs().get(i);
            // 三位以内的纯数字当页码，更长的当 uid：uid 都是八位以上，
            // 而没人会把榜单翻到第 1000 页
            if (arg.length() <= 3 && arg.chars().allMatch(Character::isDigit)) {
                page = Integer.parseInt(arg);
            } else {
                streamerKeyword = arg;
            }
        }

        if (page < 1 || page > MAX_PAGE) {
            return CommandReply.of("页码需在 1 ~ " + MAX_PAGE + " 之间");
        }

        Resolved resolved = resolve(context, streamerKeyword);
        if (resolved.failed()) {
            return resolved.error();
        }

        PushUser streamer = resolved.streamer();
        String platform = LivePlatform.BILIBILI.getName();
        BilibiliDataScope scope = scope();

        int total = scope.userCount(liveDataService, platform, streamer.getUid(), board.metric);
        if (total == 0) {
            return CommandReply.of(nameOf(streamer) + "的直播间还没有" + scope.getLabel() + board.title + "数据");
        }

        int offset = (page - 1) * PAGE_SIZE;
        if (offset >= total) {
            int pages = (total + PAGE_SIZE - 1) / PAGE_SIZE;
            return CommandReply.of(board.title + "榜" + scope.getLabel() + "共 " + total + " 人、"
                    + pages + " 页，没有第 " + page + " 页");
        }

        // 取到本页末尾后截取：JSON 实现本就要全量排序，Redis 的 zset 取前 N 名也很廉价，
        // 不值得为翻页在接口上再开一个带偏移量的方法
        List<UserScore> ranking = scope.ranking(liveDataService, platform, streamer.getUid(),
                board.metric, offset + PAGE_SIZE);
        if (ranking.size() <= offset) {
            return CommandReply.of("没有第 " + page + " 页");
        }
        List<UserScore> rows = ranking.subList(offset, ranking.size());

        int pages = (total + PAGE_SIZE - 1) / PAGE_SIZE;
        BilibiliDataQueryPainter.Header header = new BilibiliDataQueryPainter.Header(
                board.title + "排行榜",
                scope.getLabel() + "数据 · " + nameOf(streamer) + "的直播间",
                streamer.getFace());
        String footnote = "第 " + page + " / " + pages + " 页 · 共 " + total + " 人";

        return painter.paintRanking(header, rows, offset + 1, board.scoreText, footnote)
                .map(CommandReply::image)
                .orElseGet(this::paintFailed);
    }

    /**
     * 可查的榜单
     */
    private enum Board {
        DANMU("弹幕", BilibiliLiveMetric.DANMU_USERS, false, score -> Math.round(score) + " 条"),
        GIFT("礼物", BilibiliLiveMetric.GIFT_USERS, true, score -> "¥" + yuan(score)),
        SUPER_CHAT("醒目留言", BilibiliLiveMetric.SUPER_CHAT_USERS, true, score -> "¥" + yuan(score), "SC", "sc"),
        BOX("盲盒", BilibiliLiveMetric.BOX_USERS, false, score -> Math.round(score) + " 个"),
        BOX_PROFIT("盲盒盈亏", BilibiliLiveMetric.BOX_PROFIT_USERS, true,
                score -> (score >= 0 ? "+¥" : "-¥") + yuan(Math.abs(score)), "盈亏"),
        GUARD("大航海", BilibiliLiveMetric.GUARD_USERS, false, score -> Math.round(score) + " 次", "舰长");

        private final String title;

        private final String metric;

        /**
         * 榜单是否以金额排名
         * <p>
         * 这三张榜的每一行都是「某人花了多少钱」，不展示金额时整张不出——
         * 只抹掉右侧的数字仍然是在公开排消费。
         */
        private final boolean money;

        private final DoubleFunction<String> scoreText;

        private final List<String> aliases;

        Board(String title, String metric, boolean money, DoubleFunction<String> scoreText, String... aliases) {
            this.title = title;
            this.metric = metric;
            this.money = money;
            this.scoreText = scoreText;
            this.aliases = List.of(aliases);
        }

        /**
         * 按名称或别名匹配榜单
         */
        static Board match(String keyword) {
            if (keyword == null || keyword.isBlank()) {
                return null;
            }
            for (Board board : values()) {
                if (board.title.equals(keyword) || board.aliases.contains(keyword)) {
                    return board;
                }
            }
            return null;
        }

        /**
         * 可查的榜单名，用于提示
         * <p>
         * 按可见性过滤而不是全部列出：列一张查了必被拒的榜，等于请人白跑一趟。
         */
        static String names(boolean revenue) {
            StringBuilder text = new StringBuilder();
            for (Board board : values()) {
                if (board.money && !revenue) {
                    continue;
                }
                if (!text.isEmpty()) {
                    text.append("、");
                }
                text.append(board.title);
            }
            return text.toString();
        }
    }
}
