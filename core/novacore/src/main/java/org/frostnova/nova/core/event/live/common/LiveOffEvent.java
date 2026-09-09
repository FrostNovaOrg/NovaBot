package org.frostnova.nova.core.event.live.common;

import org.frostnova.nova.core.enums.LivePlatform;
import org.frostnova.nova.core.event.live.base.NovaLiveStatusChangeEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 下播事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class LiveOffEvent extends NovaLiveStatusChangeEvent {
    public LiveOffEvent(String platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public LiveOffEvent(String platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }

    public LiveOffEvent(LivePlatform platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public LiveOffEvent(LivePlatform platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }
}
