package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.common.EmojiEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.EmojiInfo;
import com.starlwr.bot.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 哔哩哔哩表情弹幕事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliEmojiEvent extends EmojiEvent {
    public BilibiliEmojiEvent(LiveStreamerInfo source, UserInfo sender, EmojiInfo emoji) {
        super(LivePlatform.BILIBILI, source, sender, emoji);
    }

    public BilibiliEmojiEvent(LiveStreamerInfo source, UserInfo sender, EmojiInfo emoji, Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, emoji, instant);
    }
}
