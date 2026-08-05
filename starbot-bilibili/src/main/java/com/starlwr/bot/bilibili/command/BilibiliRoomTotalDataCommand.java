package com.starlwr.bot.bilibili.command;

import com.starlwr.bot.bilibili.model.BilibiliDataScope;
import com.starlwr.bot.bilibili.painter.BilibiliDataQueryPainter;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.LiveDataService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 「直播间总数据」命令：查询直播间跨场次累计的整体数据
 */
@StarBotComponent
public class BilibiliRoomTotalDataCommand extends BilibiliRoomDataCommand {
    @Autowired
    public BilibiliRoomTotalDataCommand(AbstractDataSource dataSource, LiveDataService liveDataService,
                                        BilibiliDataQueryPainter painter) {
        super(dataSource, liveDataService, painter);
    }

    @Override
    public String name() {
        return "直播间总数据";
    }

    @Override
    public String description() {
        return "查询直播间历次直播累计的整体数据";
    }

    @Override
    protected BilibiliDataScope scope() {
        return BilibiliDataScope.TOTAL;
    }
}
