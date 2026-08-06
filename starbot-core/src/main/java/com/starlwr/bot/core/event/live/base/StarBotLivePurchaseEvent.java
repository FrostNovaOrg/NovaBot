package com.starlwr.bot.core.event.live.base;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 直播间消费事件 (礼物、会员等)
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class StarBotLivePurchaseEvent extends StarBotLiveInteractionEvent {
    /**
     * 总价值，即<b>主播这一笔收到了多少</b>
     */
    private Double value;

    /**
     * <b>实扣</b>：平台为这一笔从购买者账上实际扣除的金额（折算为元），取不到时为空
     * <p>
     * <b>这不是「观众花了多少人民币」。</b>扣掉的是账上的余额，而余额可能是白来的
     * （抢红包、活动、签到），也可能是以某个充值优惠价买来的。
     * <b>「观众自己掏了多少真钱」在直播间的数据里根本不存在</b>，
     * 那笔充值交易不经过直播间——所以不要试图去算它，也不要把本字段当成它。
     * <p>
     * <b>与 {@link #value} 不是一回事，尽管多数时候数值相同。</b>
     * 普通礼物两者相等；盲盒是「扣了盲盒的钱、主播收到开出物的价值」；
     * 背包礼物则是扣 0 而主播照样有收益——礼物是抢红包、活动或签到白得的，钱是别人出的。
     * <p>
     * 为空表示该平台或该类事件给不出这个数。此时应回退到 {@link #value} 而不是当作 0：
     * <b>把「不知道」记成「没扣钱」会让营收凭空少一截，而且不会有任何报错。</b>
     */
    private Double charged;

    public StarBotLivePurchaseEvent(String platform, LiveStreamerInfo source, UserInfo sender, Double value) {
        super(platform, source, sender);
        this.value = value;
    }

    public StarBotLivePurchaseEvent(String platform, LiveStreamerInfo source, UserInfo sender, Double value, Instant instant) {
        super(platform, source, sender, instant);
        this.value = value;
    }

    public StarBotLivePurchaseEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, Double value) {
        super(platform, source, sender);
        this.value = value;
    }

    public StarBotLivePurchaseEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, Double value, Instant instant) {
        super(platform, source, sender, instant);
        this.value = value;
    }
}
