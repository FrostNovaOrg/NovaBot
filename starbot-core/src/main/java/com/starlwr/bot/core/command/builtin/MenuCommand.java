package com.starlwr.bot.core.command.builtin;

import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.CommandDispatcher;
import com.starlwr.bot.core.command.CommandReply;
import com.starlwr.bot.core.command.CommandSettingsService;
import com.starlwr.bot.core.command.StarBotCommand;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.util.StringUtil;
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
 */
@Component
public class MenuCommand implements StarBotCommand {
    /**
     * 分发器反过来依赖全部命令（含本命令），直接注入会形成循环依赖，
     * 因此用 ObjectProvider 延迟到调用时才取
     */
    private final ObjectProvider<CommandDispatcher> dispatcher;

    private final CommandSettingsService settings;

    private final StarBotCoreProperties properties;

    @Autowired
    public MenuCommand(ObjectProvider<CommandDispatcher> dispatcher, CommandSettingsService settings,
                       StarBotCoreProperties properties) {
        this.dispatcher = dispatcher;
        this.settings = settings;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "菜单";
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

        String prefix = StringUtil.isBlank(properties.getCommand().getPrefix()) ? "" : properties.getCommand().getPrefix();

        // 先按分类归拢再输出：命令一多，平铺的清单没人看得下去。
        // 分类的先后取「首次出现的顺序」——命令本身已按名称排序，因此这个顺序是稳定的，
        // 不会每次发菜单都换个模样
        Map<String, List<StarBotCommand>> grouped = new LinkedHashMap<>();
        for (StarBotCommand command : instance.all()) {
            if (command.disableable() && settings.isDisabled(context.getPlatform(), context.getNum(), command.name())) {
                continue;
            }
            grouped.computeIfAbsent(command.category(), key -> new ArrayList<>()).add(command);
        }

        StringBuilder text = new StringBuilder("可用命令：");
        for (Map.Entry<String, List<StarBotCommand>> entry : grouped.entrySet()) {
            text.append("\n\n【").append(entry.getKey()).append("】");
            for (StarBotCommand command : entry.getValue()) {
                text.append("\n").append(prefix).append(command.name());
                if (StringUtil.isNotBlank(command.usage())) {
                    text.append(" ").append(command.usage());
                }
                text.append(" — ").append(command.description());
            }
        }

        return CommandReply.of(text.toString());
    }

    @Override
    public String category() {
        return "命令管理";
    }
}
