package com.starlwr.bot.core.event.live.base;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.model.GiftInfo;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;
import com.starlwr.bot.core.util.MathUtil;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 直播间礼物事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class StarBotLiveGiftEvent extends StarBotLivePurchaseEvent {
    /**
     * 礼物信息
     */
    private GiftInfo giftInfo;

    public StarBotLiveGiftEvent(String platform, LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo) {
        super(platform, source, sender, MathUtil.multiply(giftInfo.getPrice(), giftInfo.getCount()));
        this.giftInfo = giftInfo;
    }

    public StarBotLiveGiftEvent(String platform, LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo, Instant instant) {
        super(platform, source, sender, MathUtil.multiply(giftInfo.getPrice(), giftInfo.getCount()), instant);
        this.giftInfo = giftInfo;
    }

    public StarBotLiveGiftEvent(String platform, LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo, Double value) {
        super(platform, source, sender, value);
        this.giftInfo = giftInfo;
    }

    public StarBotLiveGiftEvent(String platform, LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo, Double value, Instant instant) {
        super(platform, source, sender, value, instant);
        this.giftInfo = giftInfo;
    }

    public StarBotLiveGiftEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo) {
        super(platform, source, sender, MathUtil.multiply(giftInfo.getPrice(), giftInfo.getCount()));
        this.giftInfo = giftInfo;
    }

    public StarBotLiveGiftEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo, Instant instant) {
        super(platform, source, sender, MathUtil.multiply(giftInfo.getPrice(), giftInfo.getCount()), instant);
        this.giftInfo = giftInfo;
    }

    public StarBotLiveGiftEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo, Double value) {
        super(platform, source, sender, value);
        this.giftInfo = giftInfo;
    }

    public StarBotLiveGiftEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo, Double value, Instant instant) {
        super(platform, source, sender, value, instant);
        this.giftInfo = giftInfo;
    }
}
