package com.starlwr.bot.bilibili.command;

import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.AtSubscriptionService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 「取消开播@我」命令
 */
@StarBotComponent
public class BilibiliLiveAtMeCancelCommand extends BilibiliAtSubscribeCommand {
    @Autowired
    public BilibiliLiveAtMeCancelCommand(AbstractDataSource dataSource, AtSubscriptionService subscriptions) {
        super(dataSource, subscriptions);
    }

    @Override
    public String name() {
        return "取消开播@我";
    }

    @Override
    public String description() {
        return "取消开播提醒";
    }

    @Override
    protected String type() {
        return "live";
    }

    @Override
    protected String typeName() {
        return "开播";
    }

    @Override
    protected boolean subscribing() {
        return false;
    }
}
