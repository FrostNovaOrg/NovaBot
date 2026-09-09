package org.frostnova.nova.bilibili.event.live;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.core.event.live.common.SuperChatEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.UserInfo;
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
        super(BilibiliPlatform.BILIBILI, source, sender, content, value);
    }

    public BilibiliSuperChatEvent(LiveStreamerInfo source, UserInfo sender, String content, Double value, Instant instant) {
        super(BilibiliPlatform.BILIBILI, source, sender, content, value, instant);
    }
}
