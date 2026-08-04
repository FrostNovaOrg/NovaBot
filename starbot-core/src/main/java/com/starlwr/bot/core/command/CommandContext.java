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
