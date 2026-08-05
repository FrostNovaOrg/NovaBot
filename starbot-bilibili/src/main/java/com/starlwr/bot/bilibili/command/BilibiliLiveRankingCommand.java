package com.starlwr.bot.bilibili.command;

import com.starlwr.bot.bilibili.model.BilibiliDataScope;
import com.starlwr.bot.bilibili.painter.BilibiliDataQueryPainter;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.RevenueVisibilityService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 「数据排行榜」命令：查询本场直播的排行榜
 */
@StarBotComponent
public class BilibiliLiveRankingCommand extends BilibiliRankingCommand {
    @Autowired
    public BilibiliLiveRankingCommand(AbstractDataSource dataSource, LiveDataService liveDataService,
                                      BilibiliDataQueryPainter painter, RevenueVisibilityService revenueVisibility) {
        super(dataSource, liveDataService, painter, revenueVisibility);
    }

    @Override
    public String name() {
        return "数据排行榜";
    }

    @Override
    public String description() {
        return "查询本场直播的弹幕、礼物等排行榜，可翻页";
    }

    @Override
    protected BilibiliDataScope scope() {
        return BilibiliDataScope.LIVE;
    }
}
