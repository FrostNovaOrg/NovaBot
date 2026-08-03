package com.starlwr.bot.core.service;

import lombok.NonNull;

import java.util.Optional;

/**
 * 直播数据服务接口
 */
public interface LiveDataService {
    /**
     * 获取直播间状态
     * @param platform 直播平台
     * @param uid UID
     * @return 直播间状态，true：已开播，false：未开播
     */
    Optional<Boolean> getLiveStatus(@NonNull String platform, @NonNull Long uid);

    /**
     * 设置直播间状态
     * @param platform 直播平台
     * @param uid UID
     * @param status 直播间状态，true：已开播，false：未开播
     */
    void setLiveStatus(@NonNull String platform, @NonNull Long uid, boolean status);

    /**
     * 获取最近一场直播开始时间戳
     * @param platform 直播平台
     * @param uid UID
     * @return 最近一场直播开始时间戳
     */
    Optional<Long> getLiveStartTime(@NonNull String platform, @NonNull Long uid);

    /**
     * 设置最近一场直播开始时间戳
     * @param platform 直播平台
     * @param uid UID
     * @param startTime 最近一场直播开始时间戳
     */
    void setLiveStartTime(@NonNull String platform, @NonNull Long uid, long startTime);

    /**
     * 获取最近一场直播结束时间戳
     * @param platform 直播平台
     * @param uid UID
     * @return 最近一场直播结束时间戳
     */
    Optional<Long> getLiveEndTime(@NonNull String platform, @NonNull Long uid);

    /**
     * 设置最近一场直播结束时间戳
     * @param platform 直播平台
     * @param uid UID
     * @param endTime 最近一场直播结束时间戳
     */
    void setLiveEndTime(@NonNull String platform, @NonNull Long uid, long endTime);

    /**
     * 删除最近一场直播结束时间戳
     * @param platform 直播平台
     * @param uid UID
     */
    void deleteLiveEndTime(@NonNull String platform, @NonNull Long uid);

    /**
     * 重置最近一场直播数据
     * @param platform 直播平台
     * @param uid UID
     */
    void resetLiveData(@NonNull String platform, @NonNull Long uid);
}
