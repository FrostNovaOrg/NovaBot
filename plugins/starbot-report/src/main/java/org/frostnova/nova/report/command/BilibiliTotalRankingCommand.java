package org.frostnova.nova.report.command;

import org.frostnova.nova.bilibili.command.BilibiliStreamerChoice;
import org.frostnova.nova.bilibili.model.BilibiliDataScope;
import org.frostnova.nova.report.painter.BilibiliDataQueryPainter;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.RevenueVisibilityService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 「总数据排行榜」命令：查询跨场次累计的排行榜
 */
@NovaComponent
public class BilibiliTotalRankingCommand extends BilibiliRankingCommand {
    @Autowired
    public BilibiliTotalRankingCommand(AbstractDataSource dataSource, BilibiliStreamerChoice choice,
                                       LiveDataService liveDataService,
                                       BilibiliDataQueryPainter painter, RevenueVisibilityService revenueVisibility) {
        super(dataSource, choice, liveDataService, painter, revenueVisibility);
    }

    @Override
    public String name() {
        return "总数据排行榜";
    }

    @Override
    public String description() {
        return "查询该直播间历次直播累计的排行榜，可翻页";
    }

    @Override
    protected BilibiliDataScope scope() {
        return BilibiliDataScope.TOTAL;
    }
}
