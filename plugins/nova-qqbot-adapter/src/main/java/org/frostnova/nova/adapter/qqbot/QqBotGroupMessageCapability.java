package org.frostnova.nova.adapter.qqbot;

import org.frostnova.nova.core.command.GroupMessageCapability;
import org.frostnova.nova.core.plugin.NovaComponent;

@NovaComponent
final class QqBotGroupMessageCapability implements GroupMessageCapability {
    @Override
    public String platform() {
        return QqBotPluginAutoConfiguration.PLATFORM;
    }

    @Override
    public boolean receivesUnmentionedGroupMessages() {
        return false;
    }
}
