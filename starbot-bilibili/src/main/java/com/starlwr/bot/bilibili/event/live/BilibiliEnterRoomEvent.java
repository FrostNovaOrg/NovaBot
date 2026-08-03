package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.common.EnterRoomEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;
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
        super(LivePlatform.BILIBILI, source, sender);
    }

    public BilibiliEnterRoomEvent(LiveStreamerInfo source, UserInfo sender, Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, instant);
    }
}
