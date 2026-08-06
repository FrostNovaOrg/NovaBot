package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.common.RoomInfoChangeEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 哔哩哔哩直播间标题或分区变更事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliRoomInfoChangeEvent extends RoomInfoChangeEvent {
    public BilibiliRoomInfoChangeEvent(LiveStreamerInfo source, String title, String parentAreaName, String areaName) {
        super(LivePlatform.BILIBILI, source, title, parentAreaName, areaName);
    }

    public BilibiliRoomInfoChangeEvent(LiveStreamerInfo source, String title, String parentAreaName, String areaName, Instant instant) {
        super(LivePlatform.BILIBILI, source, title, parentAreaName, areaName, instant);
    }
}
