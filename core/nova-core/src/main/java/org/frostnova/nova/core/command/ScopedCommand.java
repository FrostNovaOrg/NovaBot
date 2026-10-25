package org.frostnova.nova.core.command;

/** 在特定上下文中按完整消息识别；命中时优先于普通命令名和追问。 */
public interface ScopedCommand extends NovaCommand {
    boolean matches(CommandContext context);
}
