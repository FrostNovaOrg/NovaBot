package com.starlwr.bot.core.event.live.base;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.NovaBaseLiveEvent;
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
public class NovaLiveOperationEvent extends NovaBaseLiveEvent {
    /**
     * 观众信息
     */
    private UserInfo sender;

    public NovaLiveOperationEvent(String platform, LiveStreamerInfo source, UserInfo sender) {
        super(platform, source);
        this.sender = sender;
    }

    public NovaLiveOperationEvent(String platform, LiveStreamerInfo source, UserInfo sender, Instant instant) {
        super(platform, source, instant);
        this.sender = sender;
    }

    public NovaLiveOperationEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender) {
        super(platform, source);
        this.sender = sender;
    }

    public NovaLiveOperationEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, Instant instant) {
        super(platform, source, instant);
        this.sender = sender;
    }
}
