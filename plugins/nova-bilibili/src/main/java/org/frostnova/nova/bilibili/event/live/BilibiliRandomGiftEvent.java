package org.frostnova.nova.bilibili.event.live;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.core.event.live.common.RandomGiftEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.GiftInfo;
import org.frostnova.nova.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 哔哩哔哩盲盒礼物事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliRandomGiftEvent extends RandomGiftEvent {
    public BilibiliRandomGiftEvent(LiveStreamerInfo source, UserInfo sender, GiftInfo randomGiftInfo, GiftInfo giftInfo, Double price, Double value) {
        super(BilibiliPlatform.BILIBILI, source, sender, randomGiftInfo, giftInfo, price, value);
    }

    public BilibiliRandomGiftEvent(LiveStreamerInfo source, UserInfo sender, GiftInfo randomGiftInfo, GiftInfo giftInfo, Double price, Double value, Instant instant) {
        super(BilibiliPlatform.BILIBILI, source, sender, randomGiftInfo, giftInfo, price, value, instant);
    }
}
