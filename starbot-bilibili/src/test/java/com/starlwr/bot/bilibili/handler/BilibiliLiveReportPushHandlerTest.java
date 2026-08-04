package com.starlwr.bot.bilibili.handler;

import com.starlwr.bot.bilibili.event.live.BilibiliLiveOffEvent;
import com.starlwr.bot.bilibili.painter.BilibiliLiveReportPainter;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 下播报告推送处理器测试
 */
@DisplayName("下播报告推送处理器")
class BilibiliLiveReportPushHandlerTest {
    private BilibiliLiveReportPainter painter;

    private StarBotMessageSender sender;

    private BilibiliLiveReportPushHandler handler;

    @BeforeEach
    void setUp() {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getUpInfoByUid(anyLong())).thenThrow(new RuntimeException("接口不可用"));
        painter = mock(BilibiliLiveReportPainter.class);
        sender = mock(StarBotMessageSender.class);
        handler = new BilibiliLiveReportPushHandler(api, sender, painter);
    }

    @Test
    @DisplayName("绘制成功时应推送报告图片")
    void pushesReportImage() {
        when(painter.paint(anyString(), any(), any())).thenReturn(Optional.of("QUJD"));

        handler.handle(event(), pushMessage());

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(sender).send(captor.capture());
        assertEquals("{image_base64=QUJD}", captor.getValue().getContent());
    }

    @Test
    @DisplayName("绘制失败时默认模板应整条跳过而非推送空消息")
    void skipsWhenPaintFails() {
        when(painter.paint(anyString(), any(), any())).thenReturn(Optional.empty());

        handler.handle(event(), pushMessage());

        verify(sender, never()).send(any());
    }

    private BilibiliLiveOffEvent event() {
        return new BilibiliLiveOffEvent(new LiveStreamerInfo(10001L, "主播甲", 20002L));
    }

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
}
