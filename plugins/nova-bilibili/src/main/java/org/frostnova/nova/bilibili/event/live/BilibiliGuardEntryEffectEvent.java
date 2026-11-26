package org.frostnova.nova.bilibili.event.live;

import lombok.Getter;
import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.enums.GuardType;
import org.frostnova.nova.core.event.live.NovaBaseLiveEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;

/** 进场特效显示的观众与大航海等级。同一次进房还可能收到进房事件。 */
@Getter
public class BilibiliGuardEntryEffectEvent extends NovaBaseLiveEvent {
    private final long uid;
    private final GuardType guardType;

    public BilibiliGuardEntryEffectEvent(LiveStreamerInfo source, long uid, GuardType guardType) {
        super(BilibiliPlatform.BILIBILI, source);
        this.uid = uid;
        this.guardType = guardType;
    }
}
