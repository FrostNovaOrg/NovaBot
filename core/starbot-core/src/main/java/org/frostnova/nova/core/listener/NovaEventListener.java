package org.frostnova.nova.core.listener;

import org.frostnova.nova.core.event.NovaBaseEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * NovaBot 事件监听器
 */
@Component
@ConditionalOnProperty(name = "novabot.core.log.event-log", havingValue = "true")
public class NovaEventListener {
    private static final Logger eventLogger = LoggerFactory.getLogger("EventLogger");

    /**
     * 监听所有 NovaBot 事件记录日志
     * @param event 事件
     */
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @EventListener
    public void onNovaBaseEvent(NovaBaseEvent event) {
        eventLogger.debug("[{}][{}] {}", event.getClass().getSimpleName(), Integer.toHexString(System.identityHashCode(event)), event);
    }
}
