package com.starlwr.bot.bilibili.command;

import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.CommandReply;
import com.starlwr.bot.core.command.StarBotCommand;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.service.AtSubscriptionService;
import com.starlwr.bot.core.util.StringUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 「@我」类命令的共同实现
 * <p>
 * 六个命令（开播 / 动态 × 订阅 / 取消 / 名单）除订阅类型与动作外完全一致，
 * 共同逻辑收在这里：找出本群配置了哪些主播、参数指定了哪一位、以及回复措辞。
 */
public abstract class BilibiliAtCommand implements StarBotCommand {
    protected final AbstractDataSource dataSource;

    protected final AtSubscriptionService subscriptions;

    protected BilibiliAtCommand(AbstractDataSource dataSource, AtSubscriptionService subscriptions) {
        this.dataSource = dataSource;
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
        List<PushUser> candidates = streamersOf(context);
        if (candidates.isEmpty()) {
            return CommandReply.of("本群没有配置任何哔哩哔哩主播的推送");
        }

        String keyword = context.arg(0);
        PushUser streamer;
        if (keyword == null) {
            if (candidates.size() > 1) {
                // 本群配置了多位主播时不能替使用者猜，列出来让其指定
                return CommandReply.of("本群配置了多位主播，请指明是哪一位：\n" + describe(candidates)
                        + "\n例如：" + name() + " " + candidates.get(0).getUid());
            }
            streamer = candidates.get(0);
        } else {
            streamer = match(candidates, keyword);
            if (streamer == null) {
                return CommandReply.of("本群没有配置「" + keyword + "」的推送，当前可选：\n" + describe(candidates));
            }
        }

        return act(context, streamer);
    }

    /**
     * 对指定主播执行本命令的动作
     */
    protected abstract CommandReply act(CommandContext context, PushUser streamer);

    /**
     * 找出本群配置了推送的全部哔哩哔哩主播
     */
    private List<PushUser> streamersOf(CommandContext context) {
        List<PushUser> result = new ArrayList<>();
        for (PushUser user : dataSource.getUsers(LivePlatform.BILIBILI.getName())) {
            if (Boolean.FALSE.equals(user.getEnabled()) || user.getUid() == null) {
                continue;
            }

            boolean inThisSession = user.getTargets().stream()
                    .filter(target -> !Boolean.FALSE.equals(target.getEnabled()))
                    .anyMatch(target -> context.getPlatform().equals(target.getPlatform())
                            && context.getType() == target.getType()
                            && context.getNum().equals(target.getNum()));

            if (inThisSession) {
                result.add(user);
            }
        }
        return result;
    }

    /**
     * 按 uid 或昵称关键字匹配主播
     */
    private PushUser match(List<PushUser> candidates, String keyword) {
        for (PushUser user : candidates) {
            if (String.valueOf(user.getUid()).equals(keyword)) {
                return user;
            }
        }
        for (PushUser user : candidates) {
            if (StringUtil.isNotBlank(user.getUname()) && user.getUname().contains(keyword)) {
                return user;
            }
        }
        return null;
    }

    /**
     * 列出候选主播，供使用者选择
     */
    private String describe(List<PushUser> candidates) {
        StringBuilder text = new StringBuilder();
        for (PushUser user : candidates) {
            text.append("· ").append(StringUtil.isBlank(user.getUname()) ? "未知主播" : user.getUname())
                    .append("（").append(user.getUid()).append("）\n");
        }
        return text.toString().trim();
    }

    /**
     * 主播的展示名
     */
    protected String nameOf(PushUser streamer) {
        return StringUtil.isBlank(streamer.getUname()) ? String.valueOf(streamer.getUid()) : streamer.getUname();
    }

    /**
     * 目标是否为本会话的推送目标，用于校验
     */
    protected boolean targets(PushTarget target, CommandContext context) {
        return context.getPlatform().equals(target.getPlatform())
                && context.getType() == target.getType()
                && context.getNum().equals(target.getNum());
    }
}
