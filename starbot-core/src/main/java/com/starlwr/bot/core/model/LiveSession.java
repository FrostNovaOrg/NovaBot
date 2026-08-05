package com.starlwr.bot.core.model;

import java.util.Map;

/**
 * 一场直播的归档记录
 * <p>
 * <b>这是运营分析的唯一数据源。</b>累计数据只是个没有时间维度的标量，
 * 从「累计弹幕 455」推不出「上周播了几场」「本月比上月如何」——
 * 有了逐场流水，周月统计不过是按时间区间聚合；反过来则无解。
 * <p>
 * 指标以「名称 → 取值」的形式原样存放，不做字段化：指标名由各平台自行定义，
 * 核心并不知道有哪些，写死字段会让新增一个指标就要改一次归档格式。
 *
 * @param platform 直播平台
 * @param uid 主播 UID
 * @param uname 主播昵称，记录当时的值
 * @param roomId 直播间号
 * @param startTime 开播时刻（毫秒）
 * @param endTime 下播时刻（毫秒）
 * @param durationSeconds 时长（秒）
 * @param metrics 各项统计指标
 * @param userCounts 各计分表的独立参与人数
 */
public record LiveSession(
        String platform,
        Long uid,
        String uname,
        Long roomId,
        long startTime,
        long endTime,
        long durationSeconds,
        Map<String, Double> metrics,
        Map<String, Integer> userCounts
) {
    /**
     * 取某项指标，缺失时为 0
     */
    public double metric(String name) {
        Double value = metrics == null ? null : metrics.get(name);
        return value == null ? 0 : value;
    }

    /**
     * 取某个计分表的参与人数，缺失时为 0
     */
    public int userCount(String name) {
        Integer value = userCounts == null ? null : userCounts.get(name);
        return value == null ? 0 : value;
    }
}
