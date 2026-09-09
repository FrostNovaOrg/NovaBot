package org.frostnova.nova.bilibili.event.live;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.core.event.live.common.EnterRoomEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 哔哩哔哩进入直播间事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliEnterRoomEvent extends EnterRoomEvent {
    /**
     * 是否由推广位进入
     */
    private boolean fromPromotion;

    /**
     * 推广来源描述
     */
    private String promotionSource;

    public BilibiliEnterRoomEvent(LiveStreamerInfo source, UserInfo sender) {
        super(BilibiliPlatform.BILIBILI, source, sender);
    }

    public BilibiliEnterRoomEvent(LiveStreamerInfo source, UserInfo sender, Instant instant) {
        super(BilibiliPlatform.BILIBILI, source, sender, instant);
    }
}
