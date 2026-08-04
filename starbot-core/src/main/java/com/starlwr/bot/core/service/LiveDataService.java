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

    // ================ 本场直播统计指标 ================
    // 以下均为 default 方法：LiveDataService 是可被第三方替换的扩展点，
    // 旧实现未覆盖这些方法时统计功能静默降级为「无数据」，不会因缺方法而无法启动

    /**
     * 累加本场直播的统计指标
     * @param platform 直播平台
     * @param uid UID
     * @param metric 指标名
     * @param delta 增量
     */
    default void incrementLiveMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric, double delta) {
    }

    /**
     * 以取最大值的方式更新本场直播的统计指标，适用于服务端下发的单调累计值（如点赞总数）
     * @param platform 直播平台
     * @param uid UID
     * @param metric 指标名
     * @param value 候选值
     */
    default void maxLiveMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric, double value) {
    }

    /**
     * 获取本场直播的统计指标
     * @param platform 直播平台
     * @param uid UID
     * @param metric 指标名
     * @return 指标值，未记录时为 0
     */
    default double getLiveMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric) {
        return 0;
    }

    /**
     * 记录参与某项互动的用户，用于独立人数统计
     * @param platform 直播平台
     * @param uid UID
     * @param metric 指标名
     * @param userUid 参与用户的 UID
     */
    default void recordLiveMetricUser(@NonNull String platform, @NonNull Long uid, @NonNull String metric, @NonNull Long userUid) {
    }

    /**
     * 获取参与某项互动的独立用户数
     * @param platform 直播平台
     * @param uid UID
     * @param metric 指标名
     * @return 独立用户数，未记录时为 0
     */
    default int getLiveMetricUserCount(@NonNull String platform, @NonNull Long uid, @NonNull String metric) {
        return 0;
    }
}
