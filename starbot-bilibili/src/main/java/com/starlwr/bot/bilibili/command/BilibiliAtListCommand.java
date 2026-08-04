package com.starlwr.bot.bilibili.command;

import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.CommandReply;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.service.AtSubscriptionService;

import java.util.List;

/**
 * 名单类「@我」命令的共同实现
 */
public abstract class BilibiliAtListCommand extends BilibiliAtCommand {
    /**
     * 名单中最多列出的人数，超出只报总数
     * <p>
     * 名单动辄上百人时全部列出既刷屏又没人真的会看完。
     */
    private static final int MAX_SHOWN = 30;

    protected BilibiliAtListCommand(AbstractDataSource dataSource, AtSubscriptionService subscriptions) {
        super(dataSource, subscriptions);
    }

    @Override
    protected CommandReply act(CommandContext context, PushUser streamer) {
        List<Long> subscribers = subscriptions.list(
                context.getPlatform(), context.getNum(), streamer.getUid(), type());

        if (subscribers.isEmpty()) {
            return CommandReply.of("还没有人订阅 " + nameOf(streamer) + " 的" + typeName() + "提醒");
        }

        StringBuilder text = new StringBuilder(nameOf(streamer) + " 的" + typeName()
                + "提醒共 " + subscribers.size() + " 人订阅");

        if (subscribers.size() > MAX_SHOWN) {
            text.append("（人数较多，不逐一列出）");
            return CommandReply.of(text.toString());
        }

        text.append("：");
        for (Long uid : subscribers) {
            text.append("\n· ").append(uid);
        }
        return CommandReply.of(text.toString());
    }
}
