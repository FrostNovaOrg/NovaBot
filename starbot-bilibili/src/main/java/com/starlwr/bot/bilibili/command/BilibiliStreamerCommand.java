package com.starlwr.bot.bilibili.command;

import com.starlwr.bot.bilibili.BilibiliPlatform;
import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.CommandReply;
import com.starlwr.bot.core.command.StarBotCommand;
import com.starlwr.bot.core.datasource.AbstractDataSource;
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
 * <p>
 * 没带参数那一路交给 {@link BilibiliStreamerChoice} 去问；
 * 替他定下来的那一次带回一句 {@link Resolved#notice()}，命令用 {@link #withNotice}
 * 把它加在回复前面。
 */
public abstract class BilibiliStreamerCommand implements StarBotCommand {
    protected final AbstractDataSource dataSource;

    /**
     * 多主播时「问是哪一位」
     */
    protected final BilibiliStreamerChoice choice;

    protected BilibiliStreamerCommand(AbstractDataSource dataSource, BilibiliStreamerChoice choice) {
        this.dataSource = dataSource;
        this.choice = choice;
    }

    /**
     * 解析出本命令要操作的主播
     * <p>
     * 规则只有三条：点了名（uid 或昵称片段）一次到位；没点名而本会话<b>恰好一位</b>能定下来
     * ——只配了一位，或多位里只有一位在播——就径直办，并在回复里说清用的是谁；
     * 其余一律回一份带序号的清单让他自己挑。
     * <p>
     * <b>不记他上次选的是谁。</b>记着的那一位与他这一次想看的那一位不是同一位时，
     * 回来的是另一位主播的数据，而那张图看起来完全正常；他也无从知道机器人正替他记着什么。
     * @param context 执行上下文
     * @param keyword 主播关键字，为空表示未指定
     * @return 解析结果，失败时带着给使用者的说明
     */
    protected Resolved resolve(CommandContext context, String keyword) {
        List<PushUser> candidates = streamersOf(context);
        String here = context.here();
        if (candidates.isEmpty()) {
            return Resolved.failed(here + "没有配置任何哔哩哔哩主播的推送");
        }

        if (StringUtil.isNotBlank(keyword)) {
            PushUser matched = match(candidates, keyword);
            if (matched == null) {
                return Resolved.failed(here + "没有配置「" + keyword + "」的推送，当前可选：\n" + describe(candidates));
            }
            return Resolved.of(matched);
        }

        if (candidates.size() == 1) {
            return Resolved.of(candidates.get(0));
        }

        BilibiliStreamerChoice.Ranked ranked = choice.rank(candidates);
        if (ranked.living() == 1) {
            PushUser only = ranked.ordered().get(0);
            return Resolved.of(only, "本次用的是：" + nameOf(only) + "（只有 TA 在播）");
        }

        return Resolved.failed(choice.ask(context, ranked));
    }

    /**
     * 把「这次用的是谁」那一行加在回复前面
     * <p>
     * 只在<b>没点名</b>时才有这一行：点了名的那一次，用的是谁本就是他自己说的。
     * @param resolved 解析结果
     * @param reply 命令自己的回复
     * @return 加过说明的回复
     */
    protected CommandReply withNotice(Resolved resolved, CommandReply reply) {
        return StringUtil.isBlank(resolved.notice()) || reply == null || !reply.hasContent()
                ? reply
                : CommandReply.of(resolved.notice() + "\n" + reply.content());
    }

    /**
     * 找出本会话配置了推送的全部哔哩哔哩主播
     */
    protected List<PushUser> streamersOf(CommandContext context) {
        List<PushUser> result = new ArrayList<>();
        for (PushUser user : dataSource.getUsers(BilibiliPlatform.BILIBILI.id())) {
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
     * 列出候选主播
     * <p>
     * 只用在「点了名但这个名字没配过」那一路，因此<b>不带序号</b>：
     * 带序号就等于说「回个数字也行」，而这一路并没有开着追问，回过去的数字不会有任何反应。
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
     * @param notice 替他定下来的那一次要说清用的是谁，点了名时为空
     */
    protected record Resolved(PushUser streamer, CommandReply error, String notice) {
        static Resolved of(PushUser streamer) {
            return new Resolved(streamer, null, null);
        }

        static Resolved of(PushUser streamer, String notice) {
            return new Resolved(streamer, null, notice);
        }

        static Resolved failed(String message) {
            return new Resolved(null, CommandReply.of(message), null);
        }

        /**
         * 是否未能确定主播
         */
        boolean failed() {
            return streamer == null;
        }
    }
}
