package org.frostnova.nova.core.sender;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.health.PushActivityRecorder;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.Sender;
import org.frostnova.nova.core.service.AtAllQuotaService;
import org.frostnova.nova.core.service.NovaSenderService;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.frostnova.nova.core.util.HttpUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreLiveReminderSenderTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    @DisplayName("本人固定时间和主动索取越过真实静音闸门但总开关仍拦，避免入队复查把提醒挡掉")
    @SuppressWarnings("unchecked")
    void personalReminderDelivery(boolean requested) throws Exception {
        var properties = new NovaCoreProperties();
        properties.getPush().setQuietStart("18:00"); properties.getPush().setQuietEnd("20:00");
        var gate = new PushGate(properties);
        var http = mock(HttpUtil.class);
        when(http.postJson(anyString(), anyMap(), anyMap())).thenReturn(JSONObject.of("code", 0, "id", "synthetic-reminder"));
        var platform = new Sender(); platform.setName("sample"); platform.setUrl("https://bot.example.test/send"); platform.setDelay(0);
        var platforms = mock(NovaSenderService.class); when(platforms.getSender("sample")).thenReturn(Optional.of(platform));
        var timeline = mock(TimelineWriter.class);
        ObjectProvider<AtAllPermissionResolver> resolvers = mock(ObjectProvider.class);
        when(resolvers.iterator()).thenAnswer(i -> Collections.emptyIterator());
        var sender = new NovaMessageSender(http, platforms, new PushActivityRecorder(timeline), gate, timeline,
                mock(AtAllQuotaService.class), resolvers, mock(FirstPushTipService.class));
        var message = Message.create("sample", PushTargetType.FRIEND, 990000001001L, "【开播前提醒】合成常客甲").get(0);
        message.setReply(requested);
        var delivered = new CountDownLatch(1); message.addOnSuccessCallback(delivered::countDown);
        try {
            assertEquals(PushGate.Block.QUIET_HOURS, sender.sendScheduled(message, LocalTime.of(19, 0)));
            verifyNoInteractions(http);
            assertNull(requested ? sender.sendRequested(message) : sender.sendScheduled(message, LocalTime.of(19, 0), true));
            assertTrue(delivered.await(15, TimeUnit.SECONDS), "等待发送成功回调");
            assertFalse(message.isAlert());
            properties.getPush().setEnabled(false);
            assertEquals(PushGate.Block.DISABLED,
                    requested ? sender.sendRequested(message) : sender.sendScheduled(message, LocalTime.of(19, 0), true));
            verify(http).postJson(anyString(), anyMap(), anyMap());
        } finally { sender.shutdown(); }
    }
}
