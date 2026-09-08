package com.starlwr.bot.core.event.live.common;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.base.StarBotLiveInteractionEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 红包事件
 * <p>
 * 观众自掏腰包在直播间发红包，抽中的人拿到电池或礼物。
 * <p>
 * <b>主播不会因为这一笔有任何收益。</b>钱进的是红包，只有中奖者把奖品换成礼物送出，
 * 主播才参与分成。所以本事件<b>刻意不继承 {@link com.starlwr.bot.core.event.live.base.StarBotLivePurchaseEvent}</b>——
 * 它没有「主播收到多少」这个口径，算进营收就会凭空多出一笔主播根本没拿到的钱。
 * <p>
 * 但它<b>值得播报</b>：红包会把人吸引进直播间，主播通常都会当场感谢送红包的人。
 * 「值得感谢」与「值钱」不是一回事，这是最典型的例子。
 * <p>
 * 还有一层容易被忽略的错位：中奖者随后送出的礼物<b>会</b>被记成他自己的消费，
 * 而钱其实是发红包的人出的。消费排行榜因此会排进没花钱的人，
 * 真正出钱的那位反而不出现——这个错误不会体现在任何总额校验里。
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class RedPocketEvent extends StarBotLiveInteractionEvent {
    /**
     * 红包的唯一标识
     * <p>
     * 用于去重：<b>开启消息会被周期性重播</b>，实测同一个红包在临到期时又播了一次
     * （报文里 {@code start_time} 是十分钟前，{@code current_time} 是当下）。
     * 不按它去重，一个红包会被感谢很多次。
     */
    private String lotteryId;

    /**
     * 发红包的人为此花掉的金额，单位：元
     * <p>
     * 这是<b>送红包者的支出</b>，不是主播的收入，两者不可混用。
     */
    private Double cost;

    /**
     * 奖品名称，如「电池红包」
     */
    private String awardName;

    /**
     * 奖品数量
     */
    private Integer awardCount;

    public RedPocketEvent(String platform, LiveStreamerInfo source, UserInfo sender) {
        super(platform, source, sender);
    }

    public RedPocketEvent(String platform, LiveStreamerInfo source, UserInfo sender, Instant instant) {
        super(platform, source, sender, instant);
    }

    public RedPocketEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender) {
        super(platform, source, sender);
    }

    public RedPocketEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, Instant instant) {
        super(platform, source, sender, instant);
    }
}
