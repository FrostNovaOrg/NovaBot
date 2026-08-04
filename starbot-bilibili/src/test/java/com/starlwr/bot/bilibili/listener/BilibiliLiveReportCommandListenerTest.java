package com.starlwr.bot.bilibili.listener;

import com.starlwr.bot.bilibili.handler.BilibiliLiveReportPushHandler;
import com.starlwr.bot.bilibili.painter.BilibiliLiveReportPainter;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.event.remote.StarBotRemoteMessageEvent;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import com.starlwr.bot.core.service.DefaultLiveDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「直播报告」消息命令测试
 * <p>
 * 重点覆盖服务范围：只响应配置了下播报告推送的群，其他会话保持沉默——
 * 这是机器人不打扰无关群聊的硬约束。
 */
@DisplayName("直播报告消息命令")
class BilibiliLiveReportCommandListenerTest {
    private static final String PLATFORM = "qq-onebot";

    private static final Long GROUP = 30003L;

    private static final Long UID = 10001L;

    private AbstractDataSource dataSource;

    private DefaultLiveDataService liveDataService;

    private BilibiliLiveReportPainter painter;

    private StarBotMessageSender sender;

    private BilibiliLiveReportCommandListener listener;

    @BeforeEach
    void setUp() {
        dataSource = mock(AbstractDataSource.class);
        when(dataSource.getUsers(anyString())).thenReturn(List.of(subscribedUser()));

        liveDataService = new DefaultLiveDataService(new StarBotCoreProperties());
        painter = mock(BilibiliLiveReportPainter.class);
        sender = mock(StarBotMessageSender.class);
        listener = new BilibiliLiveReportCommandListener(dataSource, liveDataService, painter, sender);
    }

    @Test
    @DisplayName("配置群内发送命令且主播在播时应回复报告图片")
    void repliesReportWhenLiving() {
        liveDataService.setLiveStatus("bilibili", UID, true);
        when(painter.paint(anyString(), any())).thenReturn(Optional.of("QUJD"));

        listener.onRemoteMessage(command(GROUP, "直播报告"));

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(sender).send(captor.capture());
        assertEquals("{image_base64=QUJD}", captor.getValue().getContent());
        assertEquals(GROUP, captor.getValue().getNum());
    }

    @Test
    @DisplayName("未配置报告推送的群应保持沉默")
    void staysSilentInUnrelatedGroup() {
        liveDataService.setLiveStatus("bilibili", UID, true);

        listener.onRemoteMessage(command(99999L, "直播报告"));

        verify(sender, never()).send(any());
    }

    @Test
    @DisplayName("无人在播时应回复文字说明")
    void repliesTextWhenNobodyLiving() {
        liveDataService.setLiveStatus("bilibili", UID, false);

        listener.onRemoteMessage(command(GROUP, "直播报告"));

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(sender).send(captor.capture());
        assertTrue(captor.getValue().getContent().contains("没有正在直播"));
    }

    @Test
    @DisplayName("冷却期内的重复命令应被忽略")
    void ignoresRepeatedCommandDuringCooldown() {
        liveDataService.setLiveStatus("bilibili", UID, true);
        when(painter.paint(anyString(), any())).thenReturn(Optional.of("QUJD"));

        listener.onRemoteMessage(command(GROUP, "直播报告"));
        listener.onRemoteMessage(command(GROUP, "直播报告"));

        verify(sender, times(1)).send(any());
    }

    @Test
    @DisplayName("非命令文本与私聊消息应被忽略")
    void ignoresUnrelatedMessages() {
        liveDataService.setLiveStatus("bilibili", UID, true);

        listener.onRemoteMessage(command(GROUP, "今天天气不错"));
        StarBotRemoteMessageEvent privateMessage = new StarBotRemoteMessageEvent(PLATFORM, "private", GROUP, 1L, "直播报告");
        listener.onRemoteMessage(privateMessage);

        verify(sender, never()).send(any());
    }

    /**
     * 构造群聊命令事件
     */
    private StarBotRemoteMessageEvent command(Long group, String text) {
        return new StarBotRemoteMessageEvent(PLATFORM, "group", group, 1L, text);
    }

    /**
     * 构造一个把下播报告推送到测试群的推送用户
     */
    private PushUser subscribedUser() {
        PushMessage report = new PushMessage();
        report.setHandler(BilibiliLiveReportPushHandler.class.getName());

        PushTarget target = new PushTarget();
        target.setPlatform(PLATFORM);
        target.setType(PushTargetType.GROUP);
        target.setNum(GROUP);
        target.setMessages(List.of(report));

        PushUser user = new PushUser();
        user.setUid(UID);
        user.setUname("主播甲");
        user.setRoomId(20002L);
        user.setPlatform("bilibili");
        user.setTargets(List.of(target));
        return user;
    }
}
