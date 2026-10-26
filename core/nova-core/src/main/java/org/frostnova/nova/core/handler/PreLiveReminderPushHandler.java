package org.frostnova.nova.core.handler;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.event.NovaExternalBaseEvent;
import org.frostnova.nova.core.model.PushMessage;
import org.springframework.stereotype.Component;

/** 仅作订阅入口，由定时服务投递纯文字提醒。 */
@Component
public class PreLiveReminderPushHandler implements NovaEventHandler {
    public static final class PreLiveReminderEvent extends NovaExternalBaseEvent {
        private PreLiveReminderEvent() {}
    }
    @Override public void handle(NovaExternalBaseEvent event, PushMessage message) {}
    @Override public Class<? extends NovaExternalBaseEvent> getEventType() { return PreLiveReminderEvent.class; }
    @Override public JSONObject getDefaultParams() { return new JSONObject(); }
    @Override public boolean defaultEnabled() { return false; }
    @Override public String displayName() { return "开播前提醒（私聊）"; }
    @Override public int displayOrder() { return 62; }
    @Override public String description() { return "惯常开播前 60 分钟私聊提醒主播本人留意观众；静音时不发"; }
}
