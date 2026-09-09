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
 * 「直播间数据」命令：查询本场直播的整体数据
 */
@NovaComponent
public class BilibiliRoomLiveDataCommand extends BilibiliRoomDataCommand {
    @Autowired
    public BilibiliRoomLiveDataCommand(AbstractDataSource dataSource, BilibiliStreamerChoice choice,
                                       LiveDataService liveDataService,
                                       BilibiliDataQueryPainter painter, RevenueVisibilityService revenueVisibility) {
        super(dataSource, choice, liveDataService, painter, revenueVisibility);
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
