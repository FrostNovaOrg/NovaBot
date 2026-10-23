package org.frostnova.nova.core.handler;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.event.NovaExternalBaseEvent;
import org.frostnova.nova.core.model.PushMessage;
import org.frostnova.nova.core.model.HandlerOption;
import org.frostnova.nova.core.service.WeeklyReportLayout;
import org.springframework.stereotype.Component;

import java.util.List;

/** 订阅入口；周报由定时任务投递，不由直播事件触发。 */
@Component
public class WeeklyReportPushHandler implements NovaEventHandler {
    public static final class WeeklyReportEvent extends NovaExternalBaseEvent {
        private WeeklyReportEvent() {}
    }
    @Override public void handle(NovaExternalBaseEvent event, PushMessage message) {}
    @Override public Class<? extends NovaExternalBaseEvent> getEventType() { return WeeklyReportEvent.class; }
    @Override public JSONObject getDefaultParams() { return WeeklyReportLayout.defaults(); }
    @Override public boolean reportLayout() { return true; }
    @Override public List<HandlerOption> options() {
        return WeeklyReportLayout.OPTIONS;
    }
    @Override public boolean defaultEnabled() { return false; }
    @Override public String displayName() { return "运营周报"; }
    @Override public int displayOrder() { return 60; }
    @Override public String description() {
        return "每周一 10:00 发送上一周（周一 6:00 至下周一 6:00）的统计图与摘要；遇静音时段顺延到静音结束后发送；隐藏金额的会话不显示金额";
    }
}
