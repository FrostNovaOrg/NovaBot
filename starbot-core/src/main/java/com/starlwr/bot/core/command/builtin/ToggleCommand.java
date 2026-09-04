package com.starlwr.bot.core.command.builtin;

import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.CommandDispatcher;
import com.starlwr.bot.core.command.CommandReply;
import com.starlwr.bot.core.command.CommandSettingsService;
import com.starlwr.bot.core.command.StarBotCommand;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

import java.util.function.Predicate;

/**
 * 「启用命令」与「禁用命令」的共同实现
 * <p>
 * 两者除方向外逻辑完全一致，合成一个基类避免两份几乎相同的代码各自演化。
 */
@Slf4j
public abstract class ToggleCommand implements StarBotCommand {
    /**
     * 分发器反过来依赖全部命令，用 ObjectProvider 延迟取用以打破循环依赖
     */
    protected final ObjectProvider<CommandDispatcher> dispatcher;

    protected final CommandSettingsService settings;

    protected ToggleCommand(ObjectProvider<CommandDispatcher> dispatcher, CommandSettingsService settings) {
        this.dispatcher = dispatcher;
        this.settings = settings;
    }

    /**
     * 本命令的方向：true 为启用，false 为禁用
     * @return 方向
     */
    protected abstract boolean enabling();

    @Override
    public String usage() {
        return "<命令名>";
    }

    @Override
    public boolean disableable() {
        // 「启用命令」被禁用后就没有任何途径把命令开回来了；
        // 「禁用命令」同样保持不可禁用，与之对称，避免使用者困惑于两者行为不一致
        return false;
    }

    @Override
    public boolean requiresAdmin() {
        // 改的是**全群**的可用功能，不是自己的偏好。放任何人执行，
        // 等于把机器人的开关交给了路过的人
        return true;
    }

    @Override
    public CommandReply execute(CommandContext context) {
        String target = context.arg(0);
        if (target == null) {
            return CommandReply.of("请指明命令名，例如：" + name() + " 直播报告");
        }

        StarBotCommand command = resolve(context, target);
        if (command == null) {
            // 三种情形要分得开：能力没配好、这个会话里用不上、名字打错了。
            // 说成同一句的话，前两种的人会一直在改自己的措辞，而他要动的根本不是措辞
            StarBotCommand unavailable = first(target, item -> !item.available());
            if (unavailable != null) {
                return CommandReply.of("「" + unavailable.name() + "」在本机没开，不用开关它，"
                        + "把它要的能力配好之后会自动回来");
            }
            StarBotCommand hidden = first(target, item -> !item.availableIn(context));
            if (hidden != null) {
                return CommandReply.of("「" + hidden.name() + "」在" + context.here() + "用不上，"
                        + "菜单里也没有它，不用开关它");
            }
            return CommandReply.of("没有名为「" + target + "」的命令，发送「菜单」可查看全部命令");
        }
        if (!command.disableable()) {
            return CommandReply.of("「" + command.name() + "」不可开关");
        }

        boolean changed = enabling()
                ? settings.enable(context.getPlatform(), context.getNum(), command.name())
                : settings.disable(context.getPlatform(), context.getNum(), command.name());

        if (!changed) {
            return CommandReply.of("「" + command.name() + "」本来就是" + (enabling() ? "启用" : "禁用") + "状态");
        }

        // 改的是全群状态，留一条审计日志：事后才问得出「这功能什么时候被谁关掉的」
        log.info("{} 在会话 {} 中{}了命令 {}", context.getSenderUid(), context.getNum(),
                enabling() ? "启用" : "禁用", command.name());
        return CommandReply.of("已" + (enabling() ? "启用" : "禁用") + "「" + command.name() + "」");
    }

    /**
     * 按命令名或别名找到目标命令，<b>只在这个会话里列得进菜单的那些里找</b>
     * <p>
     * 问的与「菜单」是同一问 {@link StarBotCommand#availableIn}，不是只问机器整体的
     * {@link StarBotCommand#available}：两处口径分开的那一版里，本群的开播通知
     * 配成「@全体成员」时「开播@我」菜单里没有、却仍开关得动，
     * 于是状态文件里留下一条界面上看得见、群里怎么也验证不了的记录。
     * <p>
     * 两种够不着的理由都不是使用者能靠改措辞解决的：能力配好、或本会话的配置改回来之后，
     * 那条命令自己就回菜单了，那才是他要做的那一件事。
     * @param context 执行上下文
     * @param name 命令名或别名
     */
    private StarBotCommand resolve(CommandContext context, String name) {
        return first(name, command -> command.availableIn(context));
    }

    /**
     * 在满足条件的命令里按命令名或别名找头一个
     * @param name 命令名或别名
     * @param filter 候选范围
     * @return 找到的命令，没有时为 null
     */
    private StarBotCommand first(String name, Predicate<StarBotCommand> filter) {
        CommandDispatcher instance = dispatcher.getIfAvailable();
        if (instance == null) {
            return null;
        }
        return instance.all().stream()
                .filter(filter)
                .filter(command -> command.name().equals(name) || command.aliases().contains(name))
                .findFirst()
                .orElse(null);
    }

    @Override
    public String category() {
        return "命令管理";
    }
}
