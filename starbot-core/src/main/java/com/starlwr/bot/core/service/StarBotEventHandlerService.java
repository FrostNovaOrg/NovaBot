package com.starlwr.bot.core.service;

import com.starlwr.bot.core.handler.StarBotEventHandler;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * StarBot 事件处理器服务
 */
@Slf4j
@Service
public class StarBotEventHandlerService {
    private final ApplicationContext applicationContext;

    private final Map<String, StarBotEventHandler> cache = new HashMap<>();

    @Autowired
    public StarBotEventHandlerService(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * 加载事件处理器
     */
    @Order(0)
    @EventListener(ContextRefreshedEvent.class)
    public void onContextRefreshedEvent() {
        for (StarBotEventHandler handler : applicationContext.getBeansOfType(StarBotEventHandler.class).values()) {
            cache.put(handler.getClass().getName(), handler);
        }
    }

    /**
     * 获取事件处理器
     * @param handlerClass 处理器全类名
     * @return 事件处理器
     */
    public Optional<StarBotEventHandler> getHandler(@NonNull String handlerClass) {
        return Optional.ofNullable(cache.get(handlerClass));
    }
}
