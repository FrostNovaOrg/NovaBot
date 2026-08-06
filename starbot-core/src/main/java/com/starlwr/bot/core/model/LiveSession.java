package com.starlwr.bot.core.model;

import com.starlwr.bot.core.enums.LiveEndReason;

import java.util.List;
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
 * <p>
 * 结束原因与标题轨迹则相反，它们是<b>所有平台共有的场次属性</b>而不是某个平台的指标，
 * 而且都不是数值，塞进 {@code metrics} 只会让那张表同时装两种东西。
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
 * @param endReason 结束原因，用于把被平台切断的场次与正常场次区分开
 * @param titles 本场的标题与分区轨迹，首条为开播时的初始值
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
        Map<String, Integer> userCounts,
        LiveEndReason endReason,
        List<RoomInfoSnapshot> titles
) {
    /**
     * 按正常结束、无标题记录构造
     * <p>
     * 绝大多数场次都是这种情况，另有 4.3.0 之前归档的历史记录也没有这两项。
     */
    public LiveSession(String platform, Long uid, String uname, Long roomId, long startTime, long endTime,
                       long durationSeconds, Map<String, Double> metrics, Map<String, Integer> userCounts) {
        this(platform, uid, uname, roomId, startTime, endTime, durationSeconds, metrics, userCounts,
                LiveEndReason.NORMAL, List.of());
    }

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

    /**
     * 本场是否被平台中断
     * <p>
     * <b>时长、营收、互动都因此不可比。</b>做趋势分析时应当把这类场次单独标出，
     * 而不是让它在图上表现为「这天状态很差」。
     */
    public boolean interrupted() {
        return endReason != null && endReason != LiveEndReason.NORMAL;
    }

    /**
     * 本场标题实际改动的次数，首条记录是初始标题而不是一次改动
     */
    public int titleChangeCount() {
        return titles == null ? 0 : Math.max(0, titles.size() - 1);
    }
}
