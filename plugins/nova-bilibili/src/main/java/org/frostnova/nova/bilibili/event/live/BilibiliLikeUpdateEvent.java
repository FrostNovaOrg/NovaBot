package org.frostnova.nova.bilibili.event.live;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.core.event.live.common.LikeUpdateEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 哔哩哔哩点赞数更新事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliLikeUpdateEvent extends LikeUpdateEvent {
    public BilibiliLikeUpdateEvent(LiveStreamerInfo source, Integer count) {
        super(BilibiliPlatform.BILIBILI, source, count);
    }

    public BilibiliLikeUpdateEvent(LiveStreamerInfo source, Integer count, Instant instant) {
        super(BilibiliPlatform.BILIBILI, source, count, instant);
    }
}
