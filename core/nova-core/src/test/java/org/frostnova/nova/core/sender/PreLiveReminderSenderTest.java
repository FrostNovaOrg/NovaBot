package org.frostnova.nova.core.sender;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.assistant.AssistantProperties;
import org.frostnova.nova.core.command.CommandContext;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.health.PushActivityRecorder;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.Sender;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.service.*;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.frostnova.nova.core.util.HttpUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreLiveReminderSenderTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    @DisplayName("本人固定时间和主动索取越过真实静音闸门但总开关仍拦，避免入队复查把提醒挡掉")
    void personalReminderDelivery(boolean requested) throws Exception {
        var properties = new NovaCoreProperties();
        properties.getPush().setQuietStart("00:00"); properties.getPush().setQuietEnd("23:59:59.999999999");
        var gate = new PushGate(properties);
        var http = mock(HttpUtil.class);
        when(http.postJson(anyString(), anyMap(), anyMap())).thenReturn(JSONObject.of("code", 0, "id", "synthetic-reminder"));
        var sender = sender(gate, http);
        var message = Message.create("sample", PushTargetType.FRIEND, 990000001001L, "【开播前提醒】合成常客甲").get(0);
        message.setReply(requested);
        var delivered = new CountDownLatch(1); message.addOnSuccessCallback(delivered::countDown);
        try {
            assertEquals(PushGate.Block.QUIET_HOURS, sender.sendScheduled(message, LocalTime.of(19, 0)));
            verifyNoInteractions(http);
            gate.starting();
            assertNotNull(requested ? sender.sendRequested(message) : sender.sendScheduled(message, LocalTime.of(19, 0), true),
                    "启动未就绪时不能把提醒入队");
            verifyNoInteractions(http);
            gate.ready();
            assertNull(requested ? sender.sendRequested(message) : sender.sendScheduled(message, LocalTime.of(19, 0), true));
            assertTrue(delivered.await(15, TimeUnit.SECONDS), "等待发送成功回调");
            assertFalse(message.isAlert());
            properties.getPush().setEnabled(false);
            assertEquals(PushGate.Block.DISABLED,
                    requested ? sender.sendRequested(message) : sender.sendScheduled(message, LocalTime.of(19, 0), true));
            verify(http).postJson(anyString(), anyMap(), anyMap());
        } finally { sender.shutdown(); }
    }

    @Test @DisplayName("启动期间索取能实际收到稍后再试的说明，避免提示也被就绪闸门拦住")
    void requestBeforeReadyDeliversOnlyExplanation(@TempDir Path dir) throws Exception {
        var gate = new PushGate(new NovaCoreProperties()); gate.starting();
        var http = mock(HttpUtil.class);
        var delivered = new CountDownLatch(1);
        var body = new AtomicReference<String>();
        when(http.postJson(anyString(), anyMap(), anyMap())).thenAnswer(call -> {
            body.set(call.getArgument(2).toString());
            delivered.countDown();
            return JSONObject.of("code", 0, "id", "synthetic-reply");
        });
        var sender = sender(gate, http);
        var target = new PushTarget(); target.setPlatform("sample"); target.setType(PushTargetType.FRIEND);
        target.setNum(990000001001L); target.setStreamerSelf(true);
        var user = new PushUser(); user.setPlatform("test"); user.setUid(990000000101L); user.setTargets(List.of(target));
        var service = new PreLiveReminderService(mock(AbstractDataSource.class), mock(LiveSessionArchive.class),
                mock(LiveDataService.class), mock(AudienceLedgerService.class), mock(SessionRecapService.class),
                mock(StreamerProfileStore.class), new AssistantProperties(), sender, gate,
                new PreLiveReminderStore(dir.resolve("reminders.json")), Clock.systemUTC());
        try {
            service.request(user, new CommandContext("sample", PushTargetType.FRIEND, target.getNum(), target.getNum(),
                    "准备开播", List.of(), "准备开播"));
            assertTrue(delivered.await(15, TimeUnit.SECONDS), "等待启动说明送达");
            assertTrue(body.get().contains("机器人刚启动，请过一会儿再说一次"));
            assertFalse(body.get().contains("【开播前提醒】"));
            assertFalse(Files.exists(dir.resolve("reminders.json")));
            verify(http).postJson(anyString(), anyMap(), anyMap());
        } finally { sender.shutdown(); }
    }

    @SuppressWarnings("unchecked")
    private NovaMessageSender sender(PushGate gate, HttpUtil http) {
        var platform = new Sender(); platform.setName("sample"); platform.setUrl("https://bot.example.test/send"); platform.setDelay(0);
        var platforms = mock(NovaSenderService.class); when(platforms.getSender("sample")).thenReturn(Optional.of(platform));
        var timeline = mock(TimelineWriter.class);
        ObjectProvider<AtAllPermissionResolver> resolvers = mock(ObjectProvider.class);
        when(resolvers.iterator()).thenAnswer(i -> Collections.emptyIterator());
        return new NovaMessageSender(http, platforms, new PushActivityRecorder(timeline), gate, timeline,
                mock(AtAllQuotaService.class), resolvers, mock(FirstPushTipService.class));
    }
}
