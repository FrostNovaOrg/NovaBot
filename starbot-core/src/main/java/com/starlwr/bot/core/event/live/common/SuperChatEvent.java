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
