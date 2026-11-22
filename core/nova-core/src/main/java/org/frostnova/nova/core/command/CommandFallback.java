package org.frostnova.nova.core.command;

/** 接手私聊或群里 @ 机器人时无人认领的未知命令。 */
public interface CommandFallback extends NovaCommand {
    /**
     * 判断本命令是否接手。上下文使用本命令的名字，参数包含消息正文的所有词。
     * 接手后仍由分发器检查冷却、开关和权限，再执行命令。
     */
    boolean acceptsFallback(CommandContext context);
}
