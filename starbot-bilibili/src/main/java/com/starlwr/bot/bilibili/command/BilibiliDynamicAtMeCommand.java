package com.starlwr.bot.bilibili.command;

import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.AtSubscriptionService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 「动态@我」命令
 */
@StarBotComponent
public class BilibiliDynamicAtMeCommand extends BilibiliAtSubscribeCommand {
    @Autowired
    public BilibiliDynamicAtMeCommand(AbstractDataSource dataSource, AtSubscriptionService subscriptions) {
        super(dataSource, subscriptions);
    }

    @Override
    public String name() {
        return "动态@我";
    }

    @Override
    public String description() {
        return "主播发动态时 @ 你";
    }

    @Override
    protected String type() {
        return "dynamic";
    }

    @Override
    protected String typeName() {
        return "发动态";
    }

    @Override
    protected boolean subscribing() {
        return true;
    }
}
