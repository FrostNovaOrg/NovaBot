package com.starlwr.bot.core.sender;

import lombok.NonNull;

/**
 * @全体成员 的权限判定扩展点
 * <p>
 * 「这个机器人在这个会话里能不能 @全体成员」是**平台专有**的知识——
 * 核心不该知道「群管理员」是什么。因此核心只定义策略（没权限就摘掉占位符），
 * 由推送平台适配器提供事实。
 * <p>
 * 没有任何实现时核心一律放行，保持没有这层判定之前的行为。
 */
public interface AtAllPermissionResolver {
    /**
     * 本判定器是否负责该推送平台
     * @param platform 推送平台名
     * @return 是否负责
     */
    boolean supports(@NonNull String platform);

    /**
     * 判断机器人在指定会话中能否 @全体成员
     * <p>
     * <b>查不出来时应返回 true。</b>宁可保持原有行为，也不要因为一次接口抖动
     * 就悄悄吞掉使用者明确配置过的 @全体成员。
     * @param platform 推送平台名
     * @param num 会话号
     * @return 是否允许
     */
    boolean canAtAll(@NonNull String platform, @NonNull Long num);
}
