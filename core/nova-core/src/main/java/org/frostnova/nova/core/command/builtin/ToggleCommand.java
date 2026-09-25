package org.frostnova.nova.core.command.builtin;

import org.frostnova.nova.core.command.CommandContext;
import org.frostnova.nova.core.command.CommandDispatcher;
import org.frostnova.nova.core.command.CommandReply;
import org.frostnova.nova.core.command.CommandSettingsService;
import org.frostnova.nova.core.command.NovaCommand;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 「启用命令」与「禁用命令」的共同实现
 * <p>
 * 两者除方向外逻辑完全一致，合成一个基类避免两份几乎相同的代码各自演化。
 */
@Slf4j
public abstract class ToggleCommand implements NovaCommand {
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

        // 「这一句怎么打的」决定了它落到哪个用法上：「数据排行榜 总」与旧名「总数据排行榜」
        // 是同一个用法，认的是带不带「总」，不是命令正名。开关、可用与否都问这一份上下文
        List<String> rest = context.getArgs().size() > 1
                ? List.copyOf(context.getArgs().subList(1, context.getArgs().size()))
                : List.of();
        CommandContext probe = new CommandContext(context.getPlatform(), context.getType(), context.getNum(),
                context.getSenderUid(), target, rest, context.getRawText());

        NovaCommand command = resolve(probe, target);
        if (command == null) {
            // 三种情形要分得开：能力没配好、这个会话里用不上、名字打错了。
            // 说成同一句的话，前两种的人会一直在改自己的措辞，而他要动的根本不是措辞。
            // 报错里写打出来的那个名字，不写命令正名——「禁用命令 取消开播@我」若回「开播@我」，
            // 他会以为自己打错了名字
            // 报的是**这一句落到的那一格**，不是打出来的头一个词：「禁用命令 直播间数据 总」
            // 问的是累计那一半，回「直播间数据在本机没开」会把人指向还开着的那一半，
            // 他会以为整条命令都没配好，而要配的是累计数据这一件事
            NovaCommand unavailable = first(target, item -> !item.availableFor(probe));
            if (unavailable != null) {
                return CommandReply.of(CommandContext.quoted(asked(unavailable, probe, target))
                        + "在本机没开，不用开关它，"
                        + "把它要的能力配好之后会自动回来");
            }
            NovaCommand hidden = first(target, item -> !item.availableIn(probe));
            if (hidden != null) {
                return CommandReply.of(CommandContext.quoted(asked(hidden, probe, target))
                        + "在" + probe.here() + "用不上，"
                        + "菜单里也没有它，不用开关它");
            }
            return CommandReply.of("没有名为「" + target + "」的命令，发送「菜单」可查看全部命令");
        }
        if (!command.disableable()) {
            return CommandReply.of("「" + command.name() + "」不可开关");
        }

        List<String> keys = command.usageKeys(probe);
        if (keys.isEmpty()) {
            return CommandReply.of("「" + target + "」没有可开关的用法");
        }

        boolean changed = false;
        for (String key : keys) {
            boolean one = enabling()
                    ? settings.enable(context.getPlatform(), context.getNum(), key)
                    : settings.disable(context.getPlatform(), context.getNum(), key);
            changed |= one;
        }

        String joined = CommandContext.quoted(keys);
        if (!changed) {
            return CommandReply.of(joined + "本来就是" + (enabling() ? "启用" : "禁用") + "状态");
        }

        // 改的是全群状态，留一条审计日志：事后才问得出「这功能什么时候被谁关掉的」
        log.info("{} 在会话 {} 中{}了用法 {}", context.getSenderUid(), context.getNum(),
                enabling() ? "启用" : "禁用", keys);
        return CommandReply.of("已" + (enabling() ? "启用" : "禁用") + joined);
    }

    /**
     * 这一句落到哪几格，说不出时退回打出来的那个名字
     * <p>
     * 认的是「怎么打的」那一份上下文：「禁用命令 直播间数据 总」落到累计那一格，
     * 报「直播间数据」会把人指向还开着的那一半。
     * @param command 目标命令
     * @param probe 按打出来的名字建的上下文
     * @param typed 打出来的那个名字
     * @return 这一句落到的用法名
     */
    private List<String> asked(NovaCommand command, CommandContext probe, String typed) {
        List<String> keys = command.usageKeys(probe);
        return keys.isEmpty() ? List.of(typed) : keys;
    }

    /**
     * 按命令名或别名找到目标命令，<b>只在这个会话里列得进菜单的那些里找</b>
     * <p>
     * 问的与「菜单」是同一问 {@link NovaCommand#availableIn}，不是只问机器整体的
     * {@link NovaCommand#available}：两处口径分开的那一版里，本群的开播通知
     * 配成「@全体成员」时「开播@我」菜单里没有、却仍开关得动，
     * 于是状态文件里留下一条界面上看得见、群里怎么也验证不了的记录。
     * 传进来的上下文按<b>打出来的那个名字</b>建：同一句里带不带「总」是两个用法。
     * <p>
     * 两种够不着的理由都不是使用者能靠改措辞解决的：能力配好、或本会话的配置改回来之后，
     * 那条命令自己就回菜单了，那才是他要做的那一件事。
     * @param probe 按打出来的名字建的上下文
     * @param name 命令名或别名
     */
    private NovaCommand resolve(CommandContext probe, String name) {
        return first(name, command -> command.availableIn(probe));
    }

    /**
     * 在满足条件的命令里按命令名或别名找头一个
     * @param name 命令名或别名
     * @param filter 候选范围
     * @return 找到的命令，没有时为 null
     */
    private NovaCommand first(String name, Predicate<NovaCommand> filter) {
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
