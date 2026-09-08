package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.bilibili.BilibiliPlatform;
import com.starlwr.bot.core.event.live.common.ConnectedEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 哔哩哔哩直播间连接成功事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliConnectedEvent extends ConnectedEvent {
    public BilibiliConnectedEvent(LiveStreamerInfo source) {
        super(BilibiliPlatform.BILIBILI, source);
    }

    public BilibiliConnectedEvent(LiveStreamerInfo source, Instant instant) {
        super(BilibiliPlatform.BILIBILI, source, instant);
    }
}
