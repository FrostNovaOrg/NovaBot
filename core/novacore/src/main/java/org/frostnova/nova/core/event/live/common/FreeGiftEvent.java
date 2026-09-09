package org.frostnova.nova.core.event.live.common;

import org.frostnova.nova.core.enums.LivePlatform;
import org.frostnova.nova.core.event.live.base.NovaLiveGiftEvent;
import org.frostnova.nova.core.model.GiftInfo;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 免费礼物事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class FreeGiftEvent extends NovaLiveGiftEvent {
    public FreeGiftEvent(String platform, LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo) {
        super(platform, source, sender, giftInfo, 0D);
    }

    public FreeGiftEvent(String platform, LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo, Instant instant) {
        super(platform, source, sender, giftInfo, 0D, instant);
    }

    public FreeGiftEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo) {
        super(platform, source, sender, giftInfo, 0D);
    }

    public FreeGiftEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo, Instant instant) {
        super(platform, source, sender, giftInfo, 0D, instant);
    }
}
