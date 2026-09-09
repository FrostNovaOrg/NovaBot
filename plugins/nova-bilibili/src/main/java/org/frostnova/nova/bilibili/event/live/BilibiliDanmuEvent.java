package org.frostnova.nova.bilibili.event.live;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.model.BilibiliEmojiInfo;
import org.frostnova.nova.core.event.live.common.DanmuEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.UserInfo;
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
        super(BilibiliPlatform.BILIBILI, source, sender, content, contentText);
    }

    public BilibiliDanmuEvent(LiveStreamerInfo source, UserInfo sender, String content, String contentText, Instant instant) {
        super(BilibiliPlatform.BILIBILI, source, sender, content, contentText, instant);
    }
}
