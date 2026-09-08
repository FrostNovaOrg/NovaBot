package com.starlwr.bot.core.event.live.base;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.NovaBaseLiveEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 直播状态变更事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class NovaLiveStatusChangeEvent extends NovaBaseLiveEvent {
    public NovaLiveStatusChangeEvent(String platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public NovaLiveStatusChangeEvent(String platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }

    public NovaLiveStatusChangeEvent(LivePlatform platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public NovaLiveStatusChangeEvent(LivePlatform platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }
}
