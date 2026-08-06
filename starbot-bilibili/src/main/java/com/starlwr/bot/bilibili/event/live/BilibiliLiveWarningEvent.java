package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.common.LiveWarningEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
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
        super(LivePlatform.BILIBILI, source, reason);
    }

    public BilibiliLiveWarningEvent(LiveStreamerInfo source, String reason, Instant instant) {
        super(LivePlatform.BILIBILI, source, reason, instant);
    }
}
