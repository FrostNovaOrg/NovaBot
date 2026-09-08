package com.starlwr.bot.core.event.live.base;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.StarBotBaseLiveEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 平台干预事件 (警告、切断直播流、封禁直播间等)
 * <p>
 * 与其他直播事件的区别在于<b>动作发起方是平台而不是观众或主播</b>：
 * 主播往往正在专注直播、并不会立刻看到平台弹窗，而这类事件直接决定这场还能不能播下去，
 * 因此它们走告警通道而不是粉丝群推送。
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class StarBotLiveInterventionEvent extends StarBotBaseLiveEvent {
    /**
     * 平台给出的说明文案，例如「违反直播着装规范，请立即调整」
     */
    private String reason;

    public StarBotLiveInterventionEvent(String platform, LiveStreamerInfo source, String reason) {
        super(platform, source);
        this.reason = reason;
    }

    public StarBotLiveInterventionEvent(String platform, LiveStreamerInfo source, String reason, Instant instant) {
        super(platform, source, instant);
        this.reason = reason;
    }

    public StarBotLiveInterventionEvent(LivePlatform platform, LiveStreamerInfo source, String reason) {
        super(platform, source);
        this.reason = reason;
    }

    public StarBotLiveInterventionEvent(LivePlatform platform, LiveStreamerInfo source, String reason, Instant instant) {
        super(platform, source, instant);
        this.reason = reason;
    }
}
