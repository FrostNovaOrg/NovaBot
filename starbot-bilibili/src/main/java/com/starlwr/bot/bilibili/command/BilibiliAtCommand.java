package com.starlwr.bot.bilibili.command;

import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.CommandReply;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.service.AtSubscriptionService;

/**
 * 「@我」类命令的共同实现
 * <p>
 * 六个命令（开播 / 动态 × 订阅 / 取消 / 名单）除订阅类型与动作外完全一致，
 * 共同逻辑收在这里；「说的是哪位主播」这一步与数据查询命令共用
 * {@link BilibiliStreamerCommand}。
 */
public abstract class BilibiliAtCommand extends BilibiliStreamerCommand {
    protected final AtSubscriptionService subscriptions;

    protected BilibiliAtCommand(AbstractDataSource dataSource, AtSubscriptionService subscriptions) {
        super(dataSource);
        this.subscriptions = subscriptions;
    }

    /**
     * 订阅类型：live 或 dynamic
     */
    protected abstract String type();

    /**
     * 类型的中文说法，用于回复措辞
     */
    protected abstract String typeName();

    @Override
    public String usage() {
        return "[主播 uid 或昵称]";
    }

    @Override
    public CommandReply execute(CommandContext context) {
        Resolved resolved = resolve(context, context.arg(0));
        return resolved.failed() ? resolved.error() : act(context, resolved.streamer());
    }

    /**
     * 对指定主播执行本命令的动作
     */
    protected abstract CommandReply act(CommandContext context, PushUser streamer);

    /**
     * 目标是否为本会话的推送目标，用于校验
     */
    protected boolean targets(PushTarget target, CommandContext context) {
        return context.getPlatform().equals(target.getPlatform())
                && context.getType() == target.getType()
                && context.getNum().equals(target.getNum());
    }

    @Override
    public String category() {
        return "提醒订阅";
    }
}
