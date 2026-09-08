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
 * 直播间连接状态变更事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class StarBotLiveConnectionEvent extends StarBotBaseLiveEvent {
    public StarBotLiveConnectionEvent(String platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public StarBotLiveConnectionEvent(String platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }

    public StarBotLiveConnectionEvent(LivePlatform platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public StarBotLiveConnectionEvent(LivePlatform platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }
}
