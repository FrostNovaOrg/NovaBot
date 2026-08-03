package com.starlwr.bot.core.event;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * StarBot 内部事件基类，由 StarBot 内部触发、内部处理，一般无需外界关心的事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class StarBotInternalBaseEvent extends StarBotBaseEvent {
    public StarBotInternalBaseEvent(Instant instant) {
        super(instant);
    }
}
