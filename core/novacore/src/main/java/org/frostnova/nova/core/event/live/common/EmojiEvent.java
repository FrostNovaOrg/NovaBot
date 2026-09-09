package org.frostnova.nova.core.event.live.common;

import org.frostnova.nova.core.enums.LivePlatform;
import org.frostnova.nova.core.event.live.base.NovaLiveMessageEvent;
import org.frostnova.nova.core.model.EmojiInfo;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 表情事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class EmojiEvent extends NovaLiveMessageEvent {
    /**
     * 表情信息
     */
    private EmojiInfo emoji;

    public EmojiEvent(String platform, LiveStreamerInfo source, UserInfo sender, EmojiInfo emoji) {
        super(platform, source, sender);
        this.emoji = emoji;
    }

    public EmojiEvent(String platform, LiveStreamerInfo source, UserInfo sender, EmojiInfo emoji, Instant instant) {
        super(platform, source, sender, instant);
        this.emoji = emoji;
    }

    public EmojiEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, EmojiInfo emoji) {
        super(platform, source, sender);
        this.emoji = emoji;
    }

    public EmojiEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, EmojiInfo emoji, Instant instant) {
        super(platform, source, sender, instant);
        this.emoji = emoji;
    }
}
