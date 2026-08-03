package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.common.ShareEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 哔哩哔哩分享直播间事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliShareEvent extends ShareEvent {
    public BilibiliShareEvent(LiveStreamerInfo source, UserInfo sender) {
        super(LivePlatform.BILIBILI, source, sender);
    }

    public BilibiliShareEvent(LiveStreamerInfo source, UserInfo sender, Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, instant);
    }
}
