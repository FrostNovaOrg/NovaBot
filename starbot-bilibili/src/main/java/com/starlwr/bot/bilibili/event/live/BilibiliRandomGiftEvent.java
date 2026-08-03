package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.common.RandomGiftEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.GiftInfo;
import com.starlwr.bot.core.model.UserInfo;
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
        super(LivePlatform.BILIBILI, source, sender, randomGiftInfo, giftInfo, price, value);
    }

    public BilibiliRandomGiftEvent(LiveStreamerInfo source, UserInfo sender, GiftInfo randomGiftInfo, GiftInfo giftInfo, Double price, Double value, Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, randomGiftInfo, giftInfo, price, value, instant);
    }
}
