package com.starlwr.bot.core.command.builtin;

import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.CommandDispatcher;
import com.starlwr.bot.core.command.CommandReply;
import com.starlwr.bot.core.command.CommandSettingsService;
import com.starlwr.bot.core.command.StarBotCommand;
import com.starlwr.bot.core.lang.StringUtil;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「菜单」命令
 * <p>
 * 列出当前会话可用的命令。已被禁用的命令不出现在列表里——列出一个用不了的命令
 * 只会让人反复尝试。
 * <p>
 * 认不出的消息也回这一份，因此<b>顶行必须写清怎么用</b>：走到这里的人多半正是
 * 没打对触发方式的那个人，只给他一串命令名，他还是不知道该怎么把它们发出去。
 */
@Component
public class MenuCommand implements StarBotCommand {
    /**
     * 分发器反过来依赖全部命令（含本命令），直接注入会形成循环依赖，
     * 因此用 ObjectProvider 延迟到调用时才取
     */
    private final ObjectProvider<CommandDispatcher> dispatcher;

    private final CommandSettingsService settings;

    @Autowired
    public MenuCommand(ObjectProvider<CommandDispatcher> dispatcher, CommandSettingsService settings) {
        this.dispatcher = dispatcher;
        this.settings = settings;
    }

    @Override
    public String name() {
        return CommandDispatcher.MENU_COMMAND_NAME;
    }

    @Override
    public List<String> aliases() {
        return List.of("帮助", "命令");
    }

    @Override
    public String description() {
        return "列出可用命令";
    }

    @Override
    public boolean groupOnly() {
        // 私聊里最该能直呼的就是它：顶行那句写的正是「私聊直接发命令名」。
        // 若它也仅限群聊，私聊里名字发对了反而没动静，发错了倒能收到菜单
        return false;
    }

    @Override
    public boolean disableable() {
        // 菜单被禁用后，使用者就再也看不到「启用命令」该怎么写了
        return false;
    }

    @Override
    public CommandReply execute(CommandContext context) {
        CommandDispatcher instance = dispatcher.getIfAvailable();
        if (instance == null) {
            return CommandReply.none();
        }

        // 先按分类归拢再输出：命令一多，平铺的清单没人看得下去。
        // 分类的先后取「首次出现的顺序」——命令本身已按名称排序，因此这个顺序是稳定的，
        // 不会每次发菜单都换个模样
        Map<String, List<StarBotCommand>> grouped = new LinkedHashMap<>();
        for (StarBotCommand command : instance.all()) {
            // 在这个会话里没有意义的命令一并不列：它与被禁用的命令在使用者那里是同一件事——
            // 列出来只会被照着发一遍，然后收到一句拒绝。问的是带会话的那一问，
            // 它默认就转交「本机能不能用」，因此本机没开的能力照样会被这一句挡下
            if (!command.availableIn(context)) {
                continue;
            }
            if (command.disableable() && settings.isDisabled(context.getPlatform(), context.getNum(), command.name())) {
                continue;
            }
            grouped.computeIfAbsent(command.category(), key -> new ArrayList<>()).add(command);
        }

        StringBuilder text = new StringBuilder(usageLine(context)).append("\n\n可用命令：");
        for (Map.Entry<String, List<StarBotCommand>> entry : grouped.entrySet()) {
            text.append("\n\n【").append(entry.getKey()).append("】");
            for (StarBotCommand command : entry.getValue()) {
                text.append("\n").append(command.name());
                if (StringUtil.isNotBlank(command.usage())) {
                    text.append(" ").append(command.usage());
                }
                text.append(" — ").append(command.description());

                // 补充说明跟在同一行末尾：它说的正是这一条命令，另起一行会让人以为是下一条
                String note = command.menuNote(context);
                if (StringUtil.isNotBlank(note)) {
                    text.append("（").append(note).append("）");
                }
            }
        }

        return CommandReply.of(text.toString());
    }

    /**
     * 顶行的使用方法
     * <p>
     * 群聊与私聊的触发方式不同，这一行也就得跟着会话走：在群里写「直接发命令名」
     * 会让人照做，然后什么也不会发生。
     * @param context 执行上下文
     * @return 使用方法
     */
    private String usageLine(CommandContext context) {
        return context.isGroup()
                ? "用法：@ 我，后面接命令名，例如「" + name() + "」。"
                : "用法：直接发命令名，例如「" + name() + "」。";
    }

    @Override
    public String category() {
        return "命令管理";
    }
}
