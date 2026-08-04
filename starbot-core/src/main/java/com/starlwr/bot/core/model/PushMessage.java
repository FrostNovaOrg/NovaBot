package com.starlwr.bot.core.model;

import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.annotation.JSONField;
import com.starlwr.bot.core.event.StarBotExternalBaseEvent;
import com.starlwr.bot.core.handler.StarBotEventHandler;
import lombok.Getter;
import lombok.Setter;

import java.util.Objects;

/**
 * 推送消息
 */
@Getter
@Setter
public class PushMessage {
    /**
     * 关联的推送目标
     * <p>
     * 反向引用，序列化时须跳过，否则与 {@link PushTarget#getMessages()} 构成环
     */
    @JSONField(serialize = false)
    private PushTarget target;

    /**
     * 事件处理器全类名
     */
    private String handler;

    /**
     * 事件处理器实例，自动根据事件处理器解析
     */
    @JSONField(serialize = false)
    private StarBotEventHandler handlerInstance;

    /**
     * 事件处理器处理的事件类型，自动根据事件处理器解析
     */
    @JSONField(serialize = false)
    private Class<? extends StarBotExternalBaseEvent> eventClass;

    /**
     * JSON 格式推送参数
     */
    private String params;

    /**
     * 推送参数解析后的 JSON 对象，自动根据 params 参数解析
     */
    @JSONField(serialize = false)
    private JSONObject paramsJsonObject;

    /**
     * 是否启用
     */
    private Boolean enabled;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PushMessage that)) return false;
        return Objects.equals(handler, that.handler) && Objects.equals(params, that.params);
    }

    @Override
    public int hashCode() {
        return Objects.hash(handler, params);
    }

    @Override
    public String toString() {
        return "PushMessage(" + "handler=" + handler + ", params=" + params + ", enabled=" + enabled + ")";
    }
}
