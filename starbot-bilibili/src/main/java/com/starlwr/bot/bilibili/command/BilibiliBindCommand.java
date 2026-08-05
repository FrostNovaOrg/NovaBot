package com.starlwr.bot.bilibili.command;

import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.CommandReply;
import com.starlwr.bot.core.command.StarBotCommand;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.UserBindingService;
import com.starlwr.bot.core.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「绑定」命令
 * <p>
 * 把发送者的 QQ 号与其哔哩哔哩 uid 关联，个人数据查询据此知道你是谁。
 * <p>
 * 分两步：先查昵称让人确认，再由「确认绑定」落库。<b>这一步核对的是 uid 有没有打错，
 * 而不是账号归属</b>——没有接口能证明两个平台的账号属于同一人，
 * 详见 {@link UserBindingService} 的说明。
 */
@Slf4j
@StarBotComponent
public class BilibiliBindCommand implements StarBotCommand {
    /**
     * 待确认绑定的有效期。过期即失效，避免误确认到很久以前输入的 uid
     */
    static final Duration PENDING_TTL = Duration.ofMinutes(2);

    /**
     * 待确认的绑定，按「平台:会话:发送者」区分
     * <p>
     * 只存在内存里：这是个未完成的半步操作，重启后让人重新输一次 uid 才是对的，
     * 把它持久化反而会在重启后留下莫名其妙的待确认状态。
     */
    private final Map<String, Pending> pendings = new ConcurrentHashMap<>();

    private final BilibiliApiUtil api;

    private final UserBindingService bindings;

    @Autowired
    public BilibiliBindCommand(BilibiliApiUtil api, UserBindingService bindings) {
        this.api = api;
        this.bindings = bindings;
    }

    @Override
    public String name() {
        return "绑定";
    }

    @Override
    public String description() {
        return "绑定哔哩哔哩账号，用于查询个人数据";
    }

    @Override
    public String usage() {
        return "<哔哩哔哩 uid>";
    }

    @Override
    public String category() {
        return "账号绑定";
    }

    @Override
    public CommandReply execute(CommandContext context) {
        if (context.getSenderUid() == null) {
            return CommandReply.none();
        }

        String arg = context.arg(0);
        if (StringUtil.isBlank(arg)) {
            return CommandReply.of("请带上你的哔哩哔哩 uid，例如：绑定 500000004"
                    + "\nuid 在个人主页地址栏里，形如 space.bilibili.com/500000004");
        }

        long uid;
        try {
            uid = Long.parseLong(arg.trim());
        } catch (NumberFormatException e) {
            return CommandReply.of("「" + arg + "」不是有效的 uid，uid 是一串纯数字");
        }
        if (uid <= 0) {
            return CommandReply.of("uid 必须是正整数");
        }

        String uname;
        try {
            Up up = api.getUpInfoByUid(uid);
            uname = up.getUname();
        } catch (Exception e) {
            log.warn("绑定命令查询 uid {} 的信息失败: {}", uid, e.getMessage());
            return CommandReply.of("查不到 uid " + uid + " 对应的账号，请确认 uid 是否正确");
        }

        if (StringUtil.isBlank(uname)) {
            return CommandReply.of("查不到 uid " + uid + " 对应的账号，请确认 uid 是否正确");
        }

        pendings.put(key(context), new Pending(uid, uname, Instant.now()));
        return CommandReply.of("uid " + uid + " 对应的账号是「" + uname + "」"
                + "\n确认无误请回复「确认绑定」，" + PENDING_TTL.toMinutes() + " 分钟内有效");
    }

    /**
     * 取出并清除待确认的绑定，随后完成绑定
     * <p>
     * 由「确认绑定」调用。取出即清除，确认过一次就不会再被第二次确认。
     * @param context 执行上下文
     * @return 确认结果的回复
     */
    CommandReply confirm(CommandContext context) {
        Pending pending = pendings.remove(key(context));
        if (pending == null) {
            return CommandReply.of("没有待确认的绑定，请先发送「绑定 你的哔哩哔哩 uid」");
        }
        if (pending.expired()) {
            return CommandReply.of("待确认的绑定已超时，请重新发送「绑定 你的哔哩哔哩 uid」");
        }

        bindings.bind(context.getPlatform(), LivePlatform.BILIBILI.getName(), context.getSenderUid(), pending.uid());
        log.info("会话 {} 中 {} 绑定了哔哩哔哩账号 {}", context.getNum(), context.getSenderUid(), pending.uid());

        return CommandReply.of("已绑定「" + pending.uname() + "」（" + pending.uid() + "）"
                + "\n现在可以用「我的数据」查询你在直播间的互动数据了");
    }

    /**
     * 待确认的绑定所属的键
     */
    private String key(CommandContext context) {
        return context.getPlatform() + ":" + context.getNum() + ":" + context.getSenderUid();
    }

    /**
     * 一条待确认的绑定
     */
    private record Pending(long uid, String uname, Instant createdAt) {
        boolean expired() {
            return Instant.now().isAfter(createdAt.plus(PENDING_TTL));
        }
    }
}
