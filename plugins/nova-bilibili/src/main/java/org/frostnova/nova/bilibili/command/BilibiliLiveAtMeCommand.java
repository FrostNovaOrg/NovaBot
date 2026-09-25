package org.frostnova.nova.bilibili.command;

import org.frostnova.nova.core.command.CommandSettingsService;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.AtSubscriptionService;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/**
 * 「开播@我」命令
 * <p>
 * <b>开关式</b>：没订就订上，订了就取消。旧名「取消开播@我」留作别名，
 * 但只取消不订——把「取消…」也做成开关的话，手滑再发一次就把刚取消的又订回去了。
 */
@NovaComponent
public class BilibiliLiveAtMeCommand extends BilibiliAtSubscribeCommand {
    @Autowired
    public BilibiliLiveAtMeCommand(AbstractDataSource dataSource, BilibiliStreamerChoice choice,
                                   AtSubscriptionService subscriptions, CommandSettingsService settings) {
        super(dataSource, choice, subscriptions, settings);
    }

    @Override
    public String name() {
        return "开播@我";
    }

    @Override
    public List<String> aliases() {
        return List.of("取消开播@我");
    }

    @Override
    public String description() {
        return "开关开播提醒：没订就订上，订了就取消";
    }

    @Override
    protected BilibiliAtNoticeKind noticeKind() {
        return BilibiliAtNoticeKind.LIVE;
    }
}
