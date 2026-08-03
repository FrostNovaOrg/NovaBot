package com.starlwr.bot.core.listener;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.event.live.common.LiveOnEvent;
import com.starlwr.bot.core.service.LiveDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * StarBot 开播事件监听器
 */
@Slf4j
@Component
public class StarBotDefaultLiveOnEventListener {
    private final StarBotCoreProperties properties;

    private final LiveDataService liveDataService;

    @Autowired
    public StarBotDefaultLiveOnEventListener(StarBotCoreProperties properties, LiveDataService liveDataService) {
        this.properties = properties;
        this.liveDataService = liveDataService;
    }

    /**
     * 断线重连检测
     * @param event 事件
     */
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @EventListener
    public void onLiveOnEventCheckReconnect(LiveOnEvent event) {
        Optional<Long> optionalLastLiveEndTime = liveDataService.getLiveEndTime(event.getPlatform(), event.getSource().getUid());
        if (optionalLastLiveEndTime.isPresent()) {
            long lastLiveEndTime = optionalLastLiveEndTime.get();
            long currentLiveStartTime = event.getTimestamp();
            long diff = (currentLiveStartTime - lastLiveEndTime) / 1000;
            if (diff <= properties.getLive().getReconnectInterval()) {
                event.setReconnect(true);
            }
        }
    }

    /**
     * 更新重置房间数据
     * @param event 事件
     */
    @Order(-10000)
    @EventListener
    public void onLiveOnEventSetLiveData(LiveOnEvent event) {
        if (event.isReconnect()) {
            log.info("[{}] [断线重连] {}(UID: {}, 房间号: {})", event.getPlatform(), event.getSource().getUname(), event.getSource().getUid(), event.getSource().getRoomIdString());
        } else {
            log.info("[{}] [开播] {}(UID: {}, 房间号: {})", event.getPlatform(), event.getSource().getUname(), event.getSource().getUid(), event.getSource().getRoomIdString());
        }

        liveDataService.setLiveStatus(event.getPlatform(), event.getSource().getUid(), true);

        if (!event.isReconnect()) {
            liveDataService.setLiveStartTime(event.getPlatform(), event.getSource().getUid(), event.getTimestamp());
            liveDataService.resetLiveData(event.getPlatform(), event.getSource().getUid());
        }
    }
}
