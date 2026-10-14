package org.frostnova.nova.core.sender;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.health.PushActivityRecorder;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.Sender;
import org.frostnova.nova.core.service.AtAllQuotaService;
import org.frostnova.nova.core.service.NovaSenderService;
import org.frostnova.nova.core.timeline.TimelineEventType;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.frostnova.nova.core.util.HttpUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("周报发送入口")
class WeeklyReportSenderTest {
    @Test @DisplayName("静音返回待发，过闸后回调、最近推送和时间线照常记录")
    @SuppressWarnings("unchecked") void gateAndHistory() throws Exception {
        var http = mock(HttpUtil.class);
        when(http.postJson(anyString(), anyMap(), anyMap())).thenReturn(new JSONObject().fluentPut("code", 0).fluentPut("id", "sample-id"));
        var gate = mock(PushGate.class);
        when(gate.blockedBy()).thenReturn(PushGate.Block.QUIET_HOURS);
        when(gate.alertsAllowed()).thenReturn(true);
        var platform = new Sender(); platform.setName("sample"); platform.setUrl("https://bot.example.test/send"); platform.setDelay(0);
        var platforms = mock(NovaSenderService.class); when(platforms.getSender("sample")).thenReturn(Optional.of(platform));
        var timeline = mock(TimelineWriter.class);
        var activity = new PushActivityRecorder(timeline);
        ObjectProvider<AtAllPermissionResolver> resolvers = mock(ObjectProvider.class);
        when(resolvers.iterator()).thenAnswer(i -> Collections.emptyIterator());
        var sender = new NovaMessageSender(http, platforms, activity, gate, timeline,
                mock(AtAllQuotaService.class), resolvers, mock(FirstPushTipService.class));
        var message = Message.create("sample", PushTargetType.GROUP, 1001L, "运营周报示例").get(0);
        var delivered = new CountDownLatch(1);
        var failed = new AtomicInteger();
        message.addOnSuccessCallback(delivered::countDown); message.addOnFailureCallback(failed::incrementAndGet);
        try {
            assertEquals(PushGate.Block.QUIET_HOURS, sender.sendWeeklyReport(message));
            assertEquals(0, sender.getPendingCount()); assertEquals(0, failed.get()); verifyNoInteractions(http);
            when(gate.allowedFor("sample", PushTargetType.GROUP, 1001L)).thenReturn(true);
            assertNull(sender.sendWeeklyReport(message));
            assertTrue(delivered.await(10, TimeUnit.SECONDS), "等待发送成功回调");
            assertEquals(1, activity.getHistory().size()); assertTrue(activity.getHistory().get(0).success());
            assertEquals("运营周报示例", activity.getHistory().get(0).summary());
            verify(timeline).record(argThat(e -> e.type() == TimelineEventType.PUSH_SENT));
        } finally { sender.shutdown(); }
    }
}
