package org.frostnova.nova.bilibili.command;

import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.AtSubscriptionService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 「取消开播@我」命令
 */
@NovaComponent
public class BilibiliLiveAtMeCancelCommand extends BilibiliAtSubscribeCommand {
    @Autowired
    public BilibiliLiveAtMeCancelCommand(AbstractDataSource dataSource, BilibiliStreamerChoice choice,
                                         AtSubscriptionService subscriptions) {
        super(dataSource, choice, subscriptions);
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
    protected BilibiliAtNoticeKind kind() {
        return BilibiliAtNoticeKind.LIVE;
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
