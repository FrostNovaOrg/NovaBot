package org.frostnova.nova.bilibili.event.live;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.core.event.live.common.ShareEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.UserInfo;
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
        super(BilibiliPlatform.BILIBILI, source, sender);
    }

    public BilibiliShareEvent(LiveStreamerInfo source, UserInfo sender, Instant instant) {
        super(BilibiliPlatform.BILIBILI, source, sender, instant);
    }
}
