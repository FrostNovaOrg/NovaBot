package com.starlwr.bot.bilibili.command;

import com.starlwr.bot.bilibili.model.BilibiliDataScope;
import com.starlwr.bot.bilibili.painter.BilibiliDataQueryPainter;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.RevenueVisibilityService;
import com.starlwr.bot.core.service.UserBindingService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 「我的总数据」命令：查询跨场次累计的个人数据
 */
@StarBotComponent
public class BilibiliMyTotalDataCommand extends BilibiliMyDataCommand {
    @Autowired
    public BilibiliMyTotalDataCommand(AbstractDataSource dataSource, LiveDataService liveDataService,
                                      BilibiliDataQueryPainter painter, UserBindingService bindings,
                                      BilibiliApiUtil api, RevenueVisibilityService revenueVisibility) {
        super(dataSource, liveDataService, painter, bindings, api, revenueVisibility);
    }

    @Override
    public String name() {
        return "我的总数据";
    }

    @Override
    public String description() {
        return "查询自己在该直播间的累计互动数据与名次";
    }

    @Override
    protected BilibiliDataScope scope() {
        return BilibiliDataScope.TOTAL;
    }
}
