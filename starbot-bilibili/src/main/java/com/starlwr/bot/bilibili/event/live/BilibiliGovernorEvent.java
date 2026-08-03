package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.bilibili.enums.GuardOperateType;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.common.MembershipEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 哔哩哔哩总督事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliGovernorEvent extends MembershipEvent {
    /**
     * 开通或续费
     */
    private GuardOperateType operateType = GuardOperateType.UNKNOWN;

    public BilibiliGovernorEvent(LiveStreamerInfo source, UserInfo sender, Double price, Integer count, String unit) {
        super(LivePlatform.BILIBILI, source, sender, price, count, unit);
    }

    public BilibiliGovernorEvent(LiveStreamerInfo source, UserInfo sender, Double price, Integer count, String unit, Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, price, count, unit, instant);
    }
}
