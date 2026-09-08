package com.starlwr.bot.core.event.live.common;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.base.StarBotLivePurchaseEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 醒目留言事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class SuperChatEvent extends StarBotLivePurchaseEvent {
    /**
     * 内容
     */
    private String content;

    /**
     * 平台给出的停留时长，单位：秒，取不到时为空
     * <p>
     * <b>它决定的不是置顶时长。</b> 醒目留言在直播间画面上占的那个位置可能被占用远超这个数，
     * 这个数只说明平台认为这条留言该「有效」多久。拿它做倒计时可以，
     * 拿它推断「这条 SC 什么时候从画面上消失」不行。
     */
    private Integer durationSec;

    /**
     * 平台给出的醒目留言 id，取不到时为空
     * <p>
     * <b>这是醒目留言自己的编号，不是弹幕的 msg_id。</b> 弹幕报文最外层根本没有 msg_id，
     * 而醒目留言有这个 id，因此它是少数几种能直接按 id 去重的消息之一。
     */
    private Long messageId;

    /**
     * 生效时刻，取不到时为空
     */
    private Instant startTime;

    /**
     * 失效时刻，取不到时为空
     */
    private Instant endTime;

    public SuperChatEvent(String platform, LiveStreamerInfo source, UserInfo sender, String content, Double value) {
        super(platform, source, sender, value);
        this.content = content;
    }

    public SuperChatEvent(String platform, LiveStreamerInfo source, UserInfo sender, String content, Double value, Instant instant) {
        super(platform, source, sender, value, instant);
        this.content = content;
    }

    public SuperChatEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, String content, Double value) {
        super(platform, source, sender, value);
        this.content = content;
    }

    public SuperChatEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, String content, Double value, Instant instant) {
        super(platform, source, sender, value, instant);
        this.content = content;
    }
}
