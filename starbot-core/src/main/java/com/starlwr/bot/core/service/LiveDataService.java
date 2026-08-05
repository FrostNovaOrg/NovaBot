package com.starlwr.bot.core.service;

import com.starlwr.bot.core.model.UserScore;
import lombok.NonNull;

import java.util.List;
import java.util.Map;
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
     * <p>
     * 等价于以增量 1 调用 {@link #incrementLiveUserMetric}：独立人数就是「计分表里有几个人」。
     * 保留本方法是因为它的调用点语义更直白（只关心「有没有参与」而非「参与了多少」）。
     * @param platform 直播平台
     * @param uid UID
     * @param metric 指标名
     * @param userUid 参与用户的 UID
     */
    default void recordLiveMetricUser(@NonNull String platform, @NonNull Long uid, @NonNull String metric, @NonNull Long userUid) {
        incrementLiveUserMetric(platform, uid, metric, userUid, 1);
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

    // ================ 按用户计分（排行榜与个人数据） ================
    // 与上面的「本场指标」是两个维度：那边记总量，这边记「每个用户各贡献了多少」。
    // 排行榜取前 N 名，个人数据查单个用户，独立人数即计分表的大小。

    /**
     * 累加某个用户在本场直播的得分
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param metric 指标名
     * @param userUid 用户 UID
     * @param delta 增量
     */
    default void incrementLiveUserMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric,
                                         @NonNull Long userUid, double delta) {
    }

    /**
     * 记录用户昵称，供排行榜展示
     * <p>
     * 昵称与计分分开存放：同一用户可能出现在多张计分表里，昵称只需存一份。
     * 榜单动辄数十人，绘制时逐个请求接口既慢又容易触发风控，故在事件到达时顺手记下。
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param userUid 用户 UID
     * @param userName 用户昵称
     */
    default void recordLiveUserName(@NonNull String platform, @NonNull Long uid, @NonNull Long userUid, String userName) {
    }

    /**
     * 获取某个用户在本场直播的得分
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param metric 指标名
     * @param userUid 用户 UID
     * @return 得分，未记录时为 0
     */
    default double getLiveUserMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric,
                                     @NonNull Long userUid) {
        return 0;
    }

    /**
     * 获取本场直播某项指标的用户排行
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param metric 指标名
     * @param limit 取前多少名
     * @return 按得分降序排列的用户，不足时返回实际数量
     */
    default List<UserScore> getLiveUserRanking(
            @NonNull String platform, @NonNull Long uid, @NonNull String metric, int limit) {
        return List.of();
    }

    /**
     * 获取某个用户在本场排行中的名次
     * <p>
     * 单列一个方法而不让调用方自己在排行榜里找：Redis 的 zset 求名次是 O(log n)，
     * 拉一整张榜再遍历则是 O(n)，而「我排第几」恰恰是最常查的一项。
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param metric 指标名
     * @param userUid 用户 UID
     * @return 名次，从 1 开始；未上榜时为 0
     */
    default int getLiveUserRank(@NonNull String platform, @NonNull Long uid, @NonNull String metric,
                                @NonNull Long userUid) {
        return 0;
    }

    // ================ 累计数据 ================
    // 跨场次累计，数据量随时间无限增长，因此只有配置了外部存储（如 Redis）的实现才支持。
    // 未配置时一律返回「不支持」，由调用方明确告知使用者，不可静默返回 0——
    // 那会让人以为是数据丢了，而不是没开这个能力。

    /**
     * 当前实现是否支持累计数据
     * @return 是否支持
     */
    default boolean supportsTotalData() {
        return false;
    }

    /**
     * 把本场数据并入累计，在下播时调用
     * @param platform 直播平台
     * @param uid 主播 UID
     */
    default void mergeLiveDataIntoTotal(@NonNull String platform, @NonNull Long uid) {
    }

    /**
     * 获取累计的统计指标
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param metric 指标名
     * @return 指标值，不支持或未记录时为 0
     */
    default double getTotalMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric) {
        return 0;
    }

    /**
     * 获取某个用户的累计得分
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param metric 指标名
     * @param userUid 用户 UID
     * @return 得分，不支持或未记录时为 0
     */
    default double getTotalUserMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric,
                                      @NonNull Long userUid) {
        return 0;
    }

    /**
     * 获取累计的用户排行
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param metric 指标名
     * @param limit 取前多少名
     * @return 按得分降序排列的用户，不支持时为空
     */
    default List<UserScore> getTotalUserRanking(
            @NonNull String platform, @NonNull Long uid, @NonNull String metric, int limit) {
        return List.of();
    }

    /**
     * 获取某个用户在累计排行中的名次
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param metric 指标名
     * @param userUid 用户 UID
     * @return 名次，从 1 开始；不支持或未上榜时为 0
     */
    default int getTotalUserRank(@NonNull String platform, @NonNull Long uid, @NonNull String metric,
                                 @NonNull Long userUid) {
        return 0;
    }

    /**
     * 获取累计参与某项互动的独立用户数
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param metric 指标名
     * @return 独立用户数，不支持或未记录时为 0
     */
    default int getTotalMetricUserCount(@NonNull String platform, @NonNull Long uid, @NonNull String metric) {
        return 0;
    }

    /**
     * 累计本场直播的词频，用于绘制弹幕词云
     * <p>
     * 存储的是分词后的词频而非弹幕原文：体积有上界，且能随直播数据一并持久化
     * @param platform 直播平台
     * @param uid UID
     * @param word 词语
     */
    default void incrementLiveWordFrequency(@NonNull String platform, @NonNull Long uid, @NonNull String word) {
    }

    /**
     * 获取本场直播的词频表
     * @param platform 直播平台
     * @param uid UID
     * @return 词语到出现次数的映射，未记录时为空表
     */
    default Map<String, Integer> getLiveWordFrequencies(@NonNull String platform, @NonNull Long uid) {
        return Map.of();
    }
}
