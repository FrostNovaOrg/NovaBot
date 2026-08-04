package com.starlwr.bot.bilibili.command;

import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.AtSubscriptionService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 「开播@名单」命令
 */
@StarBotComponent
public class BilibiliLiveAtListCommand extends BilibiliAtListCommand {
    @Autowired
    public BilibiliLiveAtListCommand(AbstractDataSource dataSource, AtSubscriptionService subscriptions) {
        super(dataSource, subscriptions);
    }

    @Override
    public String name() {
        return "开播@名单";
    }

    @Override
    public String description() {
        return "查看开播提醒的订阅名单";
    }

    @Override
    protected String type() {
        return "live";
    }

    @Override
    protected String typeName() {
        return "开播";
    }
}
