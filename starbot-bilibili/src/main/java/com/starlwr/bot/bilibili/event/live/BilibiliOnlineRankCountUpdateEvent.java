package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.common.OnlineRankCountUpdateEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 哔哩哔哩高能用户数更新事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliOnlineRankCountUpdateEvent extends OnlineRankCountUpdateEvent {
    public BilibiliOnlineRankCountUpdateEvent(LiveStreamerInfo source, Integer count, Integer onlineCount, String text) {
        super(LivePlatform.BILIBILI, source, count, onlineCount, text);
    }

    public BilibiliOnlineRankCountUpdateEvent(LiveStreamerInfo source, Integer count, Integer onlineCount, String text, Instant instant) {
        super(LivePlatform.BILIBILI, source, count, onlineCount, text, instant);
    }
}
