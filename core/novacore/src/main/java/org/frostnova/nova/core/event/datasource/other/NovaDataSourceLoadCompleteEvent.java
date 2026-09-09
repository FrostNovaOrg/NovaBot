package org.frostnova.nova.core.event.datasource.other;

import org.frostnova.nova.core.event.datasource.NovaBaseDataSourceEvent;
import org.frostnova.nova.core.model.PushUser;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.List;

/**
 * 数据源加载完毕事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class NovaDataSourceLoadCompleteEvent extends NovaBaseDataSourceEvent {
    /**
     * 推送用户列表
     */
    private List<PushUser> users;

    public NovaDataSourceLoadCompleteEvent(List<PushUser> users) {
        this.users = users;
    }

    public NovaDataSourceLoadCompleteEvent(List<PushUser> users, Instant instant) {
        super(instant);
        this.users = users;
    }
}
