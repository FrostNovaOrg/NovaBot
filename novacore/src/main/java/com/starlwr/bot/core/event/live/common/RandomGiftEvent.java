package com.starlwr.bot.core.event.live.common;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.base.StarBotLiveGiftEvent;
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
 * 随机礼物事件 (盲盒等)
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class RandomGiftEvent extends StarBotLiveGiftEvent {
    /**
     * 随机礼物信息
     */
    private GiftInfo randomGiftInfo;

    /**
     * 总价格
     */
    private Double price;

    /**
     * 盈亏
     */
    private Double profit;

    public RandomGiftEvent(String platform, LiveStreamerInfo source, UserInfo sender, GiftInfo randomGiftInfo, GiftInfo giftInfo) {
        super(platform, source, sender, giftInfo, MathUtil.multiply(giftInfo.getPrice(), giftInfo.getCount()));
        this.randomGiftInfo = randomGiftInfo;
        this.price = MathUtil.multiply(randomGiftInfo.getPrice(), randomGiftInfo.getCount());
        this.profit = MathUtil.subtract(getValue(), price);
    }

    public RandomGiftEvent(String platform, LiveStreamerInfo source, UserInfo sender, GiftInfo randomGiftInfo, GiftInfo giftInfo, Instant instant) {
        super(platform, source, sender, giftInfo, MathUtil.multiply(giftInfo.getPrice(), giftInfo.getCount()), instant);
        this.randomGiftInfo = randomGiftInfo;
        this.price = MathUtil.multiply(randomGiftInfo.getPrice(), randomGiftInfo.getCount());
        this.profit = MathUtil.subtract(getValue(), price);
    }

    public RandomGiftEvent(String platform, LiveStreamerInfo source, UserInfo sender, GiftInfo randomGiftInfo, GiftInfo giftInfo, Double price, Double value) {
        super(platform, source, sender, giftInfo, value);
        this.randomGiftInfo = randomGiftInfo;
        this.price = price;
        this.profit = MathUtil.subtract(value, price);
    }

    public RandomGiftEvent(String platform, LiveStreamerInfo source, UserInfo sender, GiftInfo randomGiftInfo, GiftInfo giftInfo, Double price, Double value, Instant instant) {
        super(platform, source, sender, giftInfo, value, instant);
        this.randomGiftInfo = randomGiftInfo;
        this.price = price;
        this.profit = MathUtil.subtract(value, price);
    }

    public RandomGiftEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, GiftInfo randomGiftInfo, GiftInfo giftInfo) {
        super(platform, source, sender, giftInfo, MathUtil.multiply(giftInfo.getPrice(), giftInfo.getCount()));
        this.randomGiftInfo = randomGiftInfo;
        this.price = MathUtil.multiply(randomGiftInfo.getPrice(), randomGiftInfo.getCount());
        this.profit = MathUtil.subtract(getValue(), price);
    }

    public RandomGiftEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, GiftInfo randomGiftInfo, GiftInfo giftInfo, Instant instant) {
        super(platform, source, sender, giftInfo, MathUtil.multiply(giftInfo.getPrice(), giftInfo.getCount()), instant);
        this.randomGiftInfo = randomGiftInfo;
        this.price = MathUtil.multiply(randomGiftInfo.getPrice(), randomGiftInfo.getCount());
        this.profit = MathUtil.subtract(getValue(), price);
    }

    public RandomGiftEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, GiftInfo randomGiftInfo, GiftInfo giftInfo, Double price, Double value) {
        super(platform, source, sender, giftInfo, value);
        this.randomGiftInfo = randomGiftInfo;
        this.price = price;
        this.profit = MathUtil.subtract(value, price);
    }

    public RandomGiftEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, GiftInfo randomGiftInfo, GiftInfo giftInfo, Double price, Double value, Instant instant) {
        super(platform, source, sender, giftInfo, value, instant);
        this.randomGiftInfo = randomGiftInfo;
        this.price = price;
        this.profit = MathUtil.subtract(value, price);
    }
}
