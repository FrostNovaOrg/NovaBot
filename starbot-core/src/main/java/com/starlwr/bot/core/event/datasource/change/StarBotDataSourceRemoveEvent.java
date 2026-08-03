package com.starlwr.bot.core.event.datasource.change;

import com.starlwr.bot.core.event.datasource.base.StarBotDataSourceChangeEvent;
import com.starlwr.bot.core.model.PushUser;
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
public class StarBotDataSourceRemoveEvent extends StarBotDataSourceChangeEvent {
    public StarBotDataSourceRemoveEvent(PushUser user) {
        super(user);
    }

    public StarBotDataSourceRemoveEvent(PushUser user, Instant instant) {
        super(user, instant);
    }
}
