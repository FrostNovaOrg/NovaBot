package com.starlwr.bot.bilibili.handler;

import com.starlwr.bot.bilibili.event.live.BilibiliLiveOffEvent;
import com.starlwr.bot.bilibili.painter.BilibiliLiveReportPainter;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.HandlerOption;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Test
    @DisplayName("界面上的可选项应与实际生效的默认值一一对应")
    void optionsMatchDefaultParams() {
        // 两处若各写一份，改了一处忘了另一处，界面上勾的与实际生效的就会对不上，
        // 而这种不一致不会有任何报错——只会让人以为「配了没用」
        List<HandlerOption> options = handler.options();
        assertFalse(options.isEmpty(), "下播报告应声明可配置的版式选项");

        for (HandlerOption option : options) {
            assertTrue(handler.getDefaultParams().containsKey(option.key()),
                    "选项 " + option.key() + " 未出现在默认参数中");
            assertEquals(option.defaultValue(), handler.getDefaultParams().get(option.key()),
                    "选项 " + option.key() + " 的默认值与默认参数不一致");
        }
    }

    @Test
    @DisplayName("排行榜类选项的取值区间应与报告版式的夹取区间一致")
    void rankingOptionBoundsMatchPainter() {
        // 界面允许填 21 而绘制时夹到 20，等于界面在骗人
        handler.options().stream()
                .filter(option -> option.type() == HandlerOption.Type.INTEGER)
                .forEach(option -> {
                    assertEquals(0, option.min(), option.key() + " 的下限应为 0（即不展示）");
                    assertEquals(20, option.max(), option.key() + " 的上限应与 BilibiliLiveReportOptions 一致");
                });
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
