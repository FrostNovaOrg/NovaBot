package org.frostnova.nova.core.event.live.base;

import org.frostnova.nova.core.enums.LivePlatform;
import org.frostnova.nova.core.event.live.NovaBaseLiveEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 直播间连接状态变更事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class NovaLiveConnectionEvent extends NovaBaseLiveEvent {
    public NovaLiveConnectionEvent(String platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public NovaLiveConnectionEvent(String platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }

    public NovaLiveConnectionEvent(LivePlatform platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public NovaLiveConnectionEvent(LivePlatform platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }
}
