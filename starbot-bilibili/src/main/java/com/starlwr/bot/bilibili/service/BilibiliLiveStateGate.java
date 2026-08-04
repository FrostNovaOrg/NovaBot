package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.LiveDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.Map;

/**
 * 直播状态闸门
 * <p>
 * 开播与下播事件有两条独立的发现路径：直播间长连接与备用轮询。两者都会发布事件，
 * 而同一次状态变化只应推送一次，否则群里会收到两条一模一样的通知（实测已发生：
 * 长连接在 15:49:26.970 推了一条，备用轮询在 0.7 秒后又推了一条）。
 * <p>
 * 去重必须放在**发布之前**：事件一旦发布，各推送处理器都是独立的监听器，
 * Spring 的事件机制没有中断传播的手段。
 * <p>
 * 也不能只让备用轮询单方面避让。轮询每 10 秒一轮，落点是随机的，
 * 完全可能早于长连接先看到状态变化——那时重复的顺序正好反过来。因此两条路径都要过这道闸门。
 */
@Slf4j
@StarBotComponent
public class BilibiliLiveStateGate {
    private final LiveDataService liveDataService;

    /**
     * 各主播最近一次**已放行**的状态
     * <p>
     * 独立于 {@link LiveDataService} 维护：后者由事件监听器在事件发布之后才写入，
     * 两条路径若在这段空窗里同时查询，会双双认为自己是第一个
     */
    private final Map<Long, Boolean> admitted = new HashMap<>();

    @Autowired
    public BilibiliLiveStateGate(LiveDataService liveDataService) {
        this.liveDataService = liveDataService;
    }

    /**
     * 判断某次直播状态变化是否应当发布
     * <p>
     * 放行的同时即记录，因此同一次变化只有第一个调用者会得到 {@code true}。
     * @param uid 主播 UID
     * @param living 新的直播状态
     * @return 是否应当发布事件
     */
    public synchronized boolean admit(Long uid, boolean living) {
        if (uid == null) {
            return false;
        }

        Boolean last = admitted.get(uid);
        if (last == null) {
            // 进程刚起来时以持久化的状态为准，避免重启后把旧状态当成新变化推一遍
            last = liveDataService.getLiveStatus(LivePlatform.BILIBILI.getName(), uid).orElse(null);
        }

        if (last != null && last == living) {
            log.debug("主播 {} 的直播状态变化已被另一条路径处理, 本次不再重复发布", uid);
            return false;
        }

        admitted.put(uid, living);
        return true;
    }
}
