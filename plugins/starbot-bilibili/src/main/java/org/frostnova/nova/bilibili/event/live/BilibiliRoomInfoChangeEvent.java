package org.frostnova.nova.bilibili.event.live;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.core.event.live.common.RoomInfoChangeEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
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
        super(BilibiliPlatform.BILIBILI, source, title, parentAreaName, areaName);
    }

    public BilibiliRoomInfoChangeEvent(LiveStreamerInfo source, String title, String parentAreaName, String areaName, Instant instant) {
        super(BilibiliPlatform.BILIBILI, source, title, parentAreaName, areaName, instant);
    }
}
