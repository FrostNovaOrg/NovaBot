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
 * 直播违规警告事件
 * <p>
 * 警告是切流之前的最后一次提醒，处理及时就不会演变成切流，因此它值得单独发出去，
 * 而不是等真被切了再说。
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class LiveWarningEvent extends NovaLiveInterventionEvent {
    public LiveWarningEvent(String platform, LiveStreamerInfo source, String reason) {
        super(platform, source, reason);
    }

    public LiveWarningEvent(String platform, LiveStreamerInfo source, String reason, Instant instant) {
        super(platform, source, reason, instant);
    }

    public LiveWarningEvent(LivePlatform platform, LiveStreamerInfo source, String reason) {
        super(platform, source, reason);
    }

    public LiveWarningEvent(LivePlatform platform, LiveStreamerInfo source, String reason, Instant instant) {
        super(platform, source, reason, instant);
    }
}
