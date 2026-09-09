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
 * 「直播间总数据」命令：查询直播间跨场次累计的整体数据
 */
@NovaComponent
public class BilibiliRoomTotalDataCommand extends BilibiliRoomDataCommand {
    @Autowired
    public BilibiliRoomTotalDataCommand(AbstractDataSource dataSource, BilibiliStreamerChoice choice,
                                        LiveDataService liveDataService,
                                        BilibiliDataQueryPainter painter, RevenueVisibilityService revenueVisibility) {
        super(dataSource, choice, liveDataService, painter, revenueVisibility);
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
