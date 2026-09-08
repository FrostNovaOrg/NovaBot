package com.starlwr.bot.report.command;

import com.starlwr.bot.bilibili.command.BilibiliStreamerChoice;
import com.starlwr.bot.bilibili.model.BilibiliDataScope;
import com.starlwr.bot.report.painter.BilibiliDataQueryPainter;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.RevenueVisibilityService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 「总数据排行榜」命令：查询跨场次累计的排行榜
 */
@StarBotComponent
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
