package org.frostnova.nova.report.command;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.command.BilibiliStreamerChoice;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.model.BilibiliDataScope;
import org.frostnova.nova.bilibili.model.BilibiliLiveMetric;
import org.frostnova.nova.report.painter.BilibiliDataQueryPainter;
import org.frostnova.nova.core.command.CommandContext;
import org.frostnova.nova.core.command.CommandReply;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.model.UserScore;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.RevenueVisibilityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleFunction;

/**
 * 「数据排行榜」命令
 * <p>
 * 下播报告里的排行榜只出前几名且随报告一次性发出，这里可以随时查、能挑榜单。
 * 一次回一张长图：默认列前 50 名，装不下时截到高度上限，末尾写明「其余 N 名未列出」。
 * 不带「总」出本场，带「总」出历次累计；旧名「总数据排行榜」等同于带「总」。
 */
@Slf4j
@NovaComponent
public class BilibiliRankingCommand extends BilibiliScopedDataCommand {
    private final NovaBilibiliProperties properties;

    @Autowired
    public BilibiliRankingCommand(AbstractDataSource dataSource, BilibiliStreamerChoice choice,
                                  LiveDataService liveDataService,
                                  BilibiliDataQueryPainter painter, RevenueVisibilityService revenueVisibility,
                                  NovaBilibiliProperties properties) {
        super(dataSource, choice, liveDataService, painter, revenueVisibility);
        this.properties = properties;
    }

    @Override
    public String name() {
        return "数据排行榜";
    }

    @Override
    public List<String> aliases() {
        return List.of("总数据排行榜");
    }

    @Override
    public String description() {
        return "查各榜单排行，带「总」出历次累计";
    }

    @Override
    public String usage() {
        return "<榜单> [总] [主播 uid 或昵称]";
    }

    @Override
    protected String liveName() {
        return "数据排行榜";
    }

    @Override
    protected String totalName() {
        return "总数据排行榜";
    }

    @Override
    public CommandReply execute(CommandContext context) {
        CommandReply unavailable = checkScopeAvailable(context);
        if (unavailable != null) {
            return unavailable;
        }

        boolean revenue = revenueVisible(context);
        String example = revenue ? "礼物" : "弹幕";

        // 「总」是范围开关，位置随人写：先整字摘掉，剩下的按「榜单、主播」老规矩认。
        // 不摘的话它会被当成主播名，或把榜单名挤到第二位认不出来
        List<String> args = new ArrayList<>(context.getArgs());
        args.remove(TOTAL_FLAG);

        Board board = Board.match(args.isEmpty() ? null : args.get(0));
        if (board == null) {
            // 示例是给人照着发的，照着发要真查得到问的那一半：问累计时若写成
            // 「数据排行榜 …」，照着发查到的是本场，答非所问
            String asked = wantsTotal(context)
                    ? name() + " " + example + " " + TOTAL_FLAG
                    : name() + " " + example;
            return CommandReply.of("请指明要看哪张榜：" + Board.names(revenue)
                    + "\n例如：" + asked);
        }

        if (board.money && !revenue) {
            // 说清是「本会话不展示」而不是「没这张榜」，否则只会被反复重试
            return CommandReply.of("本会话不展示金额相关的榜单，可查：" + Board.names(false));
        }

        String streamerKeyword = null;
        for (int i = 1; i < args.size(); i++) {
            String arg = args.get(i);
            // 三位以内的纯数字照旧收下但不用它：它原来是页码，一次一张长图后没有页了。
            // 认出来就丢掉，既不当页码也不拿去当主播名查；更长的当 uid（uid 都是八位以上）
            if (arg.length() <= 3 && arg.chars().allMatch(Character::isDigit)) {
                continue;
            }
            streamerKeyword = arg;
        }

        Resolved resolved = resolve(context, streamerKeyword);
        if (resolved.failed()) {
            return resolved.error();
        }

        PushUser streamer = resolved.streamer();
        String platform = BilibiliPlatform.BILIBILI.id();
        BilibiliDataScope scope = scope(context);

        int total = scope.userCount(liveDataService, platform, streamer.getUid(), board.metric);
        if (total == 0) {
            // 匿名模式下弹幕发送者 uid 全是 0、不计人数：有条数却认不出人时说清缘由，
            // 别让人以为本场没有弹幕。别的榜不走这一句
            if (board == Board.DANMU
                    && Math.round(scope.metric(liveDataService, platform, streamer.getUid(),
                            BilibiliLiveMetric.DANMU_COUNT)) > 0) {
                return CommandReply.of(nameOf(streamer) + "的直播间" + scope.getLabel()
                        + board.title + "认不出发送者，没有排行");
            }
            return CommandReply.of(nameOf(streamer) + "的直播间还没有" + scope.getLabel() + board.title + "数据");
        }

        // 一次出一张长图：先按可配的「最多列出名次」取，再按整图高度上限截到装得下的那几名。
        // 取到要列的名次就停——JSON 实现本就要全量排序，Redis 的 zset 取前 N 名也很廉价
        int topN = Math.max(1, properties.getRanking().getTopN());
        List<UserScore> ranking = scope.ranking(liveDataService, platform, streamer.getUid(),
                board.metric, Math.min(total, topN));

        BilibiliDataQueryPainter.Header header = new BilibiliDataQueryPainter.Header(
                board.title + "排行榜",
                scope.getLabel() + "数据 · " + nameOf(streamer) + "的直播间",
                streamer.getFace());

        int heightLimit = properties.getRanking().getHeightLimit();
        // 生效的上限是「配置值」与「一张最小的图（表头 + 一行名次 + 脚注 + 署名）」里高的那个：
        // 比最小可出图高度还矮的上限等于要求出一张空图，宁可抬高它、并写明抬高了多少
        int minimum = painter.measureRankingHeight(header, 1, footnote(1, total, board, scope));
        int effectiveLimit = Math.max(heightLimit, minimum);
        if (heightLimit < minimum) {
            log.warn("整图高度上限填的是 {}，比一张最小的图（{} 像素）还矮，按最小高度 {} 出图",
                    heightLimit, minimum, minimum);
        }
        int shown = ranking.size();
        while (shown > 1
                && painter.measureRankingHeight(header, shown, footnote(shown, total, board, scope)) > effectiveLimit) {
            shown--;
        }
        List<UserScore> rows = ranking.subList(0, shown);

        // 理由同「直播间数据」：没点名而由机器人猜出来的那一次，图前面要有一行写清用的是谁
        return withNotice(resolved, painter.paintRanking(header, rows, 1, board.scoreText,
                        footnote(shown, total, board, scope))
                .map(CommandReply::image)
                .orElseGet(this::paintFailed));
    }

    /**
     * 脚注：第一行写「前 N 名 · 共 n 人」，没列全的再补一行「其余 N 名未列出」，口径说明单独成行
     */
    private String footnote(int shown, int total, Board board, BilibiliDataScope scope) {
        StringBuilder text = new StringBuilder("前 " + shown + " 名 · 共 " + total + " 人");
        if (shown < total) {
            text.append("\n其余 ").append(total - shown).append(" 名未列出");
        }
        if (board.note != null) {
            // 口径说明单独一行，别和名次数挤在一起：挤在一起的那行会长到折行，读起来像名次数的一部分
            text.append("\n").append(board.note);
            // 累计榜还要多说一句：这个数里含口径变更前后两段
            if (scope.isTotal()) {
                text.append("\n").append(BilibiliLiveMetric.GIFT_RANKING_SCOPE_CHANGE_NOTE);
            }
        }
        return text.toString();
    }

    private static String profitLabel(double score) {
        if (score > 0) return "+¥" + yuan(score);
        if (score < 0) return "-¥" + yuan(-score);
        return "¥" + yuan(0);
    }

    /**
     * 可查的榜单
     */
    private enum Board {
        DANMU("弹幕", BilibiliLiveMetric.DANMU_USERS, false, score -> Math.round(score) + " 条", null),
        GIFT("礼物", BilibiliLiveMetric.GIFT_USERS, true, score -> "¥" + yuan(score),
                BilibiliLiveMetric.GIFT_RANKING_NOTE),
        SUPER_CHAT("醒目留言", BilibiliLiveMetric.SUPER_CHAT_USERS, true, score -> "¥" + yuan(score),
                null, "SC", "sc"),
        BOX("盲盒", BilibiliLiveMetric.BOX_USERS, false, score -> Math.round(score) + " 个", null),
        BOX_PROFIT("盲盒盈亏", BilibiliLiveMetric.BOX_PROFIT_USERS, true,
                BilibiliRankingCommand::profitLabel, null, "盈亏"),
        GUARD("大航海", BilibiliLiveMetric.GUARD_USERS, false, score -> Math.round(score) + " 次", null, "舰长");

        private final String title;

        private final String metric;

        /**
         * 榜单的口径说明，没有歧义的榜为 null
         * <p>
         * 只有礼物榜需要：它的得分是「主播到手价值」而不是「观众花了多少」，
         * 而这张榜的读者最容易把两者当成一回事。
         */
        private final String note;

        /**
         * 榜单是否以金额排名
         * <p>
         * 这三张榜的每一行都是「某人花了多少钱」，不展示金额时整张不出——
         * 只抹掉右侧的数字仍然是在公开排消费。
         */
        private final boolean money;

        private final DoubleFunction<String> scoreText;

        private final List<String> aliases;

        /**
         * 口径说明写成必填位置参数而不是可变参数的一部分：
         * 与别名共用可变参数时，「礼物」那条的说明会被当成一个别名收下，而编译器不会有意见
         */
        Board(String title, String metric, boolean money, DoubleFunction<String> scoreText,
              String note, String... aliases) {
            this.title = title;
            this.metric = metric;
            this.money = money;
            this.scoreText = scoreText;
            this.note = note;
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
