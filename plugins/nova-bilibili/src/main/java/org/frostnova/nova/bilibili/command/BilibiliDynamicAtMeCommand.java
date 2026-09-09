package org.frostnova.nova.bilibili.command;

import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.AtSubscriptionService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 「动态@我」命令
 */
@NovaComponent
public class BilibiliDynamicAtMeCommand extends BilibiliAtSubscribeCommand {
    @Autowired
    public BilibiliDynamicAtMeCommand(AbstractDataSource dataSource, BilibiliStreamerChoice choice,
                                      AtSubscriptionService subscriptions) {
        super(dataSource, choice, subscriptions);
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
    protected BilibiliAtNoticeKind kind() {
        return BilibiliAtNoticeKind.DYNAMIC;
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
