package org.frostnova.nova.bilibili.event.live;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.core.event.live.common.RoomLockEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 哔哩哔哩直播间被封禁事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliRoomLockEvent extends RoomLockEvent {
    public BilibiliRoomLockEvent(LiveStreamerInfo source, String reason, Instant expireAt) {
        super(BilibiliPlatform.BILIBILI, source, reason, expireAt);
    }

    public BilibiliRoomLockEvent(LiveStreamerInfo source, String reason, Instant expireAt, Instant instant) {
        super(BilibiliPlatform.BILIBILI, source, reason, expireAt, instant);
    }
}
