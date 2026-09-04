package com.starlwr.bot.core.service;

import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.model.StreamerReference;

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

    /**
     * 按直播间号查主播
     * <p>
     * 短号由平台接口一次解析成真实房间再拿到 uid。默认不支持：不是每个平台
     * 都有独立于 uid 的房间号，也不是每个数据源实现都够得着那一趟查询。
     * @param roomId 直播间号，可以是短号
     * @return 查到的主播，找不到时为空
     */
    default Optional<PushUser> lookupByRoomId(Long roomId) {
        return Optional.empty();
    }

    /**
     * 从一段文本里认出本平台的主播
     * <p>
     * 让使用者自己从链接里抠出那串数字是没必要的一道门槛，而<b>链接长什么样只有平台自己知道</b>：
     * 域名、路径、参数各家各样，同一串数字在个人空间链接里是 uid、在直播间链接里是直播间号。
     * 把这件事放在这里，是因为核心一旦写死某一家的域名，就等于承认自己长在那个平台上——
     * 装第二个平台时，它的链接核心一个都不认得，而第一家的链接核心照单全收，
     * 于是那串数字会被拿去问一个根本不管这条链接的平台。
     * <p>
     * 只认本平台的链接：认不出的一律给空，包括别家平台的链接。<b>不要在这里认「一串纯数字」</b>——
     * 那不是链接，各平台一样对待，由调用方统一处理。
     * <p>
     * 默认认不出任何链接。不是每个平台都有可粘贴的主播链接，也不是每个数据源实现都管这件事。
     * @param text 输入文本，通常是使用者粘贴进来的一整条链接
     * @return 认出的 uid 或直播间号，认不出时为空
     */
    default Optional<StreamerReference> parseStreamerLink(String text) {
        return Optional.empty();
    }
}
