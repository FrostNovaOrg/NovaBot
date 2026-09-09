package org.frostnova.nova.bilibili.command;

import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.AtSubscriptionService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 「动态@名单」命令
 */
@NovaComponent
public class BilibiliDynamicAtListCommand extends BilibiliAtListCommand {
    @Autowired
    public BilibiliDynamicAtListCommand(AbstractDataSource dataSource, BilibiliStreamerChoice choice,
                                        AtSubscriptionService subscriptions) {
        super(dataSource, choice, subscriptions);
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
    protected BilibiliAtNoticeKind kind() {
        return BilibiliAtNoticeKind.DYNAMIC;
    }

    @Override
    protected String typeName() {
        return "发动态";
    }
}
