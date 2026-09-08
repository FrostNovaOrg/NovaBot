package com.starlwr.bot.core.multicaster;

import com.starlwr.bot.core.event.StarBotBaseEvent;
import lombok.NonNull;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.SimpleApplicationEventMulticaster;

/**
 * 可中断事件发布器
 */
public class InterruptibleEventMulticaster extends SimpleApplicationEventMulticaster {
    @Override
    protected void invokeListener(@NonNull ApplicationListener<?> listener, @NonNull ApplicationEvent event) {
        if (event instanceof StarBotBaseEvent stoppableEvent && stoppableEvent.isStopped()) {
            return;
        }

        super.invokeListener(listener, event);
    }
}
