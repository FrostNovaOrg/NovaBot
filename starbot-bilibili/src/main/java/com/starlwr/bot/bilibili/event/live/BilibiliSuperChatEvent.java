package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.common.SuperChatEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 哔哩哔哩醒目留言事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliSuperChatEvent extends SuperChatEvent {
    public BilibiliSuperChatEvent(LiveStreamerInfo source, UserInfo sender, String content, Double value) {
        super(LivePlatform.BILIBILI, source, sender, content, value);
    }

    public BilibiliSuperChatEvent(LiveStreamerInfo source, UserInfo sender, String content, Double value, Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, content, value, instant);
    }
}
