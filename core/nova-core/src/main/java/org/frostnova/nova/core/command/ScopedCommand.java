package org.frostnova.nova.core.command;

/** 在特定上下文中按完整消息识别；命中时优先于普通命令名和追问。 */
public interface ScopedCommand extends NovaCommand {
    /** 同一组说法在菜单中共用一行；空值使用普通命令行。 */
    default String menuSummary() { return null; }
    boolean matches(CommandContext context);
}
