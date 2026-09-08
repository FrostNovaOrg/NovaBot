package com.starlwr.bot.bilibili.command;

import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.CommandReply;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.handler.NovaEventHandler;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.sender.AtMode;
import com.starlwr.bot.core.service.AtSubscriptionService;

import java.util.ArrayList;
import java.util.List;

/**
 * 「@我」类命令的共同实现
 * <p>
 * 六个命令（开播 / 动态 × 订阅 / 取消 / 名单）除通知类别与动作外完全一致，
 * 共同逻辑收在这里；「说的是哪位主播」这一步与数据查询命令共用
 * {@link BilibiliStreamerCommand}。
 *
 * <h2>与 @ 模式的联动</h2>
 * 本群这类通知配成了「@全体成员」时，单独订阅<b>做什么都不会有效果</b>——每次通知本来就
 * @ 到所有人。此时三条命令一并从菜单里撤下，仍然发过来的回一句为什么，而<b>订阅名单一个不删</b>：
 * 模式随时可能改回来，而删掉是不可逆的。配成「@全体成员，不行就 @订阅的人」的那一档
 * 订阅照样有用（只是平时用不上），命令与菜单都照常，只在菜单那一行后面补一句说明。
 */
public abstract class BilibiliAtCommand extends BilibiliStreamerCommand {
    protected final AtSubscriptionService subscriptions;

    protected BilibiliAtCommand(AbstractDataSource dataSource, BilibiliStreamerChoice choice,
                                AtSubscriptionService subscriptions) {
        super(dataSource, choice);
        this.subscriptions = subscriptions;
    }

    /**
     * 本命令管的是哪一类通知
     */
    protected abstract BilibiliAtNoticeKind kind();

    /**
     * 「@全体成员」那两句给人看的话：群里说「本群」，私聊说「这里」。
     * <p>
     * 取词走 {@link CommandContext#here()}，与数据查询命令同一处口径。
     * {@code fallback} 为真是菜单那句（先 @ 全体，不成再按名单），为假是发过来时那句。
     */
    private String everyoneNotice(CommandContext context, boolean fallback) {
        return context.here() + kind().noticeName()
                + (fallback
                        ? "通知会先 @全体成员，@ 不成时才按这份名单 @ 人"
                        : "通知会 @全体成员，不用单独订阅");
    }

    /**
     * 订阅类型：live 或 dynamic
     */
    protected final String type() {
        return kind().type();
    }

    /**
     * 类型的中文说法，用于回复措辞
     */
    protected abstract String typeName();

    @Override
    public String usage() {
        return "[主播 uid 或昵称]";
    }

    @Override
    public boolean availableIn(CommandContext context) {
        return super.availableIn(context) && !atsEveryone(context);
    }

    @Override
    public String menuNote(CommandContext context) {
        return hasFallbackToSubscribers(context)
                ? everyoneNotice(context, true)
                : "";
    }

    @Override
    public CommandReply execute(CommandContext context) {
        // 排在解析主播之前：这一句与「说的是哪位主播」无关，本群配成 @全体成员 时
        // 任何一位主播的订阅都同样不起作用，先问一遍主播只会多出一次追问
        if (atsEveryone(context)) {
            return CommandReply.of(everyoneNotice(context, false));
        }

        Resolved resolved = resolve(context, context.arg(0));
        return resolved.failed() ? resolved.error() : act(context, resolved.streamer());
    }

    /**
     * 对指定主播执行本命令的动作
     */
    protected abstract CommandReply act(CommandContext context, PushUser streamer);

    /**
     * 本会话这一类通知是不是<b>每一位</b>主播都 @全体成员
     * <p>
     * 要求「每一位」而不是「有一位」：一个群里可以配好几位主播，只要还有一位是按订阅名单 @ 的，
     * 订阅这件事就仍然有用，藏掉命令等于把那一位的提醒一起藏了。
     * <p>
     * <b>一条都没配时不算</b>。空表上「全都是 @全体成员」恒为真，而那时藏掉订阅命令毫无道理——
     * 恒真的判据与「真的查过了」在菜单上长得一模一样。
     * @param context 执行上下文
     * @return 是否整个会话都 @全体成员
     */
    protected boolean atsEveryone(CommandContext context) {
        List<AtMode> modes = modesOf(context);
        return !modes.isEmpty() && modes.stream().allMatch(mode -> AtMode.ALL == mode);
    }

    /**
     * 本会话这一类通知里有没有「@ 不成就退回订阅名单」的那一档
     */
    protected boolean hasFallbackToSubscribers(CommandContext context) {
        return modesOf(context).contains(AtMode.ALL_OR_SUBSCRIBERS);
    }

    /**
     * 本会话这一类通知逐条推送配置的 @ 模式
     * <p>
     * 模式配在「主播 → 通道 → 这一类通知」那一层，因此同一个群里各主播可以各配各的。
     * @param context 执行上下文
     * @return 模式列表，本群没配过这类通知时为空
     */
    private List<AtMode> modesOf(CommandContext context) {
        List<AtMode> modes = new ArrayList<>();
        for (PushUser user : streamersOf(context)) {
            for (PushTarget target : user.getTargets()) {
                if (Boolean.FALSE.equals(target.getEnabled()) || !targets(target, context)) {
                    continue;
                }
                for (PushMessage message : target.getMessages()) {
                    // 关掉的那条通知不推，它配成什么都影响不到群里的人
                    if (Boolean.FALSE.equals(message.getEnabled()) || !isKind(message)) {
                        continue;
                    }
                    modes.add(AtMode.of(message.getParamsJsonObject()));
                }
            }
        }
        return modes;
    }

    /**
     * 这条推送是不是本命令管的那一类通知
     * <p>
     * 三种写法都得认，缺一种就有一批配置读成「本群没配过这类通知」——那时菜单照列、
     * 命令照办，谁都看不出订阅其实早已不起作用：
     * <ul>
     *   <li>解析出来的真类名：处理器就住在它现在这个名字下，新写的配置走这条；</li>
     *   <li>配置里那一串：处理器还没解析出来（认不出、或压根没装那个插件）时只有它；</li>
     *   <li>处理器自己声明的旧名：{@link BilibiliAtNoticeKind} 给的可能是搬走之前那一串
     *       （本模块不许指名别的插件的类），而新配置里写的是搬完之后的名字，
     *       两头对不上，只能由处理器自己把两个名字挂起来。</li>
     * </ul>
     */
    private boolean isKind(PushMessage message) {
        String wanted = kind().handlerName();
        if (wanted.equals(message.handlerClassName()) || wanted.equals(message.getHandler())) {
            return true;
        }
        return message.getHandlerInstance() instanceof NovaEventHandler handler
                && handler.legacyClassNames().contains(wanted);
    }

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
