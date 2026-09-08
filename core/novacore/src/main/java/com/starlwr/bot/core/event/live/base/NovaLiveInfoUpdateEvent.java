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
 * 直播间信息更新事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class NovaLiveInfoUpdateEvent extends NovaBaseLiveEvent {
    public NovaLiveInfoUpdateEvent(String platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public NovaLiveInfoUpdateEvent(String platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }

    public NovaLiveInfoUpdateEvent(LivePlatform platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public NovaLiveInfoUpdateEvent(LivePlatform platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }
}
