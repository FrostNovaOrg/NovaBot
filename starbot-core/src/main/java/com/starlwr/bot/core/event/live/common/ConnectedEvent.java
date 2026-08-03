package com.starlwr.bot.core.event.live.common;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.base.StarBotLiveConnectionEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 连接成功事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class ConnectedEvent extends StarBotLiveConnectionEvent {
    public ConnectedEvent(String platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public ConnectedEvent(String platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }

    public ConnectedEvent(LivePlatform platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public ConnectedEvent(LivePlatform platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }
}
