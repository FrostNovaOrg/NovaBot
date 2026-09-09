package org.frostnova.nova.bilibili.event.live;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.core.event.live.common.LiveWarningEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 哔哩哔哩直播违规警告事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliLiveWarningEvent extends LiveWarningEvent {
    public BilibiliLiveWarningEvent(LiveStreamerInfo source, String reason) {
        super(BilibiliPlatform.BILIBILI, source, reason);
    }

    public BilibiliLiveWarningEvent(LiveStreamerInfo source, String reason, Instant instant) {
        super(BilibiliPlatform.BILIBILI, source, reason, instant);
    }
}
