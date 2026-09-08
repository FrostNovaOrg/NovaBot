package com.starlwr.bot.core.event.dynamic;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.NovaExternalBaseEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * NovaBot 动态事件基类
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class NovaBaseDynamicEvent extends NovaExternalBaseEvent {
    public NovaBaseDynamicEvent(String platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public NovaBaseDynamicEvent(String platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }

    public NovaBaseDynamicEvent(LivePlatform platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public NovaBaseDynamicEvent(LivePlatform platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }
}
