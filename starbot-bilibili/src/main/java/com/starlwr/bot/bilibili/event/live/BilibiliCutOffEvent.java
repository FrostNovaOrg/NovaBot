package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.common.LiveCutOffEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 哔哩哔哩直播流被切断事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliCutOffEvent extends LiveCutOffEvent {
    public BilibiliCutOffEvent(LiveStreamerInfo source, String reason) {
        super(LivePlatform.BILIBILI, source, reason);
    }

    public BilibiliCutOffEvent(LiveStreamerInfo source, String reason, Instant instant) {
        super(LivePlatform.BILIBILI, source, reason, instant);
    }
}
