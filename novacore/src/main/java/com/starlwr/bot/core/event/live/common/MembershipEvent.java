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

    /**
     * 这位观众陪伴主播的天数，取不到时为空
     * <p>
     * 平台把它写在播报文案里而不是给一个字段（如「今天是TA陪伴主播的第 1171 天」），
     * 所以只能从文本里解析。<b>文案随时可能改版</b>，解析不出来时必须留空，
     * <b>绝不能填 0</b>——「陪伴 0 天」会作为假信息出现在感谢文案与报告里，
     * 比没有这个信息糟得多。
     * <p>
     * 这是整条报文里最有感情价值的信息，比金额更值得展示。
     */
    private Integer companionDays;

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
