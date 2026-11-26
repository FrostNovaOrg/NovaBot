package org.frostnova.nova.bilibili.event.live;

import lombok.Getter;
import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.core.event.live.NovaBaseLiveEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;

import java.time.Instant;

/** 天选开始。平台未提供倒计时时，预计结束时刻为空。 */
@Getter
public class BilibiliLotteryStartEvent extends NovaBaseLiveEvent {
    private final Long lotteryId;
    private final Instant expectedEnd;

    public BilibiliLotteryStartEvent(LiveStreamerInfo source, Long lotteryId, Instant start, Instant expectedEnd) {
        super(BilibiliPlatform.BILIBILI, source, start);
        this.lotteryId = lotteryId;
        this.expectedEnd = expectedEnd;
    }
}
