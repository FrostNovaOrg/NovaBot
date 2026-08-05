package com.starlwr.bot.bilibili.command;

import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.CommandReply;
import com.starlwr.bot.core.command.StarBotCommand;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.UserBindingService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 「解绑」命令
 * <p>
 * 解除自己的哔哩哔哩账号绑定。绑错了 uid 时要有路可退，否则只能去改状态文件。
 */
@StarBotComponent
public class BilibiliUnbindCommand implements StarBotCommand {
    private final UserBindingService bindings;

    @Autowired
    public BilibiliUnbindCommand(UserBindingService bindings) {
        this.bindings = bindings;
    }

    @Override
    public String name() {
        return "解绑";
    }

    @Override
    public String description() {
        return "解除哔哩哔哩账号绑定";
    }

    @Override
    public CommandReply execute(CommandContext context) {
        if (context.getSenderUid() == null) {
            return CommandReply.none();
        }

        boolean removed = bindings.unbind(context.getPlatform(), LivePlatform.BILIBILI.getName(), context.getSenderUid());
        return CommandReply.of(removed ? "已解除绑定" : "你还没有绑定哔哩哔哩账号");
    }

    @Override
    public String category() {
        return "账号绑定";
    }
}
