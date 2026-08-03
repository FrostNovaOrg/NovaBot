package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.common.LikeUpdateEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
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
        super(LivePlatform.BILIBILI, source, count);
    }

    public BilibiliLikeUpdateEvent(LiveStreamerInfo source, Integer count, Instant instant) {
        super(LivePlatform.BILIBILI, source, count, instant);
    }
}
