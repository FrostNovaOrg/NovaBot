package com.starlwr.bot.core.model;

import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.annotation.JSONField;
import com.starlwr.bot.core.event.NovaExternalBaseEvent;
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
     * <p>
     * 类型是那个空接口而不是推送侧的处理器接口：本模型只负责把实例存住，一次也不调它。
     * 真要用它的推送侧自己知道该把它当成什么。
     */
    @JSONField(serialize = false)
    private PushMessageHandler handlerInstance;

    /**
     * 事件处理器处理的事件类型，自动根据事件处理器解析
     */
    @JSONField(serialize = false)
    private Class<? extends NovaExternalBaseEvent> eventClass;

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

    /**
     * 这条推送实际用上的处理器全类名
     * <p>
     * 与 {@link #getHandler()} 只在一种情形下不同：配置里写的是<b>旧名字</b>。
     * 处理器解析走的是别名回落，旧名认得出；而拿 {@code handler} 这个字面串去比
     * 「这条推送是不是那一类通知」的地方认不出来，于是升级之后那些判断一律落空——
     * 不报错，只是相关的命令与联动安静地当作「本群没配过这类推送」。
     * <p>
     * 还没解析过（或压根认不出来）时退回配置里写的那一串：那一刻本来就无从知道
     * 它对应哪个类，退回原串至少与解析之前的行为一致。
     * @return 解析出实例时取实例的类名，否则取配置里写的那一串
     */
    public String handlerClassName() {
        return handlerInstance == null ? handler : handlerInstance.getClass().getName();
    }

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
