package com.starlwr.bot.core.handler;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.event.StarBotExternalBaseEvent;
import com.starlwr.bot.core.model.PushMessage;
import org.springframework.stereotype.Component;

/**
 * StarBot 事件处理器接口，推送配置中配置的事件处理器实现均应实现此接口，并使用 {@link Component} 等注解注册至 Spring 容器中
 */
public interface StarBotEventHandler {
    /**
     * 处理事件
     * @param baseEvent 事件
     * @param pushMessage 推送消息
     */
    void handle(StarBotExternalBaseEvent baseEvent, PushMessage pushMessage);

    /**
     * 获取事件处理器处理的事件类型
     * @return 事件类型
     */
    Class<? extends StarBotExternalBaseEvent> getEventType();

    /**
     * 获取事件处理器默认参数
     * @return 默认参数
     */
    JSONObject getDefaultParams();
}
