package com.starlwr.bot.bilibili.command;

import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.CommandReply;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.service.AtSubscriptionService;

/**
 * 订阅类「@我」命令的共同实现
 */
public abstract class BilibiliAtSubscribeCommand extends BilibiliAtCommand {
    protected BilibiliAtSubscribeCommand(AbstractDataSource dataSource, AtSubscriptionService subscriptions) {
        super(dataSource, subscriptions);
    }

    /**
     * 是否为订阅方向：true 订阅，false 取消订阅
     */
    protected abstract boolean subscribing();

    @Override
    protected CommandReply act(CommandContext context, PushUser streamer) {
        AtSubscriptionService.Result result = subscribing()
                ? subscriptions.subscribe(context.getPlatform(), context.getNum(), streamer.getUid(), type(), context.getSenderUid())
                : subscriptions.unsubscribe(context.getPlatform(), context.getNum(), streamer.getUid(), type(), context.getSenderUid());

        return switch (result) {
            case OK -> CommandReply.of(subscribing()
                    ? "好的，" + nameOf(streamer) + typeName() + "时会 @ 你"
                    : "已取消，" + nameOf(streamer) + typeName() + "时不再 @ 你");
            case ALREADY -> CommandReply.of(subscribing()
                    ? "你已经订阅过 " + nameOf(streamer) + " 的" + typeName() + "提醒了"
                    : "你本来就没有订阅 " + nameOf(streamer) + " 的" + typeName() + "提醒");
            case FULL -> CommandReply.of("该提醒的订阅人数已达上限，无法再加入");
        };
    }
}
