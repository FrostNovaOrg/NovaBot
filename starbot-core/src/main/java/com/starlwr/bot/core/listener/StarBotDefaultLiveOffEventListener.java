package com.starlwr.bot.core.listener;

import com.starlwr.bot.core.enums.LiveEndReason;
import com.starlwr.bot.core.event.live.common.LiveOffEvent;
import com.starlwr.bot.core.model.LiveSession;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.LiveInterventionTracker;
import com.starlwr.bot.core.service.LiveRoomInfoHistory;
import com.starlwr.bot.core.service.LiveSessionArchive;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * StarBot 下播事件监听器
 */
@Slf4j
@Component
public class StarBotDefaultLiveOffEventListener {
    private final LiveDataService liveDataService;

    private final LiveSessionArchive archive;

    private final LiveInterventionTracker interventionTracker;

    private final LiveRoomInfoHistory roomInfoHistory;

    @Autowired
    public StarBotDefaultLiveOffEventListener(LiveDataService liveDataService, LiveSessionArchive archive,
                                              LiveInterventionTracker interventionTracker, LiveRoomInfoHistory roomInfoHistory) {
        this.liveDataService = liveDataService;
        this.archive = archive;
        this.interventionTracker = interventionTracker;
        this.roomInfoHistory = roomInfoHistory;
    }

    /**
     * 更新房间数据
     * @param event 事件
     */
    @Order(-10000)
    @EventListener
    public void onLiveOffEvent(LiveOffEvent event) {
        log.info("[{}] [下播] {}(UID: {}, 房间号: {})", event.getPlatform(), event.getSource().getUname(), event.getSource().getUid(), event.getSource().getRoomIdString());

        liveDataService.setLiveStatus(event.getPlatform(), event.getSource().getUid(), false);
        liveDataService.setLiveEndTime(event.getPlatform(), event.getSource().getUid(), event.getTimestamp());

        // 归档与并入累计读的是同一份尚未清零的本场数据，互不影响，先后无所谓；
        // 但两者都必须赶在**开播清零之前**，也就是趁下播这一刻做掉
        archiveSession(event);

        // 本场数据并入累计。选在下播而非开播清零前，是因为程序可能在两场之间重启，
        // 拖到开播才并入会整场丢失。本场数据本身保留到下次开播，报告仍读得到
        liveDataService.mergeLiveDataIntoTotal(event.getPlatform(), event.getSource().getUid());
    }

    /**
     * 把本场直播归档，供运营分析
     * <p>
     * 没有开播时间就不归档：一条没有起点的记录既算不出时长，也无法归入任何统计周期，
     * 留着只会污染分析结果。这种情况多见于程序在直播中途才启动。
     */
    private void archiveSession(LiveOffEvent event) {
        LiveStreamerInfo source = event.getSource();
        Optional<Long> start = liveDataService.getLiveStartTime(event.getPlatform(), source.getUid());
        if (start.isEmpty()) {
            log.info("{} 没有记录到开播时间, 本场不归档（多为程序在直播中途才启动）", source.getUname());
            return;
        }

        long endTime = event.getTimestamp();
        // 时钟回拨或数据异常会让时长成为负数，夹到 0 而不是让统计里出现负值
        long duration = Math.max(0, (endTime - start.get()) / 1000);

        LiveEndReason endReason = interventionTracker.endReason(
                event.getPlatform(), source.getUid(), Instant.ofEpochMilli(endTime));
        if (endReason != LiveEndReason.NORMAL) {
            log.warn("{} 本场直播{}, 时长 {} 秒不代表正常水平", source.getUname(), endReason.getDescription(), duration);
        }

        // 本场之内程序停过的时段，只算与本场重叠的部分
        long gap = liveDataService.downtimeWithin(start.get(), endTime) / 1000;
        if (gap > 0) {
            log.warn("{} 本场有 {} 秒因程序停机未采集, 各项计数只是下界", source.getUname(), gap);
        }

        // 本场之内**这个直播间自己**断线的时段。与上面的程序停机分开算、分开存：
        // 停机期间所有房间都在断，两段必然重叠，加起来就是重复计数
        long outage = liveDataService.roomOutageWithin(event.getPlatform(), source.getUid(), start.get(), endTime) / 1000;
        if (outage > 0) {
            log.warn("{} 本场有 {} 秒因直播间断线未采集, 各项计数只是下界", source.getUname(), outage);
        }

        archive.append(new LiveSession(
                event.getPlatform(),
                source.getUid(),
                source.getUname(),
                source.getRoomId(),
                start.get(),
                endTime,
                duration,
                liveDataService.getLiveMetrics(event.getPlatform(), source.getUid()),
                liveDataService.getLiveMetricUserCounts(event.getPlatform(), source.getUid()),
                endReason,
                roomInfoHistory.history(event.getPlatform(), source.getUid()),
                gap,
                // F5 名单。⚠️ 它与上面的人数是**两次独立调用**，各自持锁但彼此之间没有原子性：
                // 两次之间恰好又来一个人，size 与名单长度就会差 1。
                // 这里**不**用名单反推人数——`getLiveMetricUserSets` 是带默认实现的接口方法，
                // 自定义实现可能只覆盖了人数那一个，反推会把人数变成 0。
                // 差 1 的代价（分析侧一个计数偏差）远小于把人数打成 0。
                liveDataService.getLiveMetricUserSets(event.getPlatform(), source.getUid()),
                outage));
    }
}
