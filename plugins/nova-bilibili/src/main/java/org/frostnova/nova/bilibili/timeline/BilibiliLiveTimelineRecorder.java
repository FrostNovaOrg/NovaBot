package org.frostnova.nova.bilibili.timeline;

import org.frostnova.nova.bilibili.event.live.BilibiliLiveOffEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliLiveOnEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.timeline.TimelineEventType;
import org.frostnova.nova.core.timeline.TimelineWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

/**
 * 把哔哩哔哩主播的开播、下播记进时间线
 * <p>
 * 「昨晚怎么一条开播推送都没有」——先得知道昨晚开播这件事本身有没有发生。
 * 推送那一头的记事答不了这一问：推送被关掉、额度用尽、通道挂了，都会让推送流水断掉，
 * 而开播照常发生。开播下播两条一记，推送流水上的空白才分得清是
 * 「没开播」还是「开播了但没推出去」。
 * <p>
 * <b>单独一个类，不挂在推送处理器上。</b>推送处理器天生可停（关推送、停用目标），
 * 顺手挂进去会让「记开播」的存亡取决于「推不推」——推送停掉的那天，
 * 时间线上少的只是一类记录，没有任何红会替人发现。
 * <p>
 * 只记事，不打接口：标题与封面不在事件里，要拿得再调一次直播间的接口，
 * 为一条流水去打外网不值当（要标题的那一路自己去拿，见开播推送处理器）。
 */
@Slf4j
@NovaComponent
public class BilibiliLiveTimelineRecorder {
    /**
     * 早于重置本场数据（-10000）和推送（0）。
     * 同一场开播里那些步骤若出错，这一条已经落下，不会被一起丢掉。
     */
    private static final int BEFORE_THE_REST = -10001;

    private final TimelineWriter timeline;

    @Autowired
    public BilibiliLiveTimelineRecorder(TimelineWriter timeline) {
        this.timeline = timeline;
    }

    /**
     * 开播
     * @param event 开播事件
     */
    @Order(BEFORE_THE_REST)
    @EventListener
    public void onLiveOn(BilibiliLiveOnEvent event) {
        record(TimelineEventType.LIVE_ON, "开播了", event.getSource());
    }

    /**
     * 下播
     * @param event 下播事件
     */
    @Order(BEFORE_THE_REST)
    @EventListener
    public void onLiveOff(BilibiliLiveOffEvent event) {
        record(TimelineEventType.LIVE_OFF, "下播了", event.getSource());
    }

    /**
     * 记一条开播或下播
     * <p>
     * 主播一栏写主播名——日志页上「谁开播了」是按主播筛的；名字不在事件里时写房间号，
     * 不为一条流水去打接口补（见类注释）。uid 另记进 detail：主播会改名，
     * 「昨晚那个号今天叫什么」得靠不变的 uid 才答得出来。
     * 自己出错只写日志，不往外抛，免得把后面的推送也拦住。
     * @param type 开播或下播
     * @param verb 一句人话的后半截
     * @param source 事件里的主播信息
     */
    private void record(TimelineEventType type, String verb, LiveStreamerInfo source) {
        try {
            String name = source.getUname() == null || source.getUname().isBlank()
                    ? "房间 " + source.getRoomIdString()
                    : source.getUname();

            timeline.record(TimelineEvent.of(type, TimelineEvent.Level.INFO)
                    .streamer(name)
                    .text(name + verb)
                    .detail("uid", String.valueOf(source.getUid()))
                    .detail("room", source.getRoomIdString())
                    .build());
        } catch (RuntimeException e) {
            log.error("记下开播或下播失败", e);
        }
    }
}
