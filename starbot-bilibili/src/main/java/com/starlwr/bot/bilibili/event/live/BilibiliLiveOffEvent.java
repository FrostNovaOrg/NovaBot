package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.common.LiveOffEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 哔哩哔哩下播事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliLiveOffEvent extends LiveOffEvent {
    public BilibiliLiveOffEvent(LiveStreamerInfo source) {
        super(LivePlatform.BILIBILI, source);
    }

    public BilibiliLiveOffEvent(LiveStreamerInfo source, Instant instant) {
        super(LivePlatform.BILIBILI, source, instant);
    }
}
