package org.frostnova.nova.core.event.live.base;

import org.frostnova.nova.core.enums.LivePlatform;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 直播间消息事件 (弹幕、表情等)
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class NovaLiveMessageEvent extends NovaLiveInteractionEvent {
    public NovaLiveMessageEvent(String platform, LiveStreamerInfo source, UserInfo sender) {
        super(platform, source, sender);
    }

    public NovaLiveMessageEvent(String platform, LiveStreamerInfo source, UserInfo sender, Instant instant) {
        super(platform, source, sender, instant);
    }

    public NovaLiveMessageEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender) {
        super(platform, source, sender);
    }

    public NovaLiveMessageEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, Instant instant) {
        super(platform, source, sender, instant);
    }
}
