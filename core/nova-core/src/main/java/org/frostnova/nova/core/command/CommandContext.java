package org.frostnova.nova.core.command;

import org.frostnova.nova.core.enums.PushTargetType;
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
     * 推送平台名，由适配器自报
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

    /**
     * 说给使用者听的「在哪儿」
     * <p>
     * 「本群没有配置这位主播」「本群已关闭这条命令」这类话出自互不相干的几处，
     * 回答的却是同一个问题。各写各的字面量时，改了一处的那天，
     * 同一个人在私聊里会同时听见「这里」和一句什么都不带的——而那一句本该也说「这里」。
     * <p>
     * 私聊说「本群」尤其误事：数据查询这几条命令在已配推送的好友会话里一样能用，
     * 听见「本群」的人会去群里找一份并不存在的配置。
     * @return 群聊为「本群」，私聊为「这里」
     */
    public String here() {
        return here(type);
    }

    /**
     * 同 {@link #here()}，供手头只有会话类型、还没有上下文的调用方使用
     * @param type 会话类型
     * @return 群聊为「本群」，私聊为「这里」
     */
    public static String here(PushTargetType type) {
        return PushTargetType.GROUP == type ? "本群" : "这里";
    }
}
