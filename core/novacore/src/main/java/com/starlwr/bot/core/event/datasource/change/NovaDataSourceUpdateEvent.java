package com.starlwr.bot.core.event.datasource.change;

import com.starlwr.bot.core.event.datasource.base.NovaDataSourceChangeEvent;
import com.starlwr.bot.core.model.PushUser;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 数据源推送用户更新事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class NovaDataSourceUpdateEvent extends NovaDataSourceChangeEvent {
    /**
     * 原推送用户
     */
    private PushUser oldUser;

    public NovaDataSourceUpdateEvent(PushUser oldUser, PushUser user) {
        super(user);
        this.oldUser = oldUser;
    }

    public NovaDataSourceUpdateEvent(PushUser oldUser, PushUser user, Instant instant) {
        super(user, instant);
        this.oldUser = oldUser;
    }
}
