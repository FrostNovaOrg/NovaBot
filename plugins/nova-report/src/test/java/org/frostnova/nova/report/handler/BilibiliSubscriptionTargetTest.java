package org.frostnova.nova.report.handler;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.event.dynamic.BilibiliDynamicUpdateEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliLiveOnEvent;
import org.frostnova.nova.bilibili.handler.BilibiliLiveOnPushHandler;
import org.frostnova.nova.bilibili.model.Dynamic;
import org.frostnova.nova.bilibili.model.Room;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.event.NovaExternalBaseEvent;
import org.frostnova.nova.core.handler.NovaEventHandler;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.PushMessage;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.service.AtSubscriptionService;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.frostnova.nova.report.painter.BilibiliDynamicPainter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("推送订阅名单只用于群目标")
class BilibiliSubscriptionTargetTest {
    private static final String PLATFORM = "qq-onebot";
    private static final long NUMBER = 38025617409263L;
    private static final long UID = 19604318752096L;
    private static final long ROOM = 47615208934771L;
    private static final long SUBSCRIBER = 58025617409263L;

    private final BilibiliApiUtil api = mock(BilibiliApiUtil.class);
    private final NovaMessageSender sender = mock(NovaMessageSender.class);
    private final AtSubscriptionService subscriptions = spy(
            new AtSubscriptionService(new NovaStateStore(new NovaCoreProperties())));

    @Test
    @DisplayName("开播私聊：好友与群同号时 {at} 为空，群仍带订阅名单")
    void liveFriendDoesNotUseGroupSubscriptions() {
        when(api.getLiveInfoByRoomId(anyLong())).thenReturn(new Room());
        assertTargets(new BilibiliLiveOnPushHandler(api, sender, subscriptions, mock(LiveDataService.class)),
                new BilibiliLiveOnEvent(source()), "live");
    }

    @Test
    @DisplayName("动态私聊：好友与群同号时 {at} 为空，群仍带订阅名单")
    void dynamicFriendDoesNotUseGroupSubscriptions() {
        BilibiliDynamicPainter painter = mock(BilibiliDynamicPainter.class);
        when(painter.paint(any(), any())).thenReturn(Optional.empty());
        Dynamic dynamic = new Dynamic();
        dynamic.setId("1");
        dynamic.setType("DYNAMIC_TYPE_DRAW");
        assertTargets(new BilibiliDynamicPushHandler(api, painter, sender, subscriptions, mock(LiveDataService.class),
                        new NovaBilibiliProperties(), TimelineWriter.NONE),
                new BilibiliDynamicUpdateEvent(source(), dynamic, "发布了动态", "https://example.com/dynamic"), "dynamic");
    }

    private void assertTargets(NovaEventHandler handler, NovaExternalBaseEvent event, String type) {
        subscriptions.subscribe(PLATFORM, NUMBER, UID, type, SUBSCRIBER);
        // subscribe 会读名单；只计随后处理推送时的读取。
        org.mockito.Mockito.clearInvocations(subscriptions);
        List<Message> sent = new ArrayList<>();
        doAnswer(invocation -> sent.add(invocation.getArgument(0))).when(sender).send(any());

        handler.handle(event, pushMessage(PushTargetType.FRIEND));
        assertEquals(List.of("提醒"), sent.stream().map(Message::getContent).toList(),
                "好友推送不能带上同号群的订阅名单");
        handler.handle(event, pushMessage(PushTargetType.GROUP));
        assertEquals(List.of("提醒", "{at=" + SUBSCRIBER + "}提醒"),
                sent.stream().map(Message::getContent).toList(), "群推送仍应 @ 订阅者");
        verify(subscriptions, times(1)).list(PLATFORM, NUMBER, UID, type);
    }

    private static LiveStreamerInfo source() {
        return new LiveStreamerInfo(UID, "主播甲", ROOM);
    }

    private static PushMessage pushMessage(PushTargetType type) {
        PushTarget target = new PushTarget();
        target.setPlatform(PLATFORM);
        target.setType(type);
        target.setNum(NUMBER);
        PushMessage message = new PushMessage();
        message.setTarget(target);
        message.setParamsJsonObject(new JSONObject().fluentPut("message", "{at}提醒"));
        return message;
    }
}
