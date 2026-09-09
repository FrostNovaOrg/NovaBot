package org.frostnova.nova.bilibili.handler;

import org.frostnova.nova.bilibili.event.live.BilibiliLiveOffEvent;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.PushMessage;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.service.LiveDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * 下播推送处理器测试
 * <p>
 * 默认模板内置直播时长后，重点覆盖时长可得与不可得两种情形下的渲染结果。
 */
@DisplayName("下播推送处理器")
class BilibiliLiveOffPushHandlerTest {
    private BilibiliApiUtil api;

    private NovaMessageSender sender;

    private LiveDataService liveDataService;

    private BilibiliLiveOffPushHandler handler;

    @BeforeEach
    void setUp() {
        api = mock(BilibiliApiUtil.class);
        sender = mock(NovaMessageSender.class);
        liveDataService = mock(LiveDataService.class);
        handler = new BilibiliLiveOffPushHandler(api, sender, liveDataService);

        // 昵称接口不可用时回退到事件携带的昵称，测试不关心接口路径
        when(api.getUpInfoByUid(anyLong())).thenThrow(new RuntimeException("接口不可用"));
    }

    @Test
    @DisplayName("时长可得时默认模板应渲染出直播时长")
    void rendersDurationWhenAvailable() {
        when(liveDataService.getLiveStartTime(anyString(), anyLong())).thenReturn(Optional.of(1_000_000L));
        when(liveDataService.getLiveEndTime(anyString(), anyLong())).thenReturn(Optional.of(1_000_000L + 128_000L));

        handler.handle(event(), pushMessage());

        assertEquals("主播甲 直播结束了，本场直播时长 2 分 8 秒", sentContent());
    }

    @Test
    @DisplayName("时长不可得时默认模板应退化为不含时长的版本")
    void degradesGracefullyWhenDurationMissing() {
        when(liveDataService.getLiveStartTime(anyString(), anyLong())).thenReturn(Optional.empty());
        when(liveDataService.getLiveEndTime(anyString(), anyLong())).thenReturn(Optional.of(2_000_000L));

        handler.handle(event(), pushMessage());

        assertEquals("主播甲 直播结束了", sentContent());
    }

    @Test
    @DisplayName("起止时间倒挂时应视同时长不可得")
    void treatsNonPositiveDurationAsMissing() {
        when(liveDataService.getLiveStartTime(anyString(), anyLong())).thenReturn(Optional.of(2_000_000L));
        when(liveDataService.getLiveEndTime(anyString(), anyLong())).thenReturn(Optional.of(1_000_000L));

        handler.handle(event(), pushMessage());

        assertEquals("主播甲 直播结束了", sentContent());
    }

    /**
     * 构造下播事件
     */
    private BilibiliLiveOffEvent event() {
        return new BilibiliLiveOffEvent(new LiveStreamerInfo(10001L, "主播甲", 20002L));
    }

    /**
     * 以处理器的默认参数构造推送消息
     */
    private PushMessage pushMessage() {
        PushTarget target = new PushTarget();
        target.setPlatform("qq-onebot");
        target.setType(PushTargetType.GROUP);
        target.setNum(30003L);

        PushMessage message = new PushMessage();
        message.setTarget(target);
        message.setParamsJsonObject(handler.getDefaultParams());
        return message;
    }

    /**
     * 取出实际发送的消息内容
     */
    private String sentContent() {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(sender).send(captor.capture());
        return captor.getValue().getContent();
    }
}
