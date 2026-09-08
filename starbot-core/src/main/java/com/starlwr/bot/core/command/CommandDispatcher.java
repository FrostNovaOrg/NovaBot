package com.starlwr.bot.core.command;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.event.remote.StarBotRemoteMessageEvent;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import com.starlwr.bot.core.timeline.TimelineEvent;
import com.starlwr.bot.core.timeline.TimelineEventType;
import com.starlwr.bot.core.timeline.TimelineWriter;
import com.starlwr.bot.core.lang.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Clock;
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
 *         共用同一份冷却；分开算的话，连发无效消息就能绕过它。
 *         <b>唯一的例外是被认领的追问应答</b>，理由见 {@link CommandFollowUp}。</li>
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

    /**
     * 记进时间线的命令名最多留几个字
     */
    private static final int NAME_IN_RECORD = 16;

    private final ObjectProvider<StarBotCommand> commands;

    /**
     * 追问应答的认领方。没有任何实现时，认不出的消息照旧回菜单
     */
    private final ObjectProvider<CommandFollowUp> followUps;

    private final CommandSettingsService settings;

    private final AbstractDataSource dataSource;

    private final StarBotMessageSender sender;

    private final StarBotCoreProperties properties;

    /**
     * 各会话最近一次执行命令的时间
     */
    private final Map<String, Instant> lastExecuted = new ConcurrentHashMap<>();

    /**
     * 冷却所用的时钟
     * <p>
     * 冷却与追问的有效期是同一条时间轴上的两件事：机器人问完「是哪一位」，
     * 那份追问两分钟后作废，而<b>作废之后还问不问得出来</b>只有把时间推过去才量得到。
     * 这里读真钟的话，推时间的那一端推不动它，第二次提问会撞在 3 秒冷却上——
     * 现象与「追问表没清干净」一模一样，而那正是要量的那一件。
     */
    private final Clock clock;

    /**
     * 事件时间线
     * <p>
     * 「我刚才在群里说的那句它怎么没理我」——这个问题此前只能翻运行日志回答，
     * 而其中最常见的那一种（撞在冷却上）默认级别下根本不写。
     */
    private final TimelineWriter timeline;

    @Autowired
    public CommandDispatcher(ObjectProvider<StarBotCommand> commands, ObjectProvider<CommandFollowUp> followUps,
                             CommandSettingsService settings, AbstractDataSource dataSource,
                             StarBotMessageSender sender, StarBotCoreProperties properties,
                             TimelineWriter timeline) {
        this(commands, followUps, settings, dataSource, sender, properties, timeline, Clock.systemDefaultZone());
    }

    /**
     * 指定时钟的构造方法，供把时间推着走的测试使用
     *
     * @see #clock 为什么这把钟必须能换
     */
    public CommandDispatcher(ObjectProvider<StarBotCommand> commands, ObjectProvider<CommandFollowUp> followUps,
                             CommandSettingsService settings, AbstractDataSource dataSource,
                             StarBotMessageSender sender, StarBotCoreProperties properties,
                             TimelineWriter timeline, Clock clock) {
        this.commands = commands;
        this.followUps = followUps;
        this.settings = settings;
        this.dataSource = dataSource;
        this.sender = sender;
        this.properties = properties;
        this.timeline = timeline;
        this.clock = clock;
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

        // 私聊里撞上仅限群聊的命令：按认不出处理，回菜单。
        // 菜单此时已按会话过滤，不会把这条用不了的命令再推荐一遍
        if (command != null && command.groupOnly() && !group) {
            command = null;
        }

        // 认不出的消息先问一句「有谁在等这个答案吗」。这一步必须排在冷却之前：
        // 机器人刚问完「是哪一位」，答案却撞在它自己的冷却上，现象是「回了个数字，没反应」
        CommandFollowUp.Claimed claimed = command == null ? claim(event, type, name, parts) : null;
        if (claimed != null && claimed.reply() != null) {
            reply(event, type, claimed.reply().content());
            return;
        }
        if (claimed != null) {
            command = find(claimed.command());
            parts = new ArrayList<>(claimed.args());
        }

        // 以下四条出声的路径共用这一份冷却；被认领的应答已在上面走掉
        if (claimed == null && !acquireCooldown(event, type, name)) {
            return;
        }

        if (command == null) {
            // 已经 @ 了（或在私聊里），这话就是对机器人说的。认不出来时回菜单，
            // 顶行写清怎么用——沉默只会让人换个说法再试一遍
            timeline.record(TimelineEvent.of(TimelineEventType.COMMAND_UNKNOWN, TimelineEvent.Level.INFO)
                    .channel(describe(type, event.getNum()))
                    .text("认不出「" + shorten(name) + "」，已回菜单")
                    .detail("command", shorten(name))
                    .build());

            StarBotCommand menu = find(MENU_COMMAND_NAME);
            if (menu != null) {
                run(menu, event, type, List.of(), false);
            }
            return;
        }

        if (command.disableable() && settings.isDisabled(event.getPlatform(), event.getNum(), command.name())) {
            // 关掉的命令也要回一句：沉默与「机器人坏了」在使用者眼里长得一样。
            // 「在哪儿」与命令自己说不出主播时那一句同一处取词，见 CommandContext#here
            reply(event, type, CommandContext.here(type) + "已关闭「" + command.name() + "」命令");
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

        // 记在成功那一支上：抛了异常的那一次群里什么都没有，
        // 把它也记成「执行」会让「这条命令到底管不管用」再也查不出来
        if (run(command, event, type, List.copyOf(parts), admin)) {
            timeline.record(TimelineEvent.of(TimelineEventType.COMMAND_EXECUTED, TimelineEvent.Level.INFO)
                    .channel(describe(type, event.getNum()))
                    .text("执行了「" + command.name() + "」")
                    .detail("command", command.name())
                    .detail("admin", admin ? "是" : "否")
                    .build());
        }
    }

    /**
     * 问一圈有没有人认领这条认不出的消息
     * <p>
     * 认领方按 Bean 的顺序依次问过去，头一个认领的说了算——同一条消息被两处认领本就是个错误，
     * 而在这里挑一个「更合适的」只会让那个错误更难被发现。
     * @return 认领结果，无人认领时为 null
     */
    private CommandFollowUp.Claimed claim(StarBotRemoteMessageEvent event, PushTargetType type,
                                          String name, List<String> args) {
        if (name.isEmpty()) {
            return null;
        }

        CommandContext context = new CommandContext(event.getPlatform(), type, event.getNum(),
                event.getSenderUid(), name, List.copyOf(args), event.getText());
        for (CommandFollowUp followUp : followUps) {
            try {
                CommandFollowUp.Claimed claimed = followUp.claim(context);
                if (claimed != null) {
                    return claimed;
                }
            } catch (Exception e) {
                // 认领方出错不该让这条消息连菜单都收不到
                log.error("认领消息 {} 时发生异常", name, e);
            }
        }
        return null;
    }

    /**
     * 取本次出声所需的冷却额度
     * <p>
     * 冷却按会话计，<b>会话类型也进键</b>：群号与好友账号取自两个互不相干的号段，
     * 只按号码算的话，两者撞号时会互相消耗对方的冷却。
     * @param event 消息事件
     * @param type 会话类型，记时间线时用来说清是哪个群
     * @param name 命令名，仅用于日志
     * @return 是否放行
     */
    private boolean acquireCooldown(StarBotRemoteMessageEvent event, PushTargetType type, String name) {
        String key = event.getPlatform() + ":" + event.getMessageType() + ":" + event.getNum();
        Instant now = clock.instant();
        Instant last = lastExecuted.get(key);
        if (last != null && now.isBefore(last.plus(COOLDOWN))) {
            log.debug("会话 {} 处于冷却期, 已忽略: {}", event.getNum(), name);
            // 冷却掉的那一句在屏幕上什么痕迹都没有：机器人不出声，日志是 debug 级别。
            // 「连着问了两遍，第二遍没反应」正是这一条答的问题
            timeline.record(TimelineEvent.of(TimelineEventType.COMMAND_COOLED_DOWN, TimelineEvent.Level.INFO)
                    .channel(describe(type, event.getNum()))
                    .text("冷却期内，「" + shorten(name) + "」这一句没有回")
                    .detail("command", shorten(name))
                    .build());
            return false;
        }
        lastExecuted.put(key, now);
        return true;
    }

    /**
     * 描述会话，形如「群 12345」，与推送记录里那一栏同一套写法
     */
    private static String describe(PushTargetType type, Long num) {
        return type.getStr() + " " + num;
    }

    /**
     * 把使用者打进来的那串字截短
     * <p>
     * 命令名取自消息正文的第一段，长度不受任何约束——有人往群里粘一整段话，
     * 整段都会被当成「命令名」记进时间线的每一行里。截短的是<b>记录</b>，
     * 不是判定：认不认得出这条命令仍按原样比。
     */
    private static String shorten(String name) {
        return name.length() <= NAME_IN_RECORD ? name : name.substring(0, NAME_IN_RECORD) + "…";
    }

    /**
     * 执行命令并把回复发回原会话
     * <p>
     * 返回值答的是「跑完了没抛」，供调用方决定要不要记一条时间线。
     * 回菜单那一支<b>刻意不看这个返回值</b>：那一次已经由
     * {@link TimelineEventType#COMMAND_UNKNOWN} 记过，再记一条「执行了菜单」
     * 会让日志页上认不出的命令各占两行，而后一行看起来像是命令跑成了。
     * @return 执行过程中没有抛异常
     */
    private boolean run(StarBotCommand command, StarBotRemoteMessageEvent event, PushTargetType type,
                        List<String> args, boolean admin) {
        CommandContext context = new CommandContext(event.getPlatform(), type, event.getNum(),
                event.getSenderUid(), command.name(), args, event.getText(), admin);

        try {
            CommandReply commandReply = command.execute(context);
            if (commandReply != null && commandReply.hasContent()) {
                reply(event, type, commandReply.content());
            }
            return true;
        } catch (Exception e) {
            // 一个命令出错不应影响其他命令，也不该把异常细节回给群里
            log.error("执行命令 {} 时发生异常", command.name(), e);
            return false;
        }
    }

    /**
     * 向消息来源的会话回一条消息
     */
    private void reply(StarBotRemoteMessageEvent event, PushTargetType type, String content) {
        Message.create(event.getPlatform(), type, event.getNum(), content).forEach(message -> {
            // 标成回复而不是推送：这一条是有人先开口才有的，凡是「只对主动推送成立」的事都不该算上它
            message.setReply(true);
            sender.send(message);
        });
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
