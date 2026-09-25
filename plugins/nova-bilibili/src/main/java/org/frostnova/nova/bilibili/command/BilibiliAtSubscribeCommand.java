package org.frostnova.nova.bilibili.command;

import org.frostnova.nova.core.command.CommandContext;
import org.frostnova.nova.core.command.CommandReply;
import org.frostnova.nova.core.command.CommandSettingsService;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.service.AtSubscriptionService;

import java.util.List;

/**
 * 「@我」命令的开关式实现
 * <p>
 * 正名（{@code 开播@我} / {@code 动态@我}）是<b>开关</b>：没订就订上，订了就取消；
 * 回复里写清这一下做了什么、怎么反悔。旧的「{@code 取消开播@我}」留作别名，
 * 但<b>只取消不订</b>——把「取消…」也做成开关的话，手滑再发一次就把刚取消的又订回去了。
 *
 * <h2>开关式取消要过取消那一格的口子</h2>
 * 正名开关到「取消」时，能力上走的是取消那一格：若管理员只关了「{@code 取消开播@我}」，
 * 从开关这边照样能把人从名单里放出去，「禁用取消」就成了摆设。因此这里自己再查一遍
 * 取消那一格的禁用状态，关了就说清，不替他办。
 */
public abstract class BilibiliAtSubscribeCommand extends BilibiliAtCommand {
    protected final CommandSettingsService settings;

    protected BilibiliAtSubscribeCommand(AbstractDataSource dataSource, BilibiliStreamerChoice choice,
                                         AtSubscriptionService subscriptions, CommandSettingsService settings) {
        super(dataSource, choice, subscriptions);
        this.settings = settings;
    }

    @Override
    protected List<BilibiliAtNoticeKind> kinds(CommandContext context) {
        // 正名与取消别名说的是同一件事，问的都是同一类
        return List.of(noticeKind());
    }

    /**
     * 这条命令管的是哪一类通知
     */
    protected abstract BilibiliAtNoticeKind noticeKind();

    /**
     * 类型的说法，用于回复措辞
     */
    protected String typeName() {
        return noticeKind().typeName();
    }

    /**
     * 取消方向的拼写，即那个以「取消」开头的别名
     * <p>
     * 没有取消别名时退回正名：那时开关式取消也走正名自己的格子。
     */
    protected String cancelSpelling() {
        for (String alias : aliases()) {
            if (alias.startsWith("取消")) {
                return alias;
            }
        }
        return name();
    }

    /**
     * 这一句是只取消（旧名）还是开关式（正名）
     */
    protected boolean cancelOnly(CommandContext context) {
        return context.getCommand().startsWith("取消");
    }

    @Override
    public List<String> usageKeys(CommandContext context) {
        String typed = context.getCommand();
        // 这一句怎么打的就记哪一格：正名与取消别名是两个开关，各记各的
        if (name().equals(typed) || aliases().contains(typed)) {
            return List.of(typed);
        }
        // 菜单那一路（上下文是菜单自己的名字）：菜单那一行写的就是正名，正名关了这一行就该收走。
        // 取消用的旧名是另一个开关、另一个记账名，它关没关管不着这一行
        return List.of(name());
    }

    @Override
    protected CommandReply act(CommandContext context, PushUser streamer) {
        String platform = context.getPlatform();
        Long num = context.getNum();
        Long uid = streamer.getUid();
        String type = type(context);
        Long sender = context.getSenderUid();

        boolean subscribed = subscriptions.contains(platform, num, uid, type, sender);
        boolean cancelOnly = cancelOnly(context);
        // 旧名「取消…」只取消；正名是开关式，已订再发一次就取消
        boolean wantCancel = cancelOnly || subscribed;

        if (wantCancel && !cancelOnly) {
            // 开关式取消要走取消那一格的口子：那一格被关了就得说清，
            // 否则「禁用命令 取消开播@我」关不住从开关这边退出去的人
            String cancel = cancelSpelling();
            if (settings.isDisabled(platform, num, cancel)) {
                return CommandReply.of(context.here() + "已关闭「" + cancel + "」命令，再发「"
                        + name() + "」也取消不了");
            }
        }

        if (wantCancel) {
            if (!subscribed) {
                return CommandReply.of("你本来就没有订阅 " + nameOf(streamer) + " 的" + typeName() + "提醒");
            }
            subscriptions.unsubscribe(platform, num, uid, type, sender);
            // 只取消的旧名沿用老说法；开关式补一句怎么订回来
            return CommandReply.of(cancelOnly
                    ? "已取消，" + nameOf(streamer) + typeName() + "时不再 @ 你"
                    : "已取消，" + nameOf(streamer) + typeName() + "时不再 @ 你。再发「" + name() + "」可订回来");
        }

        AtSubscriptionService.Result result = subscriptions.subscribe(platform, num, uid, type, sender);
        return switch (result) {
            case OK -> CommandReply.of(cancelOnly
                    ? "好的，" + nameOf(streamer) + typeName() + "时会 @ 你"
                    : "好的，" + nameOf(streamer) + typeName() + "时会 @ 你。再发「" + name() + "」可取消");
            case ALREADY -> CommandReply.of("你已经订阅过 " + nameOf(streamer) + " 的" + typeName() + "提醒了");
            case FULL -> CommandReply.of("该提醒的订阅人数已达上限，无法再加入");
        };
    }
}
