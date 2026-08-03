package com.starlwr.bot.core.event.live.common;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.base.StarBotLivePurchaseEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;
import com.starlwr.bot.core.util.MathUtil;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 开通会员事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class MembershipEvent extends StarBotLivePurchaseEvent {
    /**
     * 单价
     */
    private Double price;

    /**
     * 数量
     */
    private Integer count;

    /**
     * 单位
     */
    private String unit;

    public MembershipEvent(String platform, LiveStreamerInfo source, UserInfo sender, Double price, Integer count, String unit) {
        super(platform, source, sender, MathUtil.multiply(price, count));
        this.price = price;
        this.count = count;
        this.unit = unit;
    }

    public MembershipEvent(String platform, LiveStreamerInfo source, UserInfo sender, Double price, Integer count, String unit, Instant instant) {
        super(platform, source, sender, MathUtil.multiply(price, count), instant);
        this.price = price;
        this.count = count;
        this.unit = unit;
    }

    public MembershipEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, Double price, Integer count, String unit) {
        super(platform, source, sender, MathUtil.multiply(price, count));
        this.price = price;
        this.count = count;
        this.unit = unit;
    }

    public MembershipEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, Double price, Integer count, String unit, Instant instant) {
        super(platform, source, sender, MathUtil.multiply(price, count), instant);
        this.price = price;
        this.count = count;
        this.unit = unit;
    }
}
