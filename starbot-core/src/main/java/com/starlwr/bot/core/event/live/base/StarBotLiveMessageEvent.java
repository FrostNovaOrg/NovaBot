package com.starlwr.bot.core.event.live.base;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 直播间消息事件 (弹幕、表情等)
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class StarBotLiveMessageEvent extends StarBotLiveInteractionEvent {
    public StarBotLiveMessageEvent(String platform, LiveStreamerInfo source, UserInfo sender) {
        super(platform, source, sender);
    }

    public StarBotLiveMessageEvent(String platform, LiveStreamerInfo source, UserInfo sender, Instant instant) {
        super(platform, source, sender, instant);
    }

    public StarBotLiveMessageEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender) {
        super(platform, source, sender);
    }

    public StarBotLiveMessageEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, Instant instant) {
        super(platform, source, sender, instant);
    }
}
