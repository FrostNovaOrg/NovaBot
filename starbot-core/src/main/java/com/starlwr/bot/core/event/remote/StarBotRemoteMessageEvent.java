package com.starlwr.bot.core.event.remote;

import com.starlwr.bot.core.event.StarBotInternalBaseEvent;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 推送平台收到的远程消息事件
 * <p>
 * 由推送平台适配器在收到聊天消息时发布，供各平台模块实现消息命令
 * （如在群里发送「直播报告」拉取当前场次的实时报告）。
 * 命令实现方应自行校验消息来源是否为自己配置内的推送目标，避免响应无关会话。
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class StarBotRemoteMessageEvent extends StarBotInternalBaseEvent {
    /**
     * 推送平台名，如 qq-onebot
     */
    private String platform;

    /**
     * 消息类型：group 群聊 / private 私聊
     */
    private String messageType;

    /**
     * 会话号：群聊为群号，私聊为对方账号
     */
    private Long num;

    /**
     * 消息发送者账号
     */
    private Long senderUid;

    /**
     * 消息文本
     */
    private String text;

    public StarBotRemoteMessageEvent(String platform, String messageType, Long num, Long senderUid, String text) {
        this.platform = platform;
        this.messageType = messageType;
        this.num = num;
        this.senderUid = senderUid;
        this.text = text;
    }

    public StarBotRemoteMessageEvent(String platform, String messageType, Long num, Long senderUid, String text, Instant instant) {
        super(instant);
        this.platform = platform;
        this.messageType = messageType;
        this.num = num;
        this.senderUid = senderUid;
        this.text = text;
    }
}
