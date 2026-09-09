package org.frostnova.nova.report.command;

import org.frostnova.nova.bilibili.command.BilibiliStreamerChoice;
import org.frostnova.nova.bilibili.command.BilibiliStreamerCommand;
import org.frostnova.nova.bilibili.model.BilibiliDataScope;
import org.frostnova.nova.report.painter.BilibiliDataQueryPainter;
import org.frostnova.nova.core.command.CommandContext;
import org.frostnova.nova.core.command.CommandReply;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.RevenueVisibilityService;
import lombok.extern.slf4j.Slf4j;

/**
 * 分范围的数据查询命令
 * <p>
 * 每个查询都有「本场」与「累计」两副面孔，除了取数范围之外逻辑完全一致。
 * 范围由 {@link #scope()} 声明，取数交给 {@link BilibiliDataScope}，
 * 子类只关心排哪些数据、怎么排。
 */
@Slf4j
public abstract class BilibiliScopedDataCommand extends BilibiliStreamerCommand {
    protected final LiveDataService liveDataService;

    protected final BilibiliDataQueryPainter painter;

    private final RevenueVisibilityService revenueVisibility;

    protected BilibiliScopedDataCommand(AbstractDataSource dataSource, BilibiliStreamerChoice choice,
                                        LiveDataService liveDataService,
                                        BilibiliDataQueryPainter painter, RevenueVisibilityService revenueVisibility) {
        super(dataSource, choice);
        this.liveDataService = liveDataService;
        this.painter = painter;
        this.revenueVisibility = revenueVisibility;
    }

    /**
     * 本会话能否看到金额
     * <p>
     * 数据查询命令谁都能在群里打，而它们回的图里带着礼物、醒目留言、大航海的具体金额。
     * 下播报告的版式配置管不到这里——命令不属于任何推送目标，读不到那份配置，
     * 所以「给谁看」这件事必须由会话本身回答。
     * <p>
     * 各命令拿到结论后自行决定怎么降级：卡片改用人数、条数等非金额表述，
     * 而整张榜都是「谁花了多少钱」的，直接不出。
     */
    protected boolean revenueVisible(CommandContext context) {
        return revenueVisibility.isVisible(context.getPlatform(), context.getType(), context.getNum());
    }

    /**
     * 本命令查询的数据范围
     */
    protected abstract BilibiliDataScope scope();

    @Override
    public boolean groupOnly() {
        // 查数据只关乎发问的人自己，在已配推送的好友会话里一样该答得上来。
        // 「@ 谁」那几条不同：@ 在私聊里没有对象
        return false;
    }

    @Override
    public boolean available() {
        // 累计要靠外部存储。没配的机器上这条命令不进菜单，但仍认得出来，好回一句为什么
        return !scope().isTotal() || liveDataService.supportsTotalData();
    }

    /**
     * 累计范围是否可用
     * <p>
     * 未配置外部存储时累计数据一律为 0。直接把 0 画出来会让人以为数据丢了，
     * 因此这里明确回一句「没开这个能力」，并顺手指一条现在就能用的路。
     * <p>
     * 还要在日志里记一行：菜单已经不列这两条了，仍然发过来说明有人照着旧习惯或旧文档在用，
     * 而这件事在机器的主人那边<b>没有任何别的痕迹</b>——群里的对话他看不到。
     * @param context 执行上下文，用于日志
     * @return 不可用时的说明，可用时为 null
     */
    protected CommandReply checkScopeAvailable(CommandContext context) {
        if (scope().isTotal() && !liveDataService.supportsTotalData()) {
            log.info("会话 {} 请求了累计数据命令 {}, 但本机未配置累计存储, 已回绝", context.getNum(), name());
            return CommandReply.of("本机没开累计数据，只能查本场。发「直播间数据」看本场。");
        }
        return null;
    }

    /**
     * 绘制失败时的统一回复
     */
    protected CommandReply paintFailed() {
        return CommandReply.of("图片绘制失败, 请查看日志");
    }

    /**
     * 金额格式化：保留一位小数，整数金额省略小数位
     */
    protected static String yuan(double value) {
        long rounded = Math.round(value * 10);
        return rounded % 10 == 0 ? String.valueOf(rounded / 10) : String.valueOf(rounded / 10.0);
    }

    /**
     * 名次的展示文案，未上榜时为空串
     */
    protected String rankText(int rank) {
        return rank > 0 ? " · 第 " + rank + " 名" : "";
    }

    @Override
    public String category() {
        return "数据查询";
    }
}
