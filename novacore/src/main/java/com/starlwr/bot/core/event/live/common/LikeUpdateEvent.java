package com.starlwr.bot.core.event.live.common;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.base.StarBotLiveInfoUpdateEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
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
public class LikeUpdateEvent extends StarBotLiveInfoUpdateEvent {
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
