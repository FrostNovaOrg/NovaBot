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

        StarBotCommand command = resolve(target);
        if (command == null) {
            // 「本机没开」与「没有这条命令」要分得开：前者去配一份存储就回来了，
            // 后者该去看菜单。说成同一句的话，能力没配好的那个人会一直在改自己的措辞
            StarBotCommand unavailable = first(target, item -> !item.available());
            if (unavailable != null) {
                return CommandReply.of("「" + unavailable.name() + "」在本机没开，不用开关它，"
                        + "把它要的能力配好之后会自动回来");
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
     * 按命令名或别名找到目标命令，<b>只在本机可用的那些里找</b>
     * <p>
     * 不可用说的是整台机器缺着这条命令要靠的能力（如累计数据要外部存储），
     * 那样的命令菜单里已经不列了，开关它同样不会有任何效果：关掉的是一条本来就用不了的命令，
     * 而这条记录留在状态文件里，界面上看得见、群里却怎么也验证不了。
     * 能力配好之后它自己就回来了，那才是使用者要做的那一件事。
     */
    private StarBotCommand resolve(String name) {
        return first(name, StarBotCommand::available);
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
