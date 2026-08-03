package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.bilibili.model.BilibiliEmojiInfo;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.common.DanmuEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 哔哩哔哩弹幕事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliDanmuEvent extends DanmuEvent {
    /**
     * 被回复的用户，仅在弹幕为回复时存在
     */
    private UserInfo reply;

    /**
     * 弹幕中包含的表情
     */
    private List<BilibiliEmojiInfo> emojis = new ArrayList<>();

    public BilibiliDanmuEvent(LiveStreamerInfo source, UserInfo sender, String content, String contentText) {
        super(LivePlatform.BILIBILI, source, sender, content, contentText);
    }

    public BilibiliDanmuEvent(LiveStreamerInfo source, UserInfo sender, String content, String contentText, Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, content, contentText, instant);
    }
}
