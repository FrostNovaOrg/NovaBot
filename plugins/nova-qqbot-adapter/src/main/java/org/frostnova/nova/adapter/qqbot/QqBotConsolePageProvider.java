package org.frostnova.nova.adapter.qqbot;

import org.frostnova.nova.core.config.ui.ConfigUiOperationProvider;
import org.frostnova.nova.core.config.ui.page.ConsolePageProvider;
import org.frostnova.nova.core.config.ui.page.ConsolePageSlot;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.List;

@NovaComponent
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class QqBotConsolePageProvider implements ConsolePageProvider, ConfigUiOperationProvider {
    static final String ID = "qqbot";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return QqBotPluginAutoConfiguration.DISPLAY_NAME;
    }

    @Override
    public String script() {
        return "qqbot.js";
    }

    @Override
    public ConsolePageSlot slot() {
        return ConsolePageSlot.LINKS;
    }

    @Override
    public boolean botLink() {
        return true;
    }

    @Override
    public List<Operation> operations() {
        return List.of(
                new Operation("POST", "qqbot/credentials", "QQBOT_CREDENTIALS_SAVE", "保存 QQ 官方机器人凭据"),
                new Operation("POST", "qqbot/interval", "QQBOT_INTERVAL_SAVE", "调整 QQ 官方机器人发送间隔"),
                new Operation("POST", "qqbot/groups/{num}/name", "QQBOT_GROUP_NAME_SAVE", "修改 QQ 官方机器人的群名"));
    }
}
