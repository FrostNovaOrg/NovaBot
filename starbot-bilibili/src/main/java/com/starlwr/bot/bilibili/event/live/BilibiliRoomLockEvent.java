package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.common.RoomLockEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
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
        super(LivePlatform.BILIBILI, source, reason, expireAt);
    }

    public BilibiliRoomLockEvent(LiveStreamerInfo source, String reason, Instant expireAt, Instant instant) {
        super(LivePlatform.BILIBILI, source, reason, expireAt, instant);
    }
}
