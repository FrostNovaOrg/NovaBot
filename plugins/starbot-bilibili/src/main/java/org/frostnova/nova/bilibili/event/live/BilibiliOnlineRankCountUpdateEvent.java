package org.frostnova.nova.bilibili.event.live;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.core.event.live.common.OnlineRankCountUpdateEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 哔哩哔哩高能用户数更新事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliOnlineRankCountUpdateEvent extends OnlineRankCountUpdateEvent {
    public BilibiliOnlineRankCountUpdateEvent(LiveStreamerInfo source, Integer count, Integer onlineCount, String text) {
        super(BilibiliPlatform.BILIBILI, source, count, onlineCount, text);
    }

    public BilibiliOnlineRankCountUpdateEvent(LiveStreamerInfo source, Integer count, Integer onlineCount, String text, Instant instant) {
        super(BilibiliPlatform.BILIBILI, source, count, onlineCount, text, instant);
    }
}
