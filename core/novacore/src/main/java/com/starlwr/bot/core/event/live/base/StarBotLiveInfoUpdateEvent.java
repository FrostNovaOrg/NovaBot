package com.starlwr.bot.core.event.live.base;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.StarBotBaseLiveEvent;
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
public class StarBotLiveInfoUpdateEvent extends StarBotBaseLiveEvent {
    public StarBotLiveInfoUpdateEvent(String platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public StarBotLiveInfoUpdateEvent(String platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }

    public StarBotLiveInfoUpdateEvent(LivePlatform platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public StarBotLiveInfoUpdateEvent(LivePlatform platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }
}
