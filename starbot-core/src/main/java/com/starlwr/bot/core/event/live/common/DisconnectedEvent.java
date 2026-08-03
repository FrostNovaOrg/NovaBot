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
 * 连接断开事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class DisconnectedEvent extends StarBotLiveConnectionEvent {
    public DisconnectedEvent(String platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public DisconnectedEvent(String platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }

    public DisconnectedEvent(LivePlatform platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public DisconnectedEvent(LivePlatform platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }
}
