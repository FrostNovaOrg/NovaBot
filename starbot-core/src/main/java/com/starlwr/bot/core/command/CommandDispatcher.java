package com.starlwr.bot.core.command;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.event.remote.StarBotRemoteMessageEvent;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import com.starlwr.bot.core.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 命令分发器
 * <p>
 * 订阅 {@link StarBotRemoteMessageEvent}，解析出命令并路由到对应实现。
 * 命令实现只需注册为 Bean 即可被发现，核心不维护任何注册表。
 * <p>
 * 三条横切约束在此统一处理，避免每个命令各写一遍、写漏一处就成事故：
 * <ul>
 *     <li><b>只在已配置推送的会话响应</b>——机器人常同时在多个群，在无关群应答等同于打扰</li>
 *     <li><b>未知命令保持沉默</b>——群聊里正常说话不该被机器人纠正「无此命令」</li>
 *     <li><b>同会话冷却</b>——防止刷屏</li>
 * </ul>
 */
@Slf4j
@Service
public class CommandDispatcher {
    /**
     * 同一会话的命令冷却时间
     */
    private static final Duration COOLDOWN = Duration.ofSeconds(3);

    private final ObjectProvider<StarBotCommand> commands;

    private final CommandSettingsService settings;

    private final AbstractDataSource dataSource;

    private final StarBotMessageSender sender;

    private final StarBotCoreProperties properties;

    /**
     * 各会话最近一次执行命令的时间
     */
    private final Map<String, Instant> lastExecuted = new ConcurrentHashMap<>();

    @Autowired
    public CommandDispatcher(ObjectProvider<StarBotCommand> commands, CommandSettingsService settings,
                             AbstractDataSource dataSource, StarBotMessageSender sender,
                             StarBotCoreProperties properties) {
        this.commands = commands;
        this.settings = settings;
        this.dataSource = dataSource;
        this.sender = sender;
        this.properties = properties;
    }

    @EventListener(StarBotRemoteMessageEvent.class)
    public void onRemoteMessage(StarBotRemoteMessageEvent event) {
        if (event.getNum() == null || StringUtil.isBlank(event.getText())) {
            return;
        }

        String text = event.getText().trim();
        String prefix = Optional.ofNullable(properties.getCommand().getPrefix()).orElse("");
        if (!prefix.isEmpty()) {
            if (!text.startsWith(prefix)) {
                return;
            }
            text = text.substring(prefix.length()).trim();
        }

        List<String> parts = new ArrayList<>(Arrays.asList(text.split("\\s+")));
        if (parts.isEmpty() || parts.get(0).isEmpty()) {
            return;
        }

        String name = parts.remove(0);
        StarBotCommand command = find(name);
        if (command == null) {
            // 未知命令一律沉默：群里正常聊天时随口说到某个词，不该被机器人纠正
            return;
        }

        PushTargetType type = "group".equals(event.getMessageType()) ? PushTargetType.GROUP : PushTargetType.FRIEND;
        if (command.groupOnly() && PushTargetType.GROUP != type) {
            return;
        }

        if (command.requiresConfiguredTarget() && !isConfiguredTarget(event.getPlatform(), type, event.getNum())) {
            return;
        }

        if (command.disableable() && settings.isDisabled(event.getPlatform(), event.getNum(), command.name())) {
            return;
        }

        String cooldownKey = event.getPlatform() + ":" + event.getNum();
        Instant last = lastExecuted.get(cooldownKey);
        if (last != null && Instant.now().isBefore(last.plus(COOLDOWN))) {
            log.debug("会话 {} 的命令处于冷却期, 已忽略: {}", event.getNum(), name);
            return;
        }
        lastExecuted.put(cooldownKey, Instant.now());

        boolean admin = isAdmin(event);
        if (command.requiresAdmin() && !admin) {
            // 这里不沉默：使用者需要知道「命令存在但自己没权限」，否则只会反复重试。
            // 同时留一条审计日志——谁想动全群的开关，事后要查得到
            log.info("{} 在会话 {} 中尝试执行管理命令 {}, 但不是管理员", event.getSenderUid(), event.getNum(), command.name());
            Message.create(event.getPlatform(), type, event.getNum(),
                    "「" + command.name() + "」仅群主、群管理员或超级管理员可用").forEach(sender::send);
            return;
        }

        CommandContext context = new CommandContext(event.getPlatform(), type, event.getNum(),
                event.getSenderUid(), command.name(), List.copyOf(parts), event.getText(), admin);

        try {
            CommandReply reply = command.execute(context);
            if (reply != null && reply.hasContent()) {
                Message.create(event.getPlatform(), type, event.getNum(), reply.content()).forEach(sender::send);
            }
        } catch (Exception e) {
            // 一个命令出错不应影响其他命令，也不该把异常细节回给群里
            log.error("执行命令 {} 时发生异常", command.name(), e);
        }
    }

    /**
     * 判断消息发送者是否为管理员
     * <p>
     * 两条来源：会话中的角色（群主、群管理员），以及配置里的超级管理员名单。
     * 后者不依赖群角色——机器人的主人未必是每个群的管理员。
     * <p>
     * <b>私聊一律不算管理员</b>：私聊没有群角色，若在此放行，任何人私聊机器人
     * 都能改动群里的命令开关。
     */
    private boolean isAdmin(StarBotRemoteMessageEvent event) {
        Long senderUid = event.getSenderUid();
        if (senderUid == null) {
            return false;
        }

        List<Long> admins = properties.getCommand().getAdmins();
        if (admins != null && admins.contains(senderUid)) {
            return true;
        }

        String role = event.getSenderRole();
        return "owner".equalsIgnoreCase(role) || "admin".equalsIgnoreCase(role);
    }

    /**
     * 按命令名或别名查找命令
     */
    private StarBotCommand find(String name) {
        for (StarBotCommand command : commands) {
            if (command.name().equals(name) || command.aliases().contains(name)) {
                return command;
            }
        }
        return null;
    }

    /**
     * 判断会话是否已配置本平台的推送
     */
    private boolean isConfiguredTarget(String platform, PushTargetType type, Long num) {
        for (PushUser user : dataSource.getAllUsers()) {
            if (Boolean.FALSE.equals(user.getEnabled())) {
                continue;
            }
            for (PushTarget target : user.getTargets()) {
                if (Boolean.FALSE.equals(target.getEnabled())) {
                    continue;
                }
                if (platform.equals(target.getPlatform()) && type == target.getType() && num.equals(target.getNum())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 列出全部已注册命令，按命令名排序
     * @return 命令列表
     */
    public List<StarBotCommand> all() {
        Map<String, StarBotCommand> byName = new LinkedHashMap<>();
        commands.orderedStream().forEach(command -> byName.putIfAbsent(command.name(), command));
        List<StarBotCommand> result = new ArrayList<>(byName.values());
        result.sort((a, b) -> a.name().compareTo(b.name()));
        return result;
    }
}
