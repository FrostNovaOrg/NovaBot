package org.frostnova.nova.core.event.datasource.change;

import org.frostnova.nova.core.event.datasource.base.NovaDataSourceChangeEvent;
import org.frostnova.nova.core.model.PushUser;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 数据源推送用户移除事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class NovaDataSourceRemoveEvent extends NovaDataSourceChangeEvent {
    public NovaDataSourceRemoveEvent(PushUser user) {
        super(user);
    }

    public NovaDataSourceRemoveEvent(PushUser user, Instant instant) {
        super(user, instant);
    }
}
