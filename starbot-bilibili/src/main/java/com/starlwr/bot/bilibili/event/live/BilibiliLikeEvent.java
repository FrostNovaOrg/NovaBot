package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.common.LikeEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 哔哩哔哩点赞事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliLikeEvent extends LikeEvent {
    public BilibiliLikeEvent(LiveStreamerInfo source, UserInfo sender) {
        super(LivePlatform.BILIBILI, source, sender);
    }

    public BilibiliLikeEvent(LiveStreamerInfo source, UserInfo sender, Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, instant);
    }
}
