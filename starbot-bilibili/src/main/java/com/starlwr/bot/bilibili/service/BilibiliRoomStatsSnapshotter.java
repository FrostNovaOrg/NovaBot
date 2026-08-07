package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.bilibili.health.BilibiliRiskMetrics;
import com.starlwr.bot.bilibili.model.BilibiliLiveMetric;
import com.starlwr.bot.bilibili.model.Room;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.common.LiveOnEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.LiveRoomInfoHistory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

import java.util.ArrayList;
import java.util.List;

/**
 * 开播时的直播间数据快照
 * <p>
 * 粉丝数、粉丝团人数、大航海人数都不在弹幕流里，只能主动去问接口。开播时记下一份，
 * 报告绘制时再取一次实时值，两者相减就是这场直播的涨幅。
 * <p>
 * <b>顺序上必须晚于清零。</b>核心的 {@code StarBotDefaultLiveOnEventListener}
 * 用 {@code @Order(-10000)} 抢在最前面重置本场数据，本监听器不声明顺序，
 * 因而排在其后——反过来的话，刚记下的快照会被立刻抹掉。
 */
@Slf4j
@StarBotComponent
public class BilibiliRoomStatsSnapshotter {
    private final LiveDataService liveDataService;

    private final BilibiliApiUtil api;

    private final LiveRoomInfoHistory roomInfoHistory;

    private final BilibiliRiskMetrics riskMetrics;

    @Autowired
    public BilibiliRoomStatsSnapshotter(LiveDataService liveDataService, BilibiliApiUtil api, LiveRoomInfoHistory roomInfoHistory, BilibiliRiskMetrics riskMetrics) {
        this.liveDataService = liveDataService;
        this.api = api;
        this.roomInfoHistory = roomInfoHistory;
        this.riskMetrics = riskMetrics;
    }

    /**
     * 开播时记下粉丝数、粉丝团人数与大航海人数
     */
    @Order(0)
    @EventListener(LiveOnEvent.class)
    public void onLiveOn(LiveOnEvent event) {
        LiveStreamerInfo source = event.getSource();
        if (source == null || source.getUid() == null) {
            return;
        }
        if (!LivePlatform.BILIBILI.getName().equals(event.getPlatform())) {
            return;
        }
        if (event.isReconnect()) {
            // 断线重连不算新的一场，本场数据没有清零，快照自然也不该被改写——
            // 否则涨幅的基准会在直播中途悄悄挪位
            return;
        }

        String platform = event.getPlatform();
        Long uid = source.getUid();

        // 三个接口各自失败互不影响：拿到几项就记几项，报告里只展示记到的那几项。
        // 但「少记了一项」必须被发现——它的表现是报告里那张卡整个消失，数值不会变成 0，
        // 按数值告警永远不会触发，所以这里按「应记 N 项、实记 M 项」计数
        int expected = 0;
        int recorded = 0;
        List<String> missing = new ArrayList<>();

        expected++;
        if (api.getFansCount(uid).map(fans -> {
            liveDataService.setLiveMetric(platform, uid, BilibiliLiveMetric.FANS_AT_START, fans);
            return true;
        }).orElse(false)) {
            recorded++;
        } else {
            missing.add("粉丝数");
        }

        expected++;
        if (api.getFansMedalCount(uid).map(medal -> {
            liveDataService.setLiveMetric(platform, uid, BilibiliLiveMetric.FANS_MEDAL_AT_START, medal);
            return true;
        }).orElse(false)) {
            recorded++;
        } else {
            missing.add("粉丝团");
        }

        if (source.getRoomId() != null) {
            expected++;
            if (api.getGuardCount(source.getRoomId(), uid).map(guard -> {
                liveDataService.setLiveMetric(platform, uid, BilibiliLiveMetric.GUARD_AT_START, guard);
                return true;
            }).orElse(false)) {
                recorded++;
            } else {
                missing.add("大航海");
            }

            recordInitialTitle(platform, uid, source, event.getTimestamp());
        }

        if (recorded < expected) {
            riskMetrics.record(BilibiliRiskMetrics.Kind.SNAPSHOT_MISSING,
                    String.format("%s 开播快照应记 %d 项、实记 %d 项，缺 %s",
                            source.getUname(), expected, recorded, String.join("、", missing)));
            log.warn("{} 开播快照缺失 {} 项: {}", source.getUname(), expected - recorded, String.join("、", missing));
        } else {
            log.debug("已记录 {} 开播时的粉丝与大航海快照（{} 项）", source.getUname(), recorded);
        }
    }

    /**
     * 记下开播时的标题与分区，作为标题变更记录的起点
     * <p>
     * {@code ROOM_CHANGE} 只在改动发生时下发，拿不到开播时的原始标题。
     * 没有这个起点，报告只能说「改成了 X」而说不出「从什么改成 X」。
     * <p>
     * 拉不到就算了：少一个起点只是让第一条变更看起来像初始值，而让开播流程为此失败得不偿失。
     */
    private void recordInitialTitle(String platform, Long uid, LiveStreamerInfo source, long at) {
        try {
            Room room = api.getLiveInfoByRoomId(source.getRoomId());
            roomInfoHistory.record(platform, uid, at, room.getTitle(), "");
        } catch (Exception e) {
            log.debug("获取 {} 开播时的直播间标题失败: {}", source.getUname(), e.getMessage());
        }
    }
}
