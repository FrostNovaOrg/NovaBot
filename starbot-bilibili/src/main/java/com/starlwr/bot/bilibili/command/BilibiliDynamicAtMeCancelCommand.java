package com.starlwr.bot.bilibili.command;

import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.AtSubscriptionService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 「取消动态@我」命令
 */
@StarBotComponent
public class BilibiliDynamicAtMeCancelCommand extends BilibiliAtSubscribeCommand {
    @Autowired
    public BilibiliDynamicAtMeCancelCommand(AbstractDataSource dataSource, AtSubscriptionService subscriptions) {
        super(dataSource, subscriptions);
    }

    @Override
    public String name() {
        return "取消动态@我";
    }

    @Override
    public String description() {
        return "取消动态提醒";
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
        return false;
    }
}
