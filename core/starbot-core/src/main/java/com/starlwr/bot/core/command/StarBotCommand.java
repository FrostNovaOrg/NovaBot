package com.starlwr.bot.core.command;

/**
 * 旧名聊天命令接口，已由 {@link NovaCommand} 取代，下一发行版删
 * <p>
 * 旧名只是新接口的别名：全部成员都在 {@link NovaCommand} 里，
 * 旧实现无需改动即可继续工作。实现本接口并注册为 Bean
 * （插件中用 {@code @NovaComponent}）即可被命令分发器发现。
 */
@Deprecated
public interface StarBotCommand extends NovaCommand {
}
