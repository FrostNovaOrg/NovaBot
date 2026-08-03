package com.starlwr.bot.core.model;

import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.annotation.JSONField;
import com.starlwr.bot.core.event.StarBotExternalBaseEvent;
import com.starlwr.bot.core.handler.StarBotEventHandler;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.context.annotation.Profile;

import java.util.Objects;

/**
 * 推送消息
 */
@Profile("mysql")
@Getter
@Setter
@Entity
@Table(name = "starbot_push_message")
public class PushMessage {
    /**
     * ID，数据库类数据源使用
     */
    @Id
    @Column(name = "id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 关联的推送目标
     */
    @ManyToOne
    @JoinColumn(name = "target_id", referencedColumnName = "id")
    @JSONField(serialize = false)
    private PushTarget target;

    /**
     * 事件处理器全类名
     */
    @Column(name = "handler")
    private String handler;

    /**
     * 事件处理器实例，自动根据事件处理器解析
     */
    @Transient
    @JSONField(serialize = false)
    private StarBotEventHandler handlerInstance;

    /**
     * 事件处理器处理的事件类型，自动根据事件处理器解析
     */
    @Transient
    @JSONField(serialize = false)
    private Class<? extends StarBotExternalBaseEvent> eventClass;

    /**
     * JSON 格式推送参数
     */
    @Column(name = "params")
    private String params;

    /**
     * 推送参数解析后的 JSON 对象，自动根据 params 参数解析
     */
    @Transient
    @JSONField(serialize = false)
    private JSONObject paramsJsonObject;

    /**
     * 是否启用
     */
    @Column(name = "enabled")
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
