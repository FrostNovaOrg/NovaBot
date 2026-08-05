package com.starlwr.bot.bilibili.command;

import com.starlwr.bot.bilibili.model.BilibiliDataScope;
import com.starlwr.bot.bilibili.painter.BilibiliDataQueryPainter;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.RevenueVisibilityService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 「直播间数据」命令：查询本场直播的整体数据
 */
@StarBotComponent
public class BilibiliRoomLiveDataCommand extends BilibiliRoomDataCommand {
    @Autowired
    public BilibiliRoomLiveDataCommand(AbstractDataSource dataSource, LiveDataService liveDataService,
                                       BilibiliDataQueryPainter painter, RevenueVisibilityService revenueVisibility) {
        super(dataSource, liveDataService, painter, revenueVisibility);
    }

    @Override
    public String name() {
        return "直播间数据";
    }

    @Override
    public String description() {
        return "查询直播间本场的整体数据";
    }

    @Override
    protected BilibiliDataScope scope() {
        return BilibiliDataScope.LIVE;
    }
}
