package com.starlwr.bot.core.service;

import com.starlwr.bot.core.model.PushUser;

import java.util.List;

/**
 * 数据源服务接口，各直播平台实现均应实现此接口，用于获取各平台中的推送用户信息，实现类应添加 {@link DataSourceServiceConfig} 注解
 */
public interface DataSourceService {
    /**
     * 补全推送用户信息
     * @param user 推送用户
     */
    void completePushUser(PushUser user);

    /**
     * 批量补全推送用户信息
     * @param users 推送用户列表
     */
    default void completePushUsers(List<PushUser> users) {
        for (PushUser user : users) {
            completePushUser(user);
        }
    }
}
