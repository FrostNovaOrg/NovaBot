package com.starlwr.bot.core.event.datasource.base;

import com.starlwr.bot.core.event.datasource.StarBotBaseDataSourceEvent;
import com.starlwr.bot.core.model.PushUser;
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
public class StarBotDataSourceChangeEvent extends StarBotBaseDataSourceEvent {
    /**
     * 推送用户
     */
    private PushUser user;

    public StarBotDataSourceChangeEvent(PushUser user) {
        this.user = user;
    }

    public StarBotDataSourceChangeEvent(PushUser user, Instant instant) {
        super(instant);
        this.user = user;
    }
}
