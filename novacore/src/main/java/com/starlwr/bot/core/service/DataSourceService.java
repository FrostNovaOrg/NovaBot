package com.starlwr.bot.core.service;

import com.starlwr.bot.core.model.PushUser;

import java.util.List;
import java.util.Optional;

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

    /**
     * 获取粉丝数
     * <p>
     * 添加主播时那张确认小卡上要写粉丝数：uid 打错一位仍可能查到一位真实存在的人，
     * 昵称与头像不一定看得出不对，而粉丝数这一项差得远，最容易发现认错了人。
     * <p>
     * <b>粉丝数没有进 {@link PushUser}</b>：那个模型是推送配置的形状，会被完整地读写、
     * 比较与落盘，往里塞一个每分钟都在变的数，等于让「配置改没改过」这个判断天天为真。
     * <p>
     * 默认取不到。不是每个平台都有「粉丝」这个概念，也不是每个数据源实现都够得着它，
     * 而<b>空与 0 必须分得开</b>——0 会在界面上显示成「这位主播一个粉丝都没有」。
     * @param uid UID
     * @return 粉丝数，取不到时为空
     */
    default Optional<Long> getFansCount(Long uid) {
        return Optional.empty();
    }
}
