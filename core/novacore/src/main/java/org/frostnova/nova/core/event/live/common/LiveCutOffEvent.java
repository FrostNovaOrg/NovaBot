package org.frostnova.nova.core.event.live.common;

import org.frostnova.nova.core.enums.LivePlatform;
import org.frostnova.nova.core.event.live.base.NovaLiveInterventionEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 直播流被平台切断事件
 * <p>
 * 平台切流后紧接着就会下发下播消息，两者相隔通常不到几秒。
 * 本事件由 {@code LiveInterventionTracker} 记住，用于把随后那次下播标记为
 * {@link org.frostnova.nova.core.enums.LiveEndReason#CUT_OFF}。
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class LiveCutOffEvent extends NovaLiveInterventionEvent {
    public LiveCutOffEvent(String platform, LiveStreamerInfo source, String reason) {
        super(platform, source, reason);
    }

    public LiveCutOffEvent(String platform, LiveStreamerInfo source, String reason, Instant instant) {
        super(platform, source, reason, instant);
    }

    public LiveCutOffEvent(LivePlatform platform, LiveStreamerInfo source, String reason) {
        super(platform, source, reason);
    }

    public LiveCutOffEvent(LivePlatform platform, LiveStreamerInfo source, String reason, Instant instant) {
        super(platform, source, reason, instant);
    }
}
