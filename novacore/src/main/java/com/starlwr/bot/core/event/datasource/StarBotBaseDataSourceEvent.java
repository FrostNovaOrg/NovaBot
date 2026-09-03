package com.starlwr.bot.core.event.datasource;

import com.starlwr.bot.core.event.StarBotInternalBaseEvent;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * StarBot 数据源事件基类
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class StarBotBaseDataSourceEvent extends StarBotInternalBaseEvent {
    public StarBotBaseDataSourceEvent(Instant instant) {
        super(instant);
    }
}
