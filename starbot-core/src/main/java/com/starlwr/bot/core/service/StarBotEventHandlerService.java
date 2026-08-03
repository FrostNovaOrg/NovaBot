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
import java.util.Set;

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

    /**
     * 获取全部已注册的事件处理器全类名
     * <p>
     * 供推送配置的保存前校验使用：处理器类名写错时应在保存时就指出来，而不是等到运行期
     * 才以「找不到处理器」的形式静默失败。
     * @return 已注册的处理器全类名
     */
    public Set<String> getRegisteredHandlerClasses() {
        return Set.copyOf(cache.keySet());
    }

    /**
     * 获取全部已注册的事件处理器
     * <p>
     * 供配置界面渲染「推送哪些事件」的勾选项：处理器由此列表驱动，插件新增处理器时界面自动出现，
     * 不需要前端硬编码任何类名。
     * @return 处理器全类名到实例的映射
     */
    public Map<String, StarBotEventHandler> getRegisteredHandlers() {
        return Map.copyOf(cache);
    }
}
