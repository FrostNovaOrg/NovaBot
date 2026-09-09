package org.frostnova.nova.core.command.builtin;

import org.frostnova.nova.core.command.CommandDispatcher;
import org.frostnova.nova.core.command.CommandSettingsService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 「启用命令」命令
 */
@Component
public class EnableCommand extends ToggleCommand {
    @Autowired
    public EnableCommand(ObjectProvider<CommandDispatcher> dispatcher, CommandSettingsService settings) {
        super(dispatcher, settings);
    }

    @Override
    public String name() {
        return "启用命令";
    }

    @Override
    public String description() {
        return "在本群启用某个命令";
    }

    @Override
    protected boolean enabling() {
        return true;
    }
}
