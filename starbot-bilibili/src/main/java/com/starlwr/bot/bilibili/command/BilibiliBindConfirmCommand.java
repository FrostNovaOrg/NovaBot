package com.starlwr.bot.bilibili.command;

import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.CommandReply;
import com.starlwr.bot.core.command.StarBotCommand;
import com.starlwr.bot.core.plugin.StarBotComponent;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 「确认绑定」命令
 * <p>
 * 「绑定」的第二步。单列一个命令而不是让人回复「是」：群里「是」这个字出现得太频繁，
 * 拿它当确认词会误伤正常聊天。
 */
@StarBotComponent
public class BilibiliBindConfirmCommand implements StarBotCommand {
    private final BilibiliBindCommand bind;

    @Autowired
    public BilibiliBindConfirmCommand(BilibiliBindCommand bind) {
        this.bind = bind;
    }

    @Override
    public String name() {
        return "确认绑定";
    }

    @Override
    public String description() {
        return "确认「绑定」命令查到的账号，完成绑定";
    }

    @Override
    public CommandReply execute(CommandContext context) {
        return context.getSenderUid() == null ? CommandReply.none() : bind.confirm(context);
    }

    @Override
    public String category() {
        return "账号绑定";
    }
}
