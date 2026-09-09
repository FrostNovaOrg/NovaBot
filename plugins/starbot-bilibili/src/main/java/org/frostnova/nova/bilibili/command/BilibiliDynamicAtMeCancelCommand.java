package org.frostnova.nova.bilibili.command;

import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.AtSubscriptionService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 「取消动态@我」命令
 */
@NovaComponent
public class BilibiliDynamicAtMeCancelCommand extends BilibiliAtSubscribeCommand {
    @Autowired
    public BilibiliDynamicAtMeCancelCommand(AbstractDataSource dataSource, BilibiliStreamerChoice choice,
                                            AtSubscriptionService subscriptions) {
        super(dataSource, choice, subscriptions);
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
    protected BilibiliAtNoticeKind kind() {
        return BilibiliAtNoticeKind.DYNAMIC;
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
