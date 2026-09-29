package org.frostnova.nova.bilibili.handler;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.event.live.BilibiliLiveOffEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliLiveOnEvent;
import org.frostnova.nova.bilibili.timeline.BilibiliLiveTimelineRecorder;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.PushMessage;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.service.AtSubscriptionService;
import org.frostnova.nova.core.service.LiveDataService;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 起动时没查到主播昵称、推送时也查不到：开播与下播推送、日志页上开播那一条里的主播名
 * <p>
 * 推送前会再向平台查一次最新昵称，断网或被风控时这一查也落空，原先只剩一串 uid；
 * 日志页上写的是「房间 20002 开播了」。控制台却显示得出最近一场的昵称。
 */
@DisplayName("开播下播推送与日志页：补全没查到昵称时退回最近一场")
class StreamerNameFallbackPushTest {
    private static final long UID = 10001L;

    @TempDir
    Path dir;

    private StreamerNames names;

    private BilibiliApiUtil api;

    private NovaMessageSender sender;

    private LiveDataService liveDataService;

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

        api = mock(BilibiliApiUtil.class);
        when(api.getUpInfoByUid(anyLong())).thenThrow(new RuntimeException("接口不可用"));
        when(api.getLiveInfoByRoomId(anyLong())).thenThrow(new RuntimeException("接口不可用"));
        sender = mock(NovaMessageSender.class);
        liveDataService = mock(LiveDataService.class);
        when(liveDataService.getLiveStartTime(anyString(), anyLong())).thenReturn(Optional.empty());
        when(liveDataService.getLiveEndTime(anyString(), anyLong())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("开播推送：写最近一场的昵称")
    void liveOnPushNamesTheStreamer() {
        AtSubscriptionService subscriptions = mock(AtSubscriptionService.class);
        when(subscriptions.list(anyString(), anyLong(), anyLong(), anyString())).thenReturn(List.of());
        BilibiliLiveOnPushHandler handler =
                new BilibiliLiveOnPushHandler(api, sender, subscriptions, liveDataService, names);

        handler.handle(new BilibiliLiveOnEvent(source()), pushMessage(handler.getDefaultParams()));

        assertEquals("最近的昵称 正在直播 \nhttps://live.bilibili.com/20002", sentContent());
    }

    @Test
    @DisplayName("下播推送：写最近一场的昵称")
    void liveOffPushNamesTheStreamer() {
        BilibiliLiveOffPushHandler handler = new BilibiliLiveOffPushHandler(api, sender, liveDataService, names);

        handler.handle(new BilibiliLiveOffEvent(source()), pushMessage(handler.getDefaultParams()));

        assertEquals("最近的昵称 直播结束了", sentContent());
    }

    @Test
    @DisplayName("日志页上开播那一条：写最近一场的昵称，不写房间号")
    void liveOnTimelineNamesTheStreamer() {
        List<TimelineEvent> recorded = new ArrayList<>();
        new BilibiliLiveTimelineRecorder(recorded::add, names).onLiveOn(new BilibiliLiveOnEvent(source()));

        assertEquals(1, recorded.size(), recorded.toString());
        assertEquals("最近的昵称", recorded.get(0).streamer());
        assertEquals("最近的昵称开播了", recorded.get(0).text());
    }

    private static LiveStreamerInfo source() {
        return new LiveStreamerInfo(UID, "", 20002L);
    }

    private static PushMessage pushMessage(JSONObject params) {
        PushTarget target = new PushTarget();
        target.setPlatform("qq-onebot");
        target.setType(PushTargetType.GROUP);
        target.setNum(30003L);

        PushMessage message = new PushMessage();
        message.setTarget(target);
        message.setParamsJsonObject(params);
        return message;
    }

    private String sentContent() {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(sender).send(captor.capture());
        return captor.getValue().getContent();
    }
}
