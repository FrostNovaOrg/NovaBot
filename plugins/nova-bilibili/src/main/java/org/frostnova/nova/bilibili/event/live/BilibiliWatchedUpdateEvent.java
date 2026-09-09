package org.frostnova.nova.bilibili.event.live;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.core.event.live.common.WatchedUpdateEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
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
        super(BilibiliPlatform.BILIBILI, source, count, text);
    }

    public BilibiliWatchedUpdateEvent(LiveStreamerInfo source, Integer count, String text, Instant instant) {
        super(BilibiliPlatform.BILIBILI, source, count, text, instant);
    }
}
