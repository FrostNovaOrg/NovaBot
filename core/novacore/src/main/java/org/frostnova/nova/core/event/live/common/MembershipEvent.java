package org.frostnova.nova.core.event.live.common;

import org.frostnova.nova.core.enums.LivePlatform;
import org.frostnova.nova.core.event.live.base.NovaLivePurchaseEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.UserInfo;
import org.frostnova.nova.core.lang.MathUtil;
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
public class MembershipEvent extends NovaLivePurchaseEvent {
    /**
     * 价的口径：两种来源给的价含义不同，构造时必须说清是哪种（实测多月开通样本）
     */
    public enum PriceBasis {
        /**
         * 来源给的是<b>单价</b>（如 {@code GUARD_BUY} 的挂牌价），金额＝单价×数量
         */
        UNIT_PRICE,
        /**
         * 来源给的是<b>总价</b>（如成交价报文的成交总价，实测 3 个月 504 元、12 个月 2016 元），
         * 金额＝总价，<b>不再乘数量</b>——再乘就重了
         */
        ORDER_TOTAL
    }

    /**
     * 单价
     * <p>
     * <b>恒为单价，不随来源口径变。</b> 来源给的是总价时记折出的单价（总价÷数量），
     * 除不尽时按分四舍五入；价或数量取不到、数量小于 1 时记空。<b>金额一律看 {@link NovaLivePurchaseEvent#value}，
     * 不要拿本字段乘数量去算</b>——那正是多月开通被乘重的来路。
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
        this(platform, source, sender, price, count, unit, instant, PriceBasis.UNIT_PRICE);
    }

    /**
     * 按价的口径构造，见 {@link PriceBasis}
     *
     * @param price 单价或总价，由 {@code basis} 说了算；{@link #price} 恒记单价
     */
    public MembershipEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender,
                           Double price, Integer count, String unit, Instant instant, PriceBasis basis) {
        super(platform, source, sender,
                basis == PriceBasis.ORDER_TOTAL ? price : MathUtil.multiply(price, count),
                instant);
        this.price = basis == PriceBasis.ORDER_TOTAL
                ? (price == null || count == null || count < 1 ? null : MathUtil.divide(price, count))
                : price;
        this.count = count;
        this.unit = unit;
    }
}
