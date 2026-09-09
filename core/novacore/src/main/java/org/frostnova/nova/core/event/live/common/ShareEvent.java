package org.frostnova.nova.core.event.live.common;

import org.frostnova.nova.core.enums.LivePlatform;
import org.frostnova.nova.core.event.live.base.NovaLiveOperationEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 分享事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class ShareEvent extends NovaLiveOperationEvent {
    public ShareEvent(String platform, LiveStreamerInfo source, UserInfo sender) {
        super(platform, source, sender);
    }

    public ShareEvent(String platform, LiveStreamerInfo source, UserInfo sender, Instant instant) {
        super(platform, source, sender, instant);
    }

    public ShareEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender) {
        super(platform, source, sender);
    }

    public ShareEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, Instant instant) {
        super(platform, source, sender, instant);
    }
}
