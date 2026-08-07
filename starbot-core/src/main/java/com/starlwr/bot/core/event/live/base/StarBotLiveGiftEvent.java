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

    /**
     * 这一笔是否来自背包
     * <p>
     * 背包里的礼物来自红包、活动或签到，观众没有为这一笔花钱，因此 {@code charged} 为 0，
     * 但礼物本身的面值照旧。主播照样会收到分成，展示上也照样应该出现——
     * 按 0 分级会让一个白得的嘉年华落进最低档，主播就永远不知道有人送过。
     * <p>
     * <b>契约：下游要区分背包礼物，一律以本字段为准，不要用金额反推。</b>
     * {@code charged == 0} 目前恰好只有背包这一种来源，但那是<b>当下的巧合而不是约定</b>：
     * 将来只要出现第二种「确实扣了 0」的情形（全额抵扣券、活动免单等），
     * 反推就会把它静默地当成背包礼物，而且不会有任何报错。
     * <p>
     * 另外 {@code charged} 的 {@code 0} 与 {@code null} 语义不同——前者「确实没扣」，
     * 后者「平台没告诉我们」需要回退到面值——下游任何一处不小心做了空值归零，
     * 这个区分就没了。本字段不依赖那个区分。
     */
    private boolean fromBag;

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
