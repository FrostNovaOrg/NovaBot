package com.starlwr.bot.core.event.live;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.StarBotExternalBaseEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * StarBot 直播事件基类
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class StarBotBaseLiveEvent extends StarBotExternalBaseEvent {
    public StarBotBaseLiveEvent(String platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public StarBotBaseLiveEvent(String platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }

    public StarBotBaseLiveEvent(LivePlatform platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public StarBotBaseLiveEvent(LivePlatform platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }
}
