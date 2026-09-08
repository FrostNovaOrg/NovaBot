package com.starlwr.bot.core.handler;

/**
 * 旧名事件处理器接口，已由 {@link NovaEventHandler} 取代，下一发行版删
 * <p>
 * 旧名只是新接口的别名：全部成员都在 {@link NovaEventHandler} 里，
 * 旧实现无需改动即可继续工作；新代码请实现 {@link NovaEventHandler}。
 */
@Deprecated
public interface StarBotEventHandler extends NovaEventHandler {
}
