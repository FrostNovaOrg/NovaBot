package org.frostnova.nova.adapter.onebot.alert;

import org.frostnova.nova.adapter.onebot.config.OneBotAdapterPluginProperties;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * QQ 告警通道测试
 * <p>
 * 重点在推送目标类型的取值。该字段与 datasource.json 中推送目标的 type 是同一套编码
 * （{@link PushTargetType}：GROUP 为 1、FRIEND 为 0），一旦填成别的数字，
 * 消息会在发送阶段被解析为「未知类型」直接丢弃，而告警恰恰是出问题时唯一的提示，
 * 它自己静默失效是最糟的情况。
 */
@DisplayName("QQ 告警通道")
class QqAlertChannelTest {
    @Test
    @DisplayName("配置完整且类型合法时应判定为可用")
    void shouldBeAvailableWithValidConfiguration() {
        OneBotAdapterPluginProperties properties = properties(PushTargetType.FRIEND.getCode(), 10000L);

        assertTrue(new QqAlertChannel(properties, mock(NovaMessageSender.class)).isAvailable());
    }

    @Test
    @DisplayName("群聊与私聊两种合法取值都应被接受")
    void shouldAcceptBothValidTypes() {
        NovaMessageSender sender = mock(NovaMessageSender.class);

        assertTrue(new QqAlertChannel(properties(PushTargetType.GROUP.getCode(), 10000L), sender).isAvailable(),
                "群聊（1）应可用");
        assertTrue(new QqAlertChannel(properties(PushTargetType.FRIEND.getCode(), 10000L), sender).isAvailable(),
                "私聊（0）应可用");
    }

    @Test
    @DisplayName("类型取值非法时应判定为不可用, 而不是发出一条注定被丢弃的告警")
    void shouldBeUnavailableWithInvalidType() {
        // 2 是曾经写在文档与默认值里的错误取值，它会被解析为 UNKNOWN
        OneBotAdapterPluginProperties properties = properties(2, 10000L);

        assertEquals(PushTargetType.UNKNOWN, PushTargetType.of(2), "前置条件: 2 不是合法取值");
        assertFalse(new QqAlertChannel(properties, mock(NovaMessageSender.class)).isAvailable());
    }

    @Test
    @DisplayName("未配置平台或号码时应判定为不可用")
    void shouldBeUnavailableWithoutTarget() {
        NovaMessageSender sender = mock(NovaMessageSender.class);

        assertFalse(new QqAlertChannel(properties(PushTargetType.FRIEND.getCode(), null), sender).isAvailable(),
                "未填号码时不可用");

        OneBotAdapterPluginProperties noPlatform = properties(PushTargetType.FRIEND.getCode(), 10000L);
        noPlatform.getAlert().setPlatform("");
        assertFalse(new QqAlertChannel(noPlatform, sender).isAvailable(), "未填平台名时不可用");
    }

    @Test
    @DisplayName("发送的告警应带上配置的目标类型与号码")
    void shouldSendToConfiguredTarget() {
        OneBotAdapterPluginProperties properties = properties(PushTargetType.GROUP.getCode(), 12345L);
        NovaMessageSender sender = mock(NovaMessageSender.class);

        new QqAlertChannel(properties, sender).send("标题", "正文");

        ArgumentCaptor<Message> captured = ArgumentCaptor.forClass(Message.class);
        verify(sender, atLeastOnce()).send(captured.capture());

        Message message = captured.getValue();
        assertEquals("qq-onebot", message.getPlatform());
        assertEquals(PushTargetType.GROUP, message.getType());
        assertEquals(12345L, message.getNum());
    }

    /**
     * 构造告警配置
     * @param qqType 目标类型
     * @param qqNum 群号或 QQ 号
     * @return 配置
     */
    private OneBotAdapterPluginProperties properties(int type, Long num) {
        OneBotAdapterPluginProperties properties = new OneBotAdapterPluginProperties();
        properties.getAlert().setPlatform("qq-onebot");
        properties.getAlert().setType(type);
        properties.getAlert().setNum(num);
        return properties;
    }
}
