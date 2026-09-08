package com.starlwr.bot.core.event;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEvent;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * NovaBot 事件基类
 */
@Getter
@Setter
public class NovaBaseEvent extends ApplicationEvent {
    private static final Logger eventLogger = LoggerFactory.getLogger("EventLogger");

    /**
     * 事件是否已被中断
     */
    private boolean stopped = false;

    public NovaBaseEvent() {
        super(new Object());
    }

    public NovaBaseEvent(Instant instant) {
        super(new Object(), Clock.fixed(instant, ZoneId.systemDefault()));
    }

    /**
     * 中断事件传播
     * @param caller 调用者，直接传入 this 即可
     */
    public void stop(@NonNull Object caller) {
        eventLogger.debug("{} 中断了 {}[{}] 事件传播", caller.getClass().getName(), this.getClass().getName(), Integer.toHexString(System.identityHashCode(this)));
        stopped = true;
    }

    @Override
    public String toString() {
        return "NovaBaseEvent(" + "timestamp=" + getTimestamp() + ")";
    }
}
