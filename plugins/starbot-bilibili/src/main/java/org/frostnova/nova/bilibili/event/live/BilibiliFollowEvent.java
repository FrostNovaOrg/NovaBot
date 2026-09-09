package org.frostnova.nova.bilibili.event.live;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.core.event.live.common.FollowEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 哔哩哔哩关注事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliFollowEvent extends FollowEvent {
    public BilibiliFollowEvent(LiveStreamerInfo source, UserInfo sender) {
        super(BilibiliPlatform.BILIBILI, source, sender);
    }

    public BilibiliFollowEvent(LiveStreamerInfo source, UserInfo sender, Instant instant) {
        super(BilibiliPlatform.BILIBILI, source, sender, instant);
    }
}
