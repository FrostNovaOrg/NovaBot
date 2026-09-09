package org.frostnova.nova.core.event.datasource.base;

import org.frostnova.nova.core.event.datasource.NovaBaseDataSourceEvent;
import org.frostnova.nova.core.model.PushUser;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 数据源内容变更事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class NovaDataSourceChangeEvent extends NovaBaseDataSourceEvent {
    /**
     * 推送用户
     */
    private PushUser user;

    public NovaDataSourceChangeEvent(PushUser user) {
        this.user = user;
    }

    public NovaDataSourceChangeEvent(PushUser user, Instant instant) {
        super(instant);
        this.user = user;
    }
}
