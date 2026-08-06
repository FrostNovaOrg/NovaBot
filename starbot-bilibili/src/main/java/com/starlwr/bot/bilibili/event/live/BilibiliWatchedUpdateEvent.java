package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.common.WatchedUpdateEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 哔哩哔哩看过人数更新事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliWatchedUpdateEvent extends WatchedUpdateEvent {
    public BilibiliWatchedUpdateEvent(LiveStreamerInfo source, Integer count, String text) {
        super(LivePlatform.BILIBILI, source, count, text);
    }

    public BilibiliWatchedUpdateEvent(LiveStreamerInfo source, Integer count, String text, Instant instant) {
        super(LivePlatform.BILIBILI, source, count, text, instant);
    }
}
