package org.frostnova.nova.core.event.live.common;

import org.frostnova.nova.core.enums.LivePlatform;
import org.frostnova.nova.core.event.live.base.NovaLiveConnectionEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 连接成功事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class ConnectedEvent extends NovaLiveConnectionEvent {
    public ConnectedEvent(String platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public ConnectedEvent(String platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }

    public ConnectedEvent(LivePlatform platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public ConnectedEvent(LivePlatform platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }
}
