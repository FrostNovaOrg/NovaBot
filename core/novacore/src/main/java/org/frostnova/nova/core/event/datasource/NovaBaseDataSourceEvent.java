package org.frostnova.nova.core.event.datasource;

import org.frostnova.nova.core.event.NovaInternalBaseEvent;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * NovaBot 数据源事件基类
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class NovaBaseDataSourceEvent extends NovaInternalBaseEvent {
    public NovaBaseDataSourceEvent(Instant instant) {
        super(instant);
    }
}
