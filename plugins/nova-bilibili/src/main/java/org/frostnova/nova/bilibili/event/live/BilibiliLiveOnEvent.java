package org.frostnova.nova.bilibili.event.live;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.core.event.live.common.LiveOnEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
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
        super(BilibiliPlatform.BILIBILI, source);
    }

    public BilibiliLiveOnEvent(LiveStreamerInfo source, Instant instant) {
        super(BilibiliPlatform.BILIBILI, source, instant);
    }
}
