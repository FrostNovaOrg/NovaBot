package com.starlwr.bot.bilibili.command;

import com.starlwr.bot.bilibili.model.BilibiliDataScope;
import com.starlwr.bot.bilibili.painter.BilibiliDataQueryPainter;
import com.starlwr.bot.core.command.CommandReply;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.service.LiveDataService;

/**
 * 分范围的数据查询命令
 * <p>
 * 每个查询都有「本场」与「累计」两副面孔，除了取数范围之外逻辑完全一致。
 * 范围由 {@link #scope()} 声明，取数交给 {@link BilibiliDataScope}，
 * 子类只关心排哪些数据、怎么排。
 */
public abstract class BilibiliScopedDataCommand extends BilibiliStreamerCommand {
    protected final LiveDataService liveDataService;

    protected final BilibiliDataQueryPainter painter;

    protected BilibiliScopedDataCommand(AbstractDataSource dataSource, LiveDataService liveDataService,
                                        BilibiliDataQueryPainter painter) {
        super(dataSource);
        this.liveDataService = liveDataService;
        this.painter = painter;
    }

    /**
     * 本命令查询的数据范围
     */
    protected abstract BilibiliDataScope scope();

    /**
     * 累计范围是否可用
     * <p>
     * 未配置外部存储时累计数据一律为 0。直接把 0 画出来会让人以为数据丢了，
     * 因此这里明确回一句「没开这个能力」。
     * @return 不可用时的说明，可用时为 null
     */
    protected CommandReply checkScopeAvailable() {
        if (scope().isTotal() && !liveDataService.supportsTotalData()) {
            return CommandReply.of("累计数据需要配置 Redis 后才能查询，当前只有本场数据可用");
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
