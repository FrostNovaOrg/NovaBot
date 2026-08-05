package com.starlwr.bot.bilibili.command;

import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.CommandReply;
import com.starlwr.bot.core.command.StarBotCommand;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.util.StringUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 针对某位主播的命令
 * <p>
 * 「@我」与各类数据查询都要先回答同一个问题：<b>这条命令说的是哪位主播</b>。
 * 一个群可能同时推送多位主播，参数可能是 uid 也可能是昵称片段，还可能干脆没带参数。
 * 这套解析规则收在这里，各命令只管拿到主播之后做自己的事。
 */
public abstract class BilibiliStreamerCommand implements StarBotCommand {
    protected final AbstractDataSource dataSource;

    protected BilibiliStreamerCommand(AbstractDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 解析出本命令要操作的主播
     * <p>
     * 本群只配了一位时省略参数即可；配了多位又没指明时不替使用者猜，而是列出来让其选。
     * @param context 执行上下文
     * @param keyword 主播关键字，为空表示未指定
     * @return 解析结果，失败时带着给使用者的说明
     */
    protected Resolved resolve(CommandContext context, String keyword) {
        List<PushUser> candidates = streamersOf(context);
        if (candidates.isEmpty()) {
            return Resolved.failed("本群没有配置任何哔哩哔哩主播的推送");
        }

        if (StringUtil.isBlank(keyword)) {
            if (candidates.size() > 1) {
                return Resolved.failed("本群配置了多位主播，请指明是哪一位：\n" + describe(candidates)
                        + "\n例如：" + name() + " " + candidates.get(0).getUid());
            }
            return Resolved.of(candidates.get(0));
        }

        PushUser matched = match(candidates, keyword);
        return matched == null
                ? Resolved.failed("本群没有配置「" + keyword + "」的推送，当前可选：\n" + describe(candidates))
                : Resolved.of(matched);
    }

    /**
     * 找出本群配置了推送的全部哔哩哔哩主播
     */
    protected List<PushUser> streamersOf(CommandContext context) {
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
     * 按 uid 或昵称关键字匹配主播，uid 优先
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
     * 主播解析结果
     * @param streamer 解析出的主播，失败时为 null
     * @param error 失败时给使用者的说明
     */
    protected record Resolved(PushUser streamer, CommandReply error) {
        static Resolved of(PushUser streamer) {
            return new Resolved(streamer, null);
        }

        static Resolved failed(String message) {
            return new Resolved(null, CommandReply.of(message));
        }

        /**
         * 是否未能确定主播
         */
        boolean failed() {
            return streamer == null;
        }
    }
}
