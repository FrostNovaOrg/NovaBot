package org.frostnova.nova.bilibili.event.live;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.core.event.live.common.EmojiEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.EmojiInfo;
import org.frostnova.nova.core.model.UserInfo;
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
        super(BilibiliPlatform.BILIBILI, source, sender, emoji);
    }

    public BilibiliEmojiEvent(LiveStreamerInfo source, UserInfo sender, EmojiInfo emoji, Instant instant) {
        super(BilibiliPlatform.BILIBILI, source, sender, emoji, instant);
    }
}
