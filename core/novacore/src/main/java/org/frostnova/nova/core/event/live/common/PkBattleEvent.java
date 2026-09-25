package org.frostnova.nova.core.event.live.common;

import org.frostnova.nova.core.enums.LivePlatform;
import org.frostnova.nova.core.event.live.NovaBaseLiveEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.List;

/**
 * 一场 PK 的开打或结算
 * <p>
 * 一场 PK 在消息里会分两段报：开打那条说定谁跟谁打、什么时候打；结算那条说谁赢了、各拿多少票。
 * 两段各算一次本事件，用 {@link #stage} 分开。
 * <p>
 * <b>票数不是钱。</b>它只是这场 PK 的拉票结果，观众没有为它付款，主播也不会因为票多而多拿一分，
 * 所以本事件<b>刻意不继承 {@link org.frostnova.nova.core.event.live.base.NovaLivePurchaseEvent}</b>——
 * 继承了它就等于宣称「有一笔进项」，任何按事件基类归集的营收口径都会凭空多出一笔根本不存在的收入。
 * <p>
 * 它也<b>没有送礼的人</b>：发起一场 PK 的是主播，不是哪位观众，硬套「谁做了什么」那类事件就得凭空捏一个
 * 人顶上去，而这正是最容易把助攻的观众身份带进账里的一步。所以它直接挂在 {@link NovaBaseLiveEvent} 上，
 * <b>不带任何金额字段</b>，也不带助攻的观众身份：那是观众的人情，不是可以归到人头上的账。
 * <p>
 * 记下它是为了把这一场的对阵与结果留住，供以后的页面回看；它<b>不参与</b>本场任何指标的累计。
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class PkBattleEvent extends NovaBaseLiveEvent {

    /**
     * 阶段：开打，或结算
     */
    public enum Stage {
        /** 开打：这一场刚配好，说的是打算怎么打 */
        OPEN,
        /** 结算：这一场打完了，说的是结果 */
        SETTLE
    }

    /**
     * 一位对手
     *
     * @param roomId 对手的房间号
     * @param uid    对手的主播 uid
     * @param votes  对手的票数，只在结算时有
     * @param rank   对手的名次，只在结算时有
     */
    public record Opponent(Long roomId, Long uid, Long votes, Integer rank) {
    }

    /**
     * 场次号
     * <p>
     * 同一场 PK 的开打与结算带同一个号，按它把两段对起来。
     */
    private Long pkId;

    /**
     * PK 类型：老式一对一、限时一对一、多方
     */
    private Integer pkType;

    /**
     * 这一条说的是开打还是结算
     */
    private Stage stage;

    /**
     * 这一场一共几家
     */
    private Integer memberCount;

    /**
     * 计划开始时刻，秒
     */
    private Long planStart;

    /**
     * 计划结束时刻，秒
     * <p>
     * 只在开打那条上取，结算那条不看它：有些 PK 提前收场时它会被改写成实际结束时刻，
     * 当成「计划」读就名不副实了。
     */
    private Long planEnd;

    /**
     * 是不是提前结算，只在结算时有
     */
    private Boolean early;

    /**
     * 实际结束时刻，秒，只在结算时有
     * <p>
     * 取结算那条消息自己的时刻，不取场次里那个结束时刻——提前收场时后者仍然写着原计划。
     */
    private Long endedAt;

    /**
     * 本房的票数，只在结算时有
     */
    private Long myVotes;

    /**
     * 本房的名次，只在结算时有
     */
    private Integer myRank;

    /**
     * 本房的结果：1 胜、0 负、2 平，只在结算时有
     * <p>
     * <b>照平台判的结果读，不按票数推。</b>提前收场时会出现 0:0 却照样分出胜负的情况，
     * 按票数推会把这场记成平局，而平台判的就是有胜负。
     */
    private Integer myResult;

    /**
     * 其余各家，按报文里的顺序
     * <p>
     * 本房不在其中：这一栏就是「对手」。开打那条只有房间号与主播 uid，结算那条另有票数与名次。
     */
    private List<Opponent> opponents;

    public PkBattleEvent(LivePlatform platform, LiveStreamerInfo source, Long pkId, Integer pkType, Stage stage,
                         Integer memberCount, Long planStart, Long planEnd,
                         Boolean early, Long endedAt, Long myVotes, Integer myRank, Integer myResult,
                         List<Opponent> opponents) {
        super(platform, source);
        this.pkId = pkId;
        this.pkType = pkType;
        this.stage = stage;
        this.memberCount = memberCount;
        this.planStart = planStart;
        this.planEnd = planEnd;
        this.early = early;
        this.endedAt = endedAt;
        this.myVotes = myVotes;
        this.myRank = myRank;
        this.myResult = myResult;
        this.opponents = opponents;
    }

    public PkBattleEvent(LivePlatform platform, LiveStreamerInfo source, Long pkId, Integer pkType, Stage stage,
                         Integer memberCount, Long planStart, Long planEnd,
                         Boolean early, Long endedAt, Long myVotes, Integer myRank, Integer myResult,
                         List<Opponent> opponents, Instant instant) {
        super(platform, source, instant);
        this.pkId = pkId;
        this.pkType = pkType;
        this.stage = stage;
        this.memberCount = memberCount;
        this.planStart = planStart;
        this.planEnd = planEnd;
        this.early = early;
        this.endedAt = endedAt;
        this.myVotes = myVotes;
        this.myRank = myRank;
        this.myResult = myResult;
        this.opponents = opponents;
    }
}
