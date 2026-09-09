package org.frostnova.nova.core.event.live.base;

import org.frostnova.nova.core.enums.LivePlatform;
import org.frostnova.nova.core.event.live.NovaBaseLiveEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 直播间操作事件 (进房、关注、分享等)
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class NovaLiveOperationEvent extends NovaBaseLiveEvent {
    /**
     * 观众信息
     */
    private UserInfo sender;

    public NovaLiveOperationEvent(String platform, LiveStreamerInfo source, UserInfo sender) {
        super(platform, source);
        this.sender = sender;
    }

    public NovaLiveOperationEvent(String platform, LiveStreamerInfo source, UserInfo sender, Instant instant) {
        super(platform, source, instant);
        this.sender = sender;
    }

    public NovaLiveOperationEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender) {
        super(platform, source);
        this.sender = sender;
    }

    public NovaLiveOperationEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, Instant instant) {
        super(platform, source, instant);
        this.sender = sender;
    }
}
