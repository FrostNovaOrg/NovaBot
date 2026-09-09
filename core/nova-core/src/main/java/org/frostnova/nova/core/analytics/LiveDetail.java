package org.frostnova.nova.core.analytics;

import org.frostnova.nova.core.model.LiveGap;
import org.frostnova.nova.core.model.RoomInfoSnapshot;
import org.frostnova.nova.core.model.SeriesPeak;
import org.frostnova.nova.core.model.UserScore;

import java.util.List;
import java.util.Map;

/**
 * 一场直播的全量明细
 * <p>
 * 与 {@link org.frostnova.nova.core.model.LiveSession} 的分工是<b>「这一场发生过什么」与「这一场是什么样」</b>：
 * 场次归档一行一场、每条约 500 字节，是运营分析按周月聚合的那份流水；
 * 明细一场一份、动辄几十上百 KB，装的是那一场的全部原始形状。
 * <p>
 * <b>为什么非留不可</b>：下面这几项<b>全部活在本场数据里，下一次开播即清零</b>——
 * 曲线、排行榜、词频表在此前的设计里只被读一次（画那张报告图），画完就没了。
 * 于是「上个月哪一场最热闹的十分钟在哪」「这位观众在第几场开始不来了」这类问题，
 * 不是算不出来，是<b>数据当时就没留</b>。而这一项<b>越晚做丢得越多</b>。
 * <p>
 * <b>排行榜留全量而不是前 N</b>：报告图上只画前几名，是版面所限；
 * 留档要是也只留前几名，「第 30 名到第 200 名是谁」就永久没有了，
 * 而回流率、沉睡预警这类分析要的恰恰是长尾那一段。
 * <p>
 * 弹幕原文不在本记录里，单独一份逐条追加的 JSONL，
 * 理由见 {@link org.frostnova.nova.core.service.LiveDetailArchive}。
 *
 * @param version 明细格式版本，读取方据此判断自己认不认得这一份
 * @param platform 直播平台
 * @param uid 主播 UID
 * @param uname 主播昵称，记录当时的值
 * @param roomId 直播间号
 * @param startTime 开播时刻（毫秒），与场次归档里的 {@code startTime} 同值——这是两边唯一的钉子
 * @param endTime 下播时刻（毫秒）
 * @param durationSeconds 时长（秒）
 * @param metrics 各项统计指标，与场次归档里的同源
 * @param userCounts 各计分表的独立参与人数
 * @param series 各条时间序列：指标名 → （时间格起始时刻 → 该格取值）。<b>分钟级，原样留</b>
 * @param rankings 各张排行榜的<b>全量</b>名次：指标名 → 按得分降序的用户
 * @param words 弹幕词频表
 * @param highlights 高能时刻，从弹幕密度算出
 * @param titles 本场的标题与分区轨迹，首条为开播时的初始值
 * @param gaps 本场的采集缺口区间，<b>已合并至互不重叠</b>（程序停机与单房断线必然重叠，不可相加）
 * @param peaks 各条序列的峰值，与场次归档里的那一份同源——那边是给列表用的摘要，这边是明细自带的副本
 */
public record LiveDetail(
        int version,
        String platform,
        Long uid,
        String uname,
        Long roomId,
        long startTime,
        long endTime,
        long durationSeconds,
        Map<String, Double> metrics,
        Map<String, Integer> userCounts,
        Map<String, Map<Long, Double>> series,
        Map<String, List<UserScore>> rankings,
        Map<String, Integer> words,
        List<LiveHighlightFinder.Highlight> highlights,
        List<RoomInfoSnapshot> titles,
        List<LiveGap> gaps,
        Map<String, SeriesPeak> peaks
) {
    /**
     * 当前明细格式版本
     * <p>
     * 往记录里加字段不必升版本（读旧档时缺失项读成空即可）；
     * <b>改变既有字段的含义才升</b>——那种改动读旧档会安静地读出错的数。
     */
    public static final int VERSION = 1;

    /**
     * 取某条序列，没有这一条时为空表
     * @param metric 指标名
     * @return 时间格起始时刻到取值的映射
     */
    public Map<Long, Double> series(String metric) {
        Map<Long, Double> value = series == null ? null : series.get(metric);
        return value == null ? Map.of() : value;
    }

    /**
     * 取某张排行榜，没有这一张时为空表
     * @param metric 指标名
     * @return 按得分降序的用户
     */
    public List<UserScore> ranking(String metric) {
        List<UserScore> value = rankings == null ? null : rankings.get(metric);
        return value == null ? List.of() : value;
    }
}
