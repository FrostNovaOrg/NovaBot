package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.bilibili.model.BilibiliLiveMetric;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.common.LiveOnEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.LiveDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

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

    @Autowired
    public BilibiliRoomStatsSnapshotter(LiveDataService liveDataService, BilibiliApiUtil api) {
        this.liveDataService = liveDataService;
        this.api = api;
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

        // 三个接口各自失败互不影响：拿到几项就记几项，报告里只展示记到的那几项
        api.getFansCount(uid).ifPresent(fans ->
                liveDataService.setLiveMetric(platform, uid, BilibiliLiveMetric.FANS_AT_START, fans));
        api.getFansMedalCount(uid).ifPresent(medal ->
                liveDataService.setLiveMetric(platform, uid, BilibiliLiveMetric.FANS_MEDAL_AT_START, medal));
        if (source.getRoomId() != null) {
            api.getGuardCount(source.getRoomId(), uid).ifPresent(guard ->
                    liveDataService.setLiveMetric(platform, uid, BilibiliLiveMetric.GUARD_AT_START, guard));
        }

        log.debug("已记录 {} 开播时的粉丝与大航海快照", source.getUname());
    }
}
