package org.frostnova.nova.bilibili.event.live;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.enums.GuardOperateType;
import org.frostnova.nova.core.event.live.common.MembershipEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.UserInfo;
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
        super(BilibiliPlatform.BILIBILI, source, sender, price, count, unit);
    }

    public BilibiliGovernorEvent(LiveStreamerInfo source, UserInfo sender, Double price, Integer count, String unit, Instant instant) {
        super(BilibiliPlatform.BILIBILI, source, sender, price, count, unit, instant);
    }
}
