package org.frostnova.nova.core.event.remote;

import org.frostnova.nova.core.event.NovaInternalBaseEvent;
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
public class NovaRemoteMessageEvent extends NovaInternalBaseEvent {
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

    /**
     * 发送者在会话中的角色，如 owner（群主）、admin（管理员）、member（普通成员）
     * <p>
     * 取自推送平台的原始消息，平台未提供时为空。管理类命令据此判断权限——
     * 没有这一项，群里任何人都能把机器人的功能对全群关掉。
     */
    private String senderRole;

    /**
     * 消息里是否 @ 了机器人本身
     * <p>
     * 群聊中这是「这条消息是不是说给我听的」的唯一依据。判定必须由适配器完成：
     * 「@ 了谁」只存在于平台的原始消息结构里（OneBot 是 at 段与 self_id），
     * 到了 {@link #text} 这一层已经看不出来了。
     * <p>
     * 私聊没有 @ 一说，此项恒为 false，不参与判定。
     */
    private boolean mentionsBot;

    public NovaRemoteMessageEvent(String platform, String messageType, Long num, Long senderUid, String text) {
        this(platform, messageType, num, senderUid, text, (String) null);
    }

    public NovaRemoteMessageEvent(String platform, String messageType, Long num, Long senderUid,
                                     String text, String senderRole) {
        this(platform, messageType, num, senderUid, text, senderRole, false);
    }

    public NovaRemoteMessageEvent(String platform, String messageType, Long num, Long senderUid,
                                     String text, String senderRole, boolean mentionsBot) {
        this.platform = platform;
        this.messageType = messageType;
        this.num = num;
        this.senderUid = senderUid;
        this.text = text;
        this.senderRole = senderRole;
        this.mentionsBot = mentionsBot;
    }

    public NovaRemoteMessageEvent(String platform, String messageType, Long num, Long senderUid, String text, Instant instant) {
        super(instant);
        this.platform = platform;
        this.messageType = messageType;
        this.num = num;
        this.senderUid = senderUid;
        this.text = text;
    }
}
