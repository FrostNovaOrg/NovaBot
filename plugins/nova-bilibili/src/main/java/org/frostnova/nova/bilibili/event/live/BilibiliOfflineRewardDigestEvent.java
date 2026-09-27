package org.frostnova.nova.bilibili.event.live;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.enums.GuardOperateType;
import org.frostnova.nova.core.event.NovaExternalBaseEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

/**
 * 下播时段打赏汇总事件
 * <p>
 * 主播不在播时收到的上舰与礼物并不当场播报，先攒一小阵；一阵停下来（或到上限）后，
 * 把这一阵里够格播报的人合成这一条事件，交给推送处理器发出一条消息。
 * <p>
 * 够格是按人的：礼物合计够 100 元、或这一阵里上过舰，两者占其一就够；
 * 一个够格的人都没有时这条事件根本不会发布。
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
     * 够格播报的人，按够格先后排列，一人一条
     */
    private List<Contribution> contributions = new ArrayList<>();

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
    }

    /**
     * 一种礼物：名字、这一阵送了几个、合计实付（分）
     */
    public record GiftLine(String name, Integer count, Long amountFen) {
    }
}
