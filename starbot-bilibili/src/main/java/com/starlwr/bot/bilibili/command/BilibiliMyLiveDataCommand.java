package com.starlwr.bot.bilibili.command;

import com.starlwr.bot.bilibili.model.BilibiliDataScope;
import com.starlwr.bot.bilibili.painter.BilibiliDataQueryPainter;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.UserBindingService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 「我的数据」命令：查询本场直播的个人数据
 */
@StarBotComponent
public class BilibiliMyLiveDataCommand extends BilibiliMyDataCommand {
    @Autowired
    public BilibiliMyLiveDataCommand(AbstractDataSource dataSource, LiveDataService liveDataService,
                                     BilibiliDataQueryPainter painter, UserBindingService bindings,
                                     BilibiliApiUtil api) {
        super(dataSource, liveDataService, painter, bindings, api);
    }

    @Override
    public String name() {
        return "我的数据";
    }

    @Override
    public String description() {
        return "查询自己在本场直播的互动数据与名次";
    }

    @Override
    protected BilibiliDataScope scope() {
        return BilibiliDataScope.LIVE;
    }
}
