package com.starlwr.bot.core.event.live.common;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.base.StarBotLiveGiftEvent;
import com.starlwr.bot.core.model.GiftInfo;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 付费礼物事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class PaidGiftEvent extends StarBotLiveGiftEvent {
    public PaidGiftEvent(String platform, LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo) {
        super(platform, source, sender, giftInfo);
    }

    public PaidGiftEvent(String platform, LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo, Instant instant) {
        super(platform, source, sender, giftInfo, instant);
    }

    public PaidGiftEvent(String platform, LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo, Double value) {
        super(platform, source, sender, giftInfo, value);
    }

    public PaidGiftEvent(String platform, LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo, Double value, Instant instant) {
        super(platform, source, sender, giftInfo, value, instant);
    }

    public PaidGiftEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo) {
        super(platform, source, sender, giftInfo);
    }

    public PaidGiftEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo, Instant instant) {
        super(platform, source, sender, giftInfo, instant);
    }

    public PaidGiftEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo, Double value) {
        super(platform, source, sender, giftInfo, value);
    }

    public PaidGiftEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo, Double value, Instant instant) {
        super(platform, source, sender, giftInfo, value, instant);
    }
}
