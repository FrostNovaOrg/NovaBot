package com.starlwr.bot.core.listener;

import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.event.StarBotExternalBaseEvent;
import com.starlwr.bot.core.handler.StarBotEventHandler;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * StarBot 监听外部事件触发事件处理
 */
@Slf4j
@Component
public class StarBotHandlerListener {
    private final AbstractDataSource dataSource;

    @Autowired
    public StarBotHandlerListener(AbstractDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 调用事件处理器处理外部事件
     * @param event 事件
     */
    @Order(0)
    @EventListener
    public void onStarBotExternalBaseEvent(StarBotExternalBaseEvent event) {
        Optional<PushUser> optionalUser = dataSource.getUser(event.getPlatform(), event.getSource().getUid());
        if (optionalUser.isEmpty()) {
            return;
        }

        String eventClass = event.getClass().getName();

        PushUser user = optionalUser.get();
        for (PushTarget target : user.getTargets()) {
            for (PushMessage message : target.getMessages()) {
                if (event.getClass().equals(message.getEventClass())) {
                    StarBotEventHandler handler = message.getHandlerInstance();
                    try {
                        handler.handle(event, message);
                    } catch (Exception e) {
                        log.error("事件处理器 {} 处理事件 {} 异常", handler.getClass().getName(), eventClass, e);
                    }
                }
            }
        }
    }
}
