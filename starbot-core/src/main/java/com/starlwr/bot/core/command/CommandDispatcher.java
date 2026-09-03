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
import java.util.concurrent.ConcurrentHashMap;

/**
 * 命令分发器
 * <p>
 * 订阅 {@link StarBotRemoteMessageEvent}，解析出命令并路由到对应实现。
 * 命令实现只需注册为 Bean 即可被发现，核心不维护任何注册表。
 *
 * <h2>什么算一条命令</h2>
 * <ul>
 *     <li><b>群聊只认 @</b>：消息里 @ 了机器人本身才当命令看，@ 之后的正文就是命令与参数。
 *         没 @ 的消息一概不理——群里正常聊天时随口说到某个命令名，不该被机器人接话。</li>
 *     <li><b>私聊直呼命令名</b>：私聊没有旁人，不必先 @。</li>
 * </ul>
 *
 * <h2>四条横切约束在此统一处理</h2>
 * 每个命令各写一遍的话，写漏一处就是一次事故：
 * <ul>
 *     <li><b>只在已配置推送的会话开口</b>——机器人常同时在多个群，在无关群应答等同于打扰。
 *         这一条是硬规矩，命令<b>不能</b>把它放宽：未配置的会话连菜单都不回。</li>
 *     <li><b>认不出的命令回菜单</b>——已经 @ 了机器人（或在私聊里），说明这话就是对它说的，
 *         此时装作没听见比回一份菜单更让人困惑。</li>
 *     <li><b>被本会话关掉的命令回一句</b>——沉默会让人以为机器人坏了，反复重试。</li>
 *     <li><b>同会话冷却</b>——防止刷屏。回菜单、回「已关闭」、回「没权限」与执行命令
 *         共用同一份冷却；分开算的话，连发无效消息就能绕过它。</li>
 * </ul>
 */
@Slf4j
@Service
public class CommandDispatcher {
    /**
     * 「菜单」命令的命令名
     * <p>
     * 认不出命令时回的就是它。常量放在这里而不是命令实现里：分发器要按名字找到它，
     * 两处各写一个字面量的话，改名时只会有一处跟着改，而另一处安静地再也找不到菜单。
     */
    public static final String MENU_COMMAND_NAME = "菜单";

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
        if (event.getNum() == null) {
            return;
        }

        boolean group = "group".equals(event.getMessageType());
        PushTargetType type = group ? PushTargetType.GROUP : PushTargetType.FRIEND;

        // 群聊里没 @ 机器人的，一个字都不是说给它听的
        if (group && !event.isMentionsBot()) {
            return;
        }

        // 没配过推送的会话完全沉默——包括不回菜单。@ 一个不该在这儿说话的机器人，
        // 它就该继续不说话
        if (!isConfiguredTarget(event.getPlatform(), type, event.getNum())) {
            return;
        }

        List<String> parts = new ArrayList<>(Arrays.asList(
                StringUtil.isBlank(event.getText()) ? new String[0] : event.getText().trim().split("\\s+")));
        String name = parts.isEmpty() ? "" : parts.remove(0);
        StarBotCommand command = name.isEmpty() ? null : find(name);

        // 私聊里撞上仅限群聊的命令：保持沉默，不回菜单。
        // 回菜单会把这条用不了的命令再推荐一遍，使用者照着发第二次，还是同一份菜单
        if (command != null && command.groupOnly() && !group) {
            return;
        }

        // 以下四条出声的路径共用这一份冷却
        if (!acquireCooldown(event, name)) {
            return;
        }

        if (command == null) {
            // 已经 @ 了（或在私聊里），这话就是对机器人说的。认不出来时回菜单，
            // 顶行写清怎么用——沉默只会让人换个说法再试一遍
            StarBotCommand menu = find(MENU_COMMAND_NAME);
            if (menu != null) {
                run(menu, event, type, List.of(), false);
            }
            return;
        }

        if (command.disableable() && settings.isDisabled(event.getPlatform(), event.getNum(), command.name())) {
            // 关掉的命令也要回一句：沉默与「机器人坏了」在使用者眼里长得一样
            reply(event, type, (group ? "本群" : "") + "已关闭「" + command.name() + "」命令");
            return;
        }

        boolean admin = isAdmin(event);
        if (command.requiresAdmin() && !admin) {
            // 这里不沉默：使用者需要知道「命令存在但自己没权限」，否则只会反复重试。
            // 同时留一条审计日志——谁想动全群的开关，事后要查得到
            log.info("{} 在会话 {} 中尝试执行管理命令 {}, 但不是管理员", event.getSenderUid(), event.getNum(), command.name());
            reply(event, type, "「" + command.name() + "」仅群主、群管理员或超级管理员可用");
            return;
        }

        run(command, event, type, List.copyOf(parts), admin);
    }

    /**
     * 取本次出声所需的冷却额度
     * <p>
     * 冷却按会话计，<b>会话类型也进键</b>：群号与好友账号取自两个互不相干的号段，
     * 只按号码算的话，两者撞号时会互相消耗对方的冷却。
     * @param event 消息事件
     * @param name 命令名，仅用于日志
     * @return 是否放行
     */
    private boolean acquireCooldown(StarBotRemoteMessageEvent event, String name) {
        String key = event.getPlatform() + ":" + event.getMessageType() + ":" + event.getNum();
        Instant last = lastExecuted.get(key);
        if (last != null && Instant.now().isBefore(last.plus(COOLDOWN))) {
            log.debug("会话 {} 处于冷却期, 已忽略: {}", event.getNum(), name);
            return false;
        }
        lastExecuted.put(key, Instant.now());
        return true;
    }

    /**
     * 执行命令并把回复发回原会话
     */
    private void run(StarBotCommand command, StarBotRemoteMessageEvent event, PushTargetType type,
                     List<String> args, boolean admin) {
        CommandContext context = new CommandContext(event.getPlatform(), type, event.getNum(),
                event.getSenderUid(), command.name(), args, event.getText(), admin);

        try {
            CommandReply commandReply = command.execute(context);
            if (commandReply != null && commandReply.hasContent()) {
                reply(event, type, commandReply.content());
            }
        } catch (Exception e) {
            // 一个命令出错不应影响其他命令，也不该把异常细节回给群里
            log.error("执行命令 {} 时发生异常", command.name(), e);
        }
    }

    /**
     * 向消息来源的会话回一条消息
     */
    private void reply(StarBotRemoteMessageEvent event, PushTargetType type, String content) {
        Message.create(event.getPlatform(), type, event.getNum(), content).forEach(sender::send);
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
