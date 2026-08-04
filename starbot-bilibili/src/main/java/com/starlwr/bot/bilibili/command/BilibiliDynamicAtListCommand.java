package com.starlwr.bot.bilibili.command;

import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.AtSubscriptionService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 「动态@名单」命令
 */
@StarBotComponent
public class BilibiliDynamicAtListCommand extends BilibiliAtListCommand {
    @Autowired
    public BilibiliDynamicAtListCommand(AbstractDataSource dataSource, AtSubscriptionService subscriptions) {
        super(dataSource, subscriptions);
    }

    @Override
    public String name() {
        return "动态@名单";
    }

    @Override
    public String description() {
        return "查看动态提醒的订阅名单";
    }

    @Override
    protected String type() {
        return "dynamic";
    }

    @Override
    protected String typeName() {
        return "发动态";
    }
}
