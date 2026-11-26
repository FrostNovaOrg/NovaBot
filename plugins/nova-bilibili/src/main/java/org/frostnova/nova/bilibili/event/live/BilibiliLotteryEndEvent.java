package org.frostnova.nova.bilibili.event.live;

import lombok.Getter;
import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.core.event.live.NovaBaseLiveEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;

/** 收到开奖或结束通知。 */
@Getter
public class BilibiliLotteryEndEvent extends NovaBaseLiveEvent {
    private final Long lotteryId;

    public BilibiliLotteryEndEvent(LiveStreamerInfo source, Long lotteryId) {
        super(BilibiliPlatform.BILIBILI, source);
        this.lotteryId = lotteryId;
    }
}
