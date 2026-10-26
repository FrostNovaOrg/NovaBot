package org.frostnova.nova.core.handler;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.event.NovaExternalBaseEvent;
import org.frostnova.nova.core.model.PushMessage;
import org.springframework.stereotype.Component;

/** 仅作订阅入口，由定时服务投递纯文字小结。 */
@Component
public class SessionRecapPushHandler implements NovaEventHandler {
    public static final class SessionRecapEvent extends NovaExternalBaseEvent {
        private SessionRecapEvent() {}
    }
    @Override public void handle(NovaExternalBaseEvent event, PushMessage message) {}
    @Override public Class<? extends NovaExternalBaseEvent> getEventType() { return SessionRecapEvent.class; }
    @Override public JSONObject getDefaultParams() { return new JSONObject(); }
    @Override public boolean defaultEnabled() { return false; }
    @Override public String displayName() { return "下播小结（私聊）"; }
    @Override public int displayOrder() { return 61; }
    @Override public String description() { return "真下播 30 分钟后私聊发给主播本人；静音时段顺延"; }
}
