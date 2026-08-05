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
                                     BilibiliDataQueryPainter painter) {
        super(dataSource, liveDataService, painter);
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

        Board board = Board.match(context.arg(0));
        if (board == null) {
            return CommandReply.of("请指明要看哪张榜：" + Board.names()
                    + "\n例如：" + name() + " 礼物"
                    + "\n翻页：" + name() + " 礼物 2");
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
        DANMU("弹幕", BilibiliLiveMetric.DANMU_USERS, score -> Math.round(score) + " 条"),
        GIFT("礼物", BilibiliLiveMetric.GIFT_USERS, score -> "¥" + yuan(score)),
        SUPER_CHAT("醒目留言", BilibiliLiveMetric.SUPER_CHAT_USERS, score -> "¥" + yuan(score), "SC", "sc"),
        BOX("盲盒", BilibiliLiveMetric.BOX_USERS, score -> Math.round(score) + " 个"),
        BOX_PROFIT("盲盒盈亏", BilibiliLiveMetric.BOX_PROFIT_USERS,
                score -> (score >= 0 ? "+¥" : "-¥") + yuan(Math.abs(score)), "盈亏"),
        GUARD("大航海", BilibiliLiveMetric.GUARD_USERS, score -> Math.round(score) + " 次", "舰长");

        private final String title;

        private final String metric;

        private final DoubleFunction<String> scoreText;

        private final List<String> aliases;

        Board(String title, String metric, DoubleFunction<String> scoreText, String... aliases) {
            this.title = title;
            this.metric = metric;
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
         * 全部榜单名，用于提示
         */
        static String names() {
            StringBuilder text = new StringBuilder();
            for (Board board : values()) {
                if (!text.isEmpty()) {
                    text.append("、");
                }
                text.append(board.title);
            }
            return text.toString();
        }
    }
}
