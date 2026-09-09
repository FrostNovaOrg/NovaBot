package org.frostnova.nova.bilibili.command;

import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.AtSubscriptionService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 「开播@名单」命令
 */
@NovaComponent
public class BilibiliLiveAtListCommand extends BilibiliAtListCommand {
    @Autowired
    public BilibiliLiveAtListCommand(AbstractDataSource dataSource, BilibiliStreamerChoice choice,
                                     AtSubscriptionService subscriptions) {
        super(dataSource, choice, subscriptions);
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
    protected BilibiliAtNoticeKind kind() {
        return BilibiliAtNoticeKind.LIVE;
    }

    @Override
    protected String typeName() {
        return "开播";
    }
}
