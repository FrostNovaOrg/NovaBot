package org.frostnova.nova.bilibili.event.live;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.enums.GuardOperateType;
import org.frostnova.nova.core.event.NovaExternalBaseEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 下播时段打赏汇总事件
 * <p>
 * 主播不在播时收到的上舰与礼物并不当场播报，先攒一小阵；一阵停下来（或到上限）后，
 * 把这一阵的人合成一条事件，交给推送处理器按各会话门槛筛选并发送。
 * <p>
 * 下播前十秒的打赏另发提醒事件，使用同一推送设置；没有打赏不发布。
 * <p>
 * 事件类必须留在本包（{@code org.frostnova.nova.bilibili.event.live}）：
 * 「只连有推送的直播间」按事件类的包名前缀判断某个推送目标算不算订阅了直播事件，
 * 挪出这个包后只配这类通知的房间就不会被连上，而现象只是「什么都不推送」。
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliOfflineRewardDigestEvent extends NovaExternalBaseEvent {
    /**
     * 本阵观众，按首次收到打赏的先后排列，一人一条
     */
    private List<Contribution> contributions = new ArrayList<>();

    /** 下播前十秒内打赏的独立提醒。 */
    private boolean closingReminder;

    /** 会话筛选后，超过展示人数上限的观众人数及其合计金额（分）。 */
    private int omittedCount;

    private long omittedAmountFen;

    public BilibiliOfflineRewardDigestEvent(LiveStreamerInfo source, List<Contribution> contributions, Instant instant) {
        super(BilibiliPlatform.BILIBILI, source, instant);
        this.contributions = contributions;
    }

    public BilibiliOfflineRewardDigestEvent(LiveStreamerInfo source, List<Contribution> contributions) {
        super(BilibiliPlatform.BILIBILI, source);
        this.contributions = contributions;
    }

    /**
     * 一个人在这一阵里的打赏
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @ToString
    public static class Contribution {
        /**
         * 观众 UID
         */
        private Long uid;

        /**
         * 昵称，平台没给时为空（渲染时回退到 UID）
         */
        private String uname;

        /**
         * 这一阵礼物合计实付（分），没送过计入口径的礼物时为空。金额一律按「分」整数记账
         */
        private Long giftAmountFen;

        /**
         * 送过的礼物，一种一行
         */
        private List<GiftLine> gifts = new ArrayList<>();

        /**
         * 上舰等级：3 舰长、2 提督、1 总督；没上舰时为空
         */
        private Integer guardLevel;

        /**
         * 开通还是续费；没上舰时为空
         */
        private GuardOperateType operateType;

        /**
         * 上舰实付（分），取不到时为空
         */
        private Long guardAmountFen;

        /** 最后一次上舰的送出时刻。 */
        private Instant guardSentAt;
    }

    /**
     * 一种礼物：名字、这一阵送了几个、合计实付（分）
     */
    public record GiftLine(String name, Integer count, Long amountFen, Instant firstSentAt, Instant lastSentAt) {
        public GiftLine(String name, Integer count, Long amountFen) {
            this(name, count, amountFen, null, null);
        }
    }
}
