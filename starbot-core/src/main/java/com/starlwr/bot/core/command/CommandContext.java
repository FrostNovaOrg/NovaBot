package com.starlwr.bot.core.command;

import com.starlwr.bot.core.enums.PushTargetType;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 命令执行上下文
 * <p>
 * 命令实现从这里取得「谁、在哪、说了什么」，并通过 {@link #getArgs()} 拿到参数。
 */
@Getter
@RequiredArgsConstructor
public class CommandContext {
    /**
     * 推送平台名，如 qq-onebot
     */
    private final String platform;

    /**
     * 会话类型：群聊或私聊
     */
    private final PushTargetType type;

    /**
     * 会话号：群聊为群号，私聊为对方账号
     */
    private final Long num;

    /**
     * 命令发送者账号
     */
    private final Long senderUid;

    /**
     * 命令名（已去掉前缀）
     */
    private final String command;

    /**
     * 命令参数，按空白切分，不含命令名本身
     */
    private final List<String> args;

    /**
     * 原始消息文本
     */
    private final String rawText;

    /**
     * 发送者是否为管理员
     * <p>
     * 群主、群管理员，或列在超级管理员名单里的账号。<b>判定统一由分发器完成</b>，
     * 命令实现只管用结论——规则若散落在各命令里，漏写一处就是一个洞。
     */
    private final boolean admin;

    /**
     * 视发送者为非管理员的构造方法，供测试与不关心权限的调用方使用
     */
    public CommandContext(String platform, PushTargetType type, Long num, Long senderUid,
                          String command, List<String> args, String rawText) {
        this(platform, type, num, senderUid, command, args, rawText, false);
    }

    /**
     * 取第 n 个参数
     * @param index 下标，从 0 开始
     * @return 参数，不存在时为 null
     */
    public String arg(int index) {
        return index >= 0 && index < args.size() ? args.get(index) : null;
    }

    /**
     * 是否为群聊会话
     * @return 是否群聊
     */
    public boolean isGroup() {
        return PushTargetType.GROUP == type;
    }
}
