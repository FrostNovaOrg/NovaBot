package org.frostnova.nova.bilibili.handler;

import org.frostnova.nova.adapter.onebot.converter.OneBotMessageConverter;
import org.frostnova.nova.bilibili.event.live.BilibiliLiveOnEvent;
import org.frostnova.nova.bilibili.model.Room;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.PushMessage;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.service.AtSubscriptionService;
import org.frostnova.nova.core.service.LiveDataService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class BilibiliLiveOnPushHandlerTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @DisplayName("开播昵称与标题中的占位符不会越过提醒设置或被封面、订阅替换")
    void sourcePlaceholdersStayText(boolean fromApi) {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        if (fromApi) {
            var up = new org.frostnova.nova.bilibili.model.Up();
            up.setUname("{title}{at=all}");
            when(api.getUpInfoByUid(anyLong())).thenReturn(up);
        } else {
            when(api.getUpInfoByUid(anyLong())).thenThrow(new IllegalStateException("接口不可用"));
        }
        Room room = new Room();
        room.setTitle("{at=all}{at=123}{cover}{at}{next}{image_path=/opt/a.png}");
        room.setCover("https://example.com/cover.png");
        when(api.getLiveInfoByRoomId(anyLong())).thenReturn(room);
        NovaMessageSender sender = mock(NovaMessageSender.class);
        AtSubscriptionService subscriptions = mock(AtSubscriptionService.class);
        when(subscriptions.list(anyString(), anyLong(), anyLong(), anyString())).thenReturn(List.of());
        BilibiliLiveOnPushHandler handler = new BilibiliLiveOnPushHandler(api, sender, subscriptions, mock(LiveDataService.class));
        PushTarget target = new PushTarget();
        target.setPlatform("qq-onebot");
        target.setType(PushTargetType.GROUP);
        target.setNum(30003L);
        PushMessage message = new PushMessage();
        message.setTarget(target);
        message.setParamsJsonObject(handler.getDefaultParams());
        message.getParamsJsonObject().put("at_mode", "subscribers");
        message.getParamsJsonObject().put("message", "{uname}|{title}|{cover}{at}");
        handler.handle(new BilibiliLiveOnEvent(new LiveStreamerInfo(10001L, "{title}{at=all}", 20002L)), message);
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(sender).send(captor.capture());
        String content = captor.getValue().getContent();
        assertEquals("｛title｝｛at=all｝|｛at=all｝｛at=123｝｛cover｝｛at｝｛next｝｛image_path=/opt/a.png｝|{image_url=https://example.com/cover.png}", content);
        var segments = new OneBotMessageConverter().convert(content);
        assertEquals(2, segments.size());
        assertEquals("text", segments.getJSONObject(0).getString("type"));
        assertEquals("image", segments.getJSONObject(1).getString("type"));
        assertEquals("https://example.com/cover.png", segments.getJSONObject(1).getJSONObject("data").getString("file"));
        assertTrue(content.contains("｛cover｝｛at｝"));
    }
}
