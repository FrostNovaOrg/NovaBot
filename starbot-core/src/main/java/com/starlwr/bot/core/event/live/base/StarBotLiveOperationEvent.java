package com.starlwr.bot.core.event.live.base;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.StarBotBaseLiveEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 直播间操作事件 (进房、关注、分享等)
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class StarBotLiveOperationEvent extends StarBotBaseLiveEvent {
    /**
     * 观众信息
     */
    private UserInfo sender;

    public StarBotLiveOperationEvent(String platform, LiveStreamerInfo source, UserInfo sender) {
        super(platform, source);
        this.sender = sender;
    }

    public StarBotLiveOperationEvent(String platform, LiveStreamerInfo source, UserInfo sender, Instant instant) {
        super(platform, source, instant);
        this.sender = sender;
    }

    public StarBotLiveOperationEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender) {
        super(platform, source);
        this.sender = sender;
    }

    public StarBotLiveOperationEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, Instant instant) {
        super(platform, source, instant);
        this.sender = sender;
    }
}
