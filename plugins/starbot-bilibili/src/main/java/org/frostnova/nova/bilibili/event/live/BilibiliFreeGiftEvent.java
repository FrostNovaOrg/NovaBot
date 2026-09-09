package org.frostnova.nova.bilibili.event.live;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.core.event.live.common.FreeGiftEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.GiftInfo;
import org.frostnova.nova.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 哔哩哔哩免费礼物事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliFreeGiftEvent extends FreeGiftEvent {
    public BilibiliFreeGiftEvent(LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo) {
        super(BilibiliPlatform.BILIBILI, source, sender, giftInfo);
    }

    public BilibiliFreeGiftEvent(LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo, Instant instant) {
        super(BilibiliPlatform.BILIBILI, source, sender, giftInfo, instant);
    }
}
