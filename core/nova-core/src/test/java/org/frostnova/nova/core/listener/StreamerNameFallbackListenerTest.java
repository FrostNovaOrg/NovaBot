package org.frostnova.nova.core.listener;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.alert.AlertService;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.event.NovaExternalBaseEvent;
import org.frostnova.nova.core.event.live.common.LiveWarningEvent;
import org.frostnova.nova.core.handler.NovaEventHandler;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.PushMessage;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.sender.PushGate;
import org.frostnova.nova.core.service.LiveSessionArchive;
import org.frostnova.nova.core.service.StreamerNames;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 起动时没查到主播昵称：日志页上「丢弃了谁的推送」与平台干预告警里的主播名
 * <p>
 * 事件里的主播名来自内存里的推送配置，起动补全没查到时是空的。
 * 此时日志页原先只写一串 uid，告警里写的是「null」；控制台却显示得出最近一场的昵称。
 */
@DisplayName("日志页与告警里的主播名：补全没查到昵称时退回最近一场")
class StreamerNameFallbackListenerTest {
    private static final long UID = 10001L;

    @TempDir
    Path dir;

    private StreamerNames names;

    @BeforeEach
    void setUp() throws IOException {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        names = new StreamerNames(new LiveSessionArchive(properties));

        JSONObject line = new JSONObject();
        line.put("platform", "bilibili");
        line.put("uid", UID);
        line.put("uname", "最近的昵称");
        line.put("startTime", 1_700_000_000_000L);
        line.put("endTime", 1_700_003_600_000L);
        Files.writeString(dir.resolve("sessions.jsonl"), line.toJSONString() + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("暂停推送时丢弃的那条：日志页写最近一场的昵称，不写 uid")
    void droppedPushNamesTheStreamer() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getPush().setEnabled(false);

        List<TimelineEvent> recorded = new ArrayList<>();
        NovaHandlerListener listener = new NovaHandlerListener(dataSource(), new PushGate(properties),
                recorded::add, names);

        listener.onNovaExternalBaseEvent(new NovaExternalBaseEvent("bilibili", new LiveStreamerInfo(UID, "", 20002L)));

        assertEquals(1, recorded.size(), recorded.toString());
        assertEquals("最近的昵称", recorded.get(0).streamer());
        assertTrue(recorded.get(0).text().contains("最近的昵称"), recorded.get(0).text());
    }

    @Test
    @DisplayName("平台警告的告警：写最近一场的昵称，不写 null")
    void interventionAlertNamesTheStreamer() {
        AlertService alertService = mock(AlertService.class);
        NovaLiveInterventionListener listener = new NovaLiveInterventionListener(alertService, names);

        listener.onWarning(new LiveWarningEvent("bilibili", new LiveStreamerInfo(UID, null, 20002L), "测试说明"));

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(alertService).alert(anyString(), eq("直播收到违规警告"), content.capture());
        assertTrue(content.getValue().startsWith("最近的昵称（直播间 20002）"), content.getValue());
    }

    private static AbstractDataSource dataSource() {
        PushTarget target = new PushTarget();
        target.setPlatform("qq-onebot");
        target.setType(PushTargetType.GROUP);
        target.setNum(30003L);

        PushMessage message = new PushMessage();
        message.setTarget(target);
        message.setHandlerInstance(new NovaEventHandler() {
            @Override
            public void handle(NovaExternalBaseEvent baseEvent, PushMessage pushMessage) {
            }

            @Override
            public Class<? extends NovaExternalBaseEvent> getEventType() {
                return NovaExternalBaseEvent.class;
            }

            @Override
            public JSONObject getDefaultParams() {
                return new JSONObject();
            }
        });
        message.setEventClass(NovaExternalBaseEvent.class);
        target.getMessages().add(message);

        PushUser user = new PushUser();
        user.setPlatform("bilibili");
        user.setUid(UID);
        user.setUname("");
        user.getTargets().add(target);

        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getUser("bilibili", UID)).thenReturn(Optional.of(user));
        return dataSource;
    }
}
