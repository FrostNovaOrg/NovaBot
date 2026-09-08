package com.starlwr.bot.core.event.live.common;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.base.StarBotLiveStatusChangeEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 开播事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class LiveOnEvent extends StarBotLiveStatusChangeEvent {
    /**
     * 是否为断线重连（下播后短时间内重新开播），断线重连不会重置直播数据，由 StarBot 内部判断，无需传入
     */
    boolean reconnect;

    public LiveOnEvent(String platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public LiveOnEvent(String platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }

    public LiveOnEvent(LivePlatform platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public LiveOnEvent(LivePlatform platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }
}
