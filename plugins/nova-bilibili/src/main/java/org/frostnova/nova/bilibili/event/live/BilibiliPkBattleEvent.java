package org.frostnova.nova.bilibili.event.live;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.core.event.live.common.PkBattleEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.List;

/**
 * 哔哩哔哩 PK 开打与结算事件
 * <p>
 * 三种 PK（老式一对一、限时一对一、多方）都靠同一条场次消息报出开打与结算，
 * 所以这里只有一种事件，用 {@link Stage} 区分两段。
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliPkBattleEvent extends PkBattleEvent {

    public BilibiliPkBattleEvent(LiveStreamerInfo source, Long pkId, Integer pkType, Stage stage,
                                 Integer memberCount, Long planStart, Long planEnd,
                                 Boolean early, Long endedAt, Long myVotes, Integer myRank, Integer myResult,
                                 List<Opponent> opponents) {
        super(BilibiliPlatform.BILIBILI, source, pkId, pkType, stage, memberCount, planStart, planEnd,
                early, endedAt, myVotes, myRank, myResult, opponents);
    }

    public BilibiliPkBattleEvent(LiveStreamerInfo source, Long pkId, Integer pkType, Stage stage,
                                 Integer memberCount, Long planStart, Long planEnd,
                                 Boolean early, Long endedAt, Long myVotes, Integer myRank, Integer myResult,
                                 List<Opponent> opponents, Instant instant) {
        super(BilibiliPlatform.BILIBILI, source, pkId, pkType, stage, memberCount, planStart, planEnd,
                early, endedAt, myVotes, myRank, myResult, opponents, instant);
    }
}
