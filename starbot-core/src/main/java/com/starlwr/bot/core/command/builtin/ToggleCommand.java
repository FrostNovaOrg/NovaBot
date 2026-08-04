package com.starlwr.bot.core.command.builtin;

import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.CommandDispatcher;
import com.starlwr.bot.core.command.CommandReply;
import com.starlwr.bot.core.command.CommandSettingsService;
import com.starlwr.bot.core.command.StarBotCommand;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 「启用命令」与「禁用命令」的共同实现
 * <p>
 * 两者除方向外逻辑完全一致，合成一个基类避免两份几乎相同的代码各自演化。
 */
public abstract class ToggleCommand implements StarBotCommand {
    /**
     * 分发器反过来依赖全部命令，用 ObjectProvider 延迟取用以打破循环依赖
     */
    protected final ObjectProvider<CommandDispatcher> dispatcher;

    protected final CommandSettingsService settings;

    protected ToggleCommand(ObjectProvider<CommandDispatcher> dispatcher, CommandSettingsService settings) {
        this.dispatcher = dispatcher;
        this.settings = settings;
    }

    /**
     * 本命令的方向：true 为启用，false 为禁用
     * @return 方向
     */
    protected abstract boolean enabling();

    @Override
    public String usage() {
        return "<命令名>";
    }

    @Override
    public boolean disableable() {
        // 「启用命令」被禁用后就没有任何途径把命令开回来了；
        // 「禁用命令」同样保持不可禁用，与之对称，避免使用者困惑于两者行为不一致
        return false;
    }

    @Override
    public CommandReply execute(CommandContext context) {
        String target = context.arg(0);
        if (target == null) {
            return CommandReply.of("请指明命令名，例如：" + name() + " 直播报告");
        }

        StarBotCommand command = resolve(target);
        if (command == null) {
            return CommandReply.of("没有名为「" + target + "」的命令，发送「菜单」可查看全部命令");
        }
        if (!command.disableable()) {
            return CommandReply.of("「" + command.name() + "」不可开关");
        }

        boolean changed = enabling()
                ? settings.enable(context.getPlatform(), context.getNum(), command.name())
                : settings.disable(context.getPlatform(), context.getNum(), command.name());

        if (!changed) {
            return CommandReply.of("「" + command.name() + "」本来就是" + (enabling() ? "启用" : "禁用") + "状态");
        }
        return CommandReply.of("已" + (enabling() ? "启用" : "禁用") + "「" + command.name() + "」");
    }

    /**
     * 按命令名或别名找到目标命令
     */
    private StarBotCommand resolve(String name) {
        CommandDispatcher instance = dispatcher.getIfAvailable();
        if (instance == null) {
            return null;
        }
        return instance.all().stream()
                .filter(command -> command.name().equals(name) || command.aliases().contains(name))
                .findFirst()
                .orElse(null);
    }
}
