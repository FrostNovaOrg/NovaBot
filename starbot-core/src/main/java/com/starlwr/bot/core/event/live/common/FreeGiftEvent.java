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
 * 免费礼物事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class FreeGiftEvent extends StarBotLiveGiftEvent {
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
