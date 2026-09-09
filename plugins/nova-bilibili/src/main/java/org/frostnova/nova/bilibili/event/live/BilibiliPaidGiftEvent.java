package org.frostnova.nova.bilibili.event.live;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.core.event.live.common.PaidGiftEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.GiftInfo;
import org.frostnova.nova.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 哔哩哔哩付费礼物事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliPaidGiftEvent extends PaidGiftEvent {
    public BilibiliPaidGiftEvent(LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo, Double value) {
        super(BilibiliPlatform.BILIBILI, source, sender, giftInfo, value);
    }

    public BilibiliPaidGiftEvent(LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo, Double value, Instant instant) {
        super(BilibiliPlatform.BILIBILI, source, sender, giftInfo, value, instant);
    }
}
