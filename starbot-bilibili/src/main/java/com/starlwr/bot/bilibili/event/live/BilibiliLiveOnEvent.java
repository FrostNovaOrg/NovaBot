package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.common.LiveOnEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 哔哩哔哩开播事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliLiveOnEvent extends LiveOnEvent {
    public BilibiliLiveOnEvent(LiveStreamerInfo source) {
        super(LivePlatform.BILIBILI, source);
    }

    public BilibiliLiveOnEvent(LiveStreamerInfo source, Instant instant) {
        super(LivePlatform.BILIBILI, source, instant);
    }
}
