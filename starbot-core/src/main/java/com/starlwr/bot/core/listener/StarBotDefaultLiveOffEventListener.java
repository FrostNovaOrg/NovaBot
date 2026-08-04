package com.starlwr.bot.core.listener;

import com.starlwr.bot.core.event.live.common.LiveOffEvent;
import com.starlwr.bot.core.service.LiveDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * StarBot 下播事件监听器
 */
@Slf4j
@Component
public class StarBotDefaultLiveOffEventListener {
    private final LiveDataService liveDataService;

    @Autowired
    public StarBotDefaultLiveOffEventListener(LiveDataService liveDataService) {
        this.liveDataService = liveDataService;
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

        // 本场数据并入累计。选在下播而非开播清零前，是因为程序可能在两场之间重启，
        // 拖到开播才并入会整场丢失。本场数据本身保留到下次开播，报告仍读得到
        liveDataService.mergeLiveDataIntoTotal(event.getPlatform(), event.getSource().getUid());
    }
}
