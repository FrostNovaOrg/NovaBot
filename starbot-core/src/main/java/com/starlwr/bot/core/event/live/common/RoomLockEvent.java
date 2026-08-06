package com.starlwr.bot.core.event.live.common;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.base.StarBotLiveInterventionEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 直播间被封禁事件
 * <p>
 * 比切流更严重：切流只是断掉这一场，封禁期间连开播都不行。
 * 解封时刻由平台给出，取不到时为空——<b>取不到不等于永久封禁</b>，
 * 展示时要如实说明未知，不要替平台下结论。
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class RoomLockEvent extends StarBotLiveInterventionEvent {
    /**
     * 解封时刻，平台未给出或无法解析时为空
     */
    private Instant expireAt;

    public RoomLockEvent(String platform, LiveStreamerInfo source, String reason, Instant expireAt) {
        super(platform, source, reason);
        this.expireAt = expireAt;
    }

    public RoomLockEvent(LivePlatform platform, LiveStreamerInfo source, String reason, Instant expireAt) {
        super(platform, source, reason);
        this.expireAt = expireAt;
    }

    public RoomLockEvent(LivePlatform platform, LiveStreamerInfo source, String reason, Instant expireAt, Instant instant) {
        super(platform, source, reason, instant);
        this.expireAt = expireAt;
    }
}
