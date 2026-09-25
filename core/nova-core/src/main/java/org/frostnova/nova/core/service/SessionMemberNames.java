package org.frostnova.nova.core.service;

import lombok.NonNull;
import org.frostnova.nova.core.enums.PushTargetType;

import java.util.Collection;
import java.util.Map;

/**
 * 会话成员的展示名
 * <p>
 * 「群里的人叫什么」是**平台专有**的知识：QQ 有群昵称，别的平台有别的叫法，
 * 核心不该知道这些。要把成员写给人看时（例如订阅名单），由推送平台适配器供名字。
 * <p>
 * <b>取不到名字时调用方自己兜底</b>，而不是把账号写出去——名单是要发回群里的，
 * 账号是隐私，群里本来就能看见的只是昵称。
 */
public interface SessionMemberNames {
    /**
     * 本查询器是否负责该推送平台
     * @param platform 推送平台名
     * @return 是否负责
     */
    boolean supports(@NonNull String platform);

    /**
     * 一次问一批成员在这个会话里的展示名
     * <p>
     * 群昵称优先——群里的人认的是这个名字；没设群昵称时退回账号昵称。
     * 两者都取不到（或接口查不动）的成员<b>不在结果里</b>，由调用方自己写占位。
     * <p>
     * 按批给而不是一人一个方法：取名字这一支是要出远门的，逐个问的话
     * 名单上几十个人就是几十趟远门，一次回话能把群接口打穿。
     * @param platform 推送平台名
     * @param type 会话类型
     * @param sessionNum 会话号
     * @param memberUids 这一批成员的账号
     * @return 账号 → 展示名，取不到名字的账号不在表里
     */
    Map<Long, String> displayNames(@NonNull String platform, @NonNull PushTargetType type,
                                   @NonNull Long sessionNum, @NonNull Collection<Long> memberUids);
}
