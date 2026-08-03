package com.starlwr.bot.core.event.datasource.other;

import com.starlwr.bot.core.event.datasource.StarBotBaseDataSourceEvent;
import com.starlwr.bot.core.model.PushUser;
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
public class StarBotDataSourceLoadCompleteEvent extends StarBotBaseDataSourceEvent {
    /**
     * 推送用户列表
     */
    private List<PushUser> users;

    public StarBotDataSourceLoadCompleteEvent(List<PushUser> users) {
        this.users = users;
    }

    public StarBotDataSourceLoadCompleteEvent(List<PushUser> users, Instant instant) {
        super(instant);
        this.users = users;
    }
}
