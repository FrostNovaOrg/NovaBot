package org.frostnova.nova.core.event.live.common;

import org.frostnova.nova.core.enums.LivePlatform;
import org.frostnova.nova.core.event.live.base.NovaLiveInfoUpdateEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 点赞数更新事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class LikeUpdateEvent extends NovaLiveInfoUpdateEvent {
    /**
     * 点赞数
     */
    private Integer count;

    public LikeUpdateEvent(String platform, LiveStreamerInfo source, Integer count) {
        super(platform, source);
        this.count = count;
    }

    public LikeUpdateEvent(String platform, LiveStreamerInfo source, Integer count, Instant instant) {
        super(platform, source, instant);
        this.count = count;
    }

    public LikeUpdateEvent(LivePlatform platform, LiveStreamerInfo source, Integer count) {
        super(platform, source);
        this.count = count;
    }

    public LikeUpdateEvent(LivePlatform platform, LiveStreamerInfo source, Integer count, Instant instant) {
        super(platform, source, instant);
        this.count = count;
    }
}
