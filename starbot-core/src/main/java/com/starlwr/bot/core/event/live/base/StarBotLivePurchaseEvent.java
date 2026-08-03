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
     * 总价值
     */
    private Double value;

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
