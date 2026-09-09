package org.frostnova.nova.bilibili.event.live;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.core.event.live.common.DisconnectedEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 哔哩哔哩直播间连接断开事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliDisconnectedEvent extends DisconnectedEvent {
    public BilibiliDisconnectedEvent(LiveStreamerInfo source) {
        super(BilibiliPlatform.BILIBILI, source);
    }

    public BilibiliDisconnectedEvent(LiveStreamerInfo source, Instant instant) {
        super(BilibiliPlatform.BILIBILI, source, instant);
    }
}
