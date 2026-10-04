package org.frostnova.nova.core.service;

import org.frostnova.nova.core.analytics.LiveDetail;
import org.frostnova.nova.core.analytics.LiveHighlightFinder;
import org.frostnova.nova.core.model.DanmuRecord;
import org.frostnova.nova.core.model.LiveGap;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.SeriesPeak;
import org.frostnova.nova.core.model.UserScore;
import lombok.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一场直播的明细与峰值归档，正常下播与未闭合补档共用的一套
 * <p>
 * 曲线全量、排行全量、词频、高能、标题、缺口、打赏，这些只活在本场数据里，
 * 下一次开播即清零——归档那一刻没留下，这一场以后就重画不出来。
 * 这一套原来只长在正常下播的监听器里（{@code NovaDefaultLiveOffEventListener}），
 * 停机里结束的那一场走补档（{@code LiveSessionRecovery}），补出来的明细就缺了这一整份。
 * 抽到这里之后，两条路写出来的是<b>同一份东西</b>，不再各抄一套。
 * <p>
 * <b>明细落盘（{@code detail.json}）同时是「本场已封存」的标记</b>：它落下之后，
 * 这一场的弹幕原文不再接收新的追加（见 {@link LiveDetailArchive}）。补档这一路
 * 存下明细，等于把旧场的弹幕原文一并封了口。
 */
@Service
public class LiveSessionDetailArchiver {
    private final LiveDataService liveDataService;

    private final LiveRoomInfoHistory roomInfoHistory;

    /**
     * 本场明细留档。场次归档留「这一场发生过」，它留「这一场的原始数据」
     */
    private final LiveDetailArchive details;

    @Autowired
    public LiveSessionDetailArchiver(LiveDataService liveDataService, LiveRoomInfoHistory roomInfoHistory,
                                     LiveDetailArchive details) {
        this.liveDataService = liveDataService;
        this.roomInfoHistory = roomInfoHistory;
        this.details = details;
    }

    /**
     * 断线重连续上了这一场，让它的明细留档接着收弹幕原文与事件流水（见 {@link LiveDetailArchive#reopen}）
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param start 本场开播时刻（毫秒）
     */
    public void reopen(@NonNull String platform, @NonNull Long uid, long start) {
        details.reopen(platform, uid, start);
    }

    /**
     * 把本场明细整份留下来
     * <p>
     * 调用方应把它<b>排在场次归档之后</b>：两者读的是同一份尚未清零的数据，先后本不影响读数，
     * 但场次归档是运营统计的命根子，明细写盘慢得多（几十上百 KB）——
     * <b>先把小的那份落定，再去写大的那份</b>，程序若在这中间被杀，丢的是明细不是场次。
     * <p>
     * 明细失败不连累场次：{@link LiveDetailArchive} 自己吞掉 IO 异常；
     * 调用方仍应兜住 RuntimeException，别让同一事件上排在后面的监听整段跳过。
     * @param platform 直播平台
     * @param source 主播信息
     * @param start 本场开播时刻（毫秒）
     * @param endTime 本场结束时刻（毫秒）
     * @param duration 时长（秒）
     * @param series 本场全部时间序列（{@link #allSeries}）
     * @param peaks 各条序列的峰值（{@link #peaks}）
     * @param upTo 截止时刻：标题轨迹只取不晚于它的条目，高能只数不晚于它的弹幕。
     *              正常下播传 {@link Long#MAX_VALUE}——事件时刻就是终点，照旧全量；
     *              补档传本场结束时刻——结束之后主播改的标题、收到的弹幕不属于已结束的这一场
     */
    public void store(@NonNull String platform, @NonNull LiveStreamerInfo source, long start, long endTime,
                      long duration, Map<String, Map<Long, Double>> series, Map<String, SeriesPeak> peaks,
                      long upTo) {
        Long uid = source.getUid();

        // 排行榜留**全量**：报告图上只画前几名是版面所限，留档只留前几名
        // 就等于把第 30 名往后的人永久丢掉，而回流率、沉睡预警要的恰恰是长尾那一段。
        // 每张榜取多少条，问它自己的参与人数——那正是这张榜的全长
        Map<String, Integer> userCounts = liveDataService.getLiveMetricUserCounts(platform, uid);
        Map<String, List<UserScore>> rankings = new LinkedHashMap<>();
        userCounts.forEach((metric, count) -> {
            if (count > 0) {
                rankings.put(metric, liveDataService.getLiveUserRanking(platform, uid, metric, count));
            }
        });

        // 缺口两份合并到互不重叠：程序停机期间这个房间当然也是断的，两段必然重叠，
        // 留档里各留一份的话，读的人把它们相加就会算出比整场还长的缺口
        List<LiveGap> gaps = LiveGap.merge(List.of(
                liveDataService.downtimeIntervals(start, endTime),
                liveDataService.roomOutageIntervals(platform, uid, start, endTime)));

        // 高能时刻按**弹幕原文**的分钟密度算，而不是问某个指标名要序列：
        // 核心并不知道哪个指标是弹幕（指标名由各平台自行定义），
        // 而弹幕原文本身就是弹幕，这条路平台无关且与原文同源
        List<LiveHighlightFinder.Highlight> highlights = LiveHighlightFinder.find(
                danmuSeries(platform, uid, start, upTo), LiveDataService.SERIES_BUCKET_MILLIS, start, endTime);

        details.store(new LiveDetail(
                LiveDetail.VERSION,
                platform,
                uid,
                source.getUname(),
                source.getRoomId(),
                start,
                endTime,
                duration,
                liveDataService.getLiveMetrics(platform, uid),
                userCounts,
                series,
                rankings,
                liveDataService.getLiveWordFrequencies(platform, uid),
                highlights,
                roomInfoHistory.history(platform, uid, upTo),
                gaps,
                peaks,
                liveDataService.getLiveGifts(platform, uid)));
    }

    /**
     * 本场全部时间序列
     * <p>
     * 逐条问而不是按一张写死的指标名单取：核心不知道有哪些指标，
     * <b>按名单取的话，插件新加一条曲线，明细里就会安静地少一条</b>，
     * 而那一场的序列下次开播就没了。
     */
    public Map<String, Map<Long, Double>> allSeries(@NonNull String platform, @NonNull Long uid) {
        Map<String, Map<Long, Double>> series = new LinkedHashMap<>();
        for (String metric : liveDataService.getLiveSeriesMetrics(platform, uid)) {
            Map<Long, Double> one = liveDataService.getLiveSeries(platform, uid, metric);
            if (!one.isEmpty()) {
                series.put(metric, one);
            }
        }
        return series;
    }

    /**
     * 各条序列的峰值
     * <p>
     * <b>时刻与取值同源</b>：取最大值的那一格，它的键就是时刻。
     * 分两趟各求一次的话，会算出「峰值 137，出现在第 42 分钟」而第 42 分钟其实是 96 的情形。
     * <p>
     * 空序列不产生峰值项——「这条曲线一个点都没有」与「峰值为 0」是两回事。
     */
    public Map<String, SeriesPeak> peaks(Map<String, Map<Long, Double>> series) {
        Map<String, SeriesPeak> peaks = new LinkedHashMap<>();
        series.forEach((metric, buckets) -> buckets.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .ifPresent(top -> peaks.put(metric, new SeriesPeak(top.getKey(), top.getValue()))));
        return peaks;
    }

    /**
     * 从本场弹幕原文数出按分钟的密度序列
     * <p>
     * 只数计入弹幕条数的那几类（见 {@link org.frostnova.nova.core.model.DanmuRecord#countsAsDanmu}）：
     * 把一条 30 元的付费留言算进弹幕密度，它在曲线上就等价于一句「哈哈」。
     * <p>
     * 没留下原文时是空表，高能时刻随之为空——那是真话：<b>没有原文就挑不出高能片段</b>。
     * @param upTo 只数不晚于这一时刻的弹幕：补档那一场的原文目录里，可能已经混进
     *              结束之后收到的弹幕（封存之前它一直照收），那些不属于这一场
     */
    private Map<Long, Double> danmuSeries(@NonNull String platform, @NonNull Long uid, long start, long upTo) {
        Map<Long, Double> series = new java.util.TreeMap<>();
        for (DanmuRecord record : details.readDanmu(platform, uid, start)) {
            if (!record.countsAsDanmu() || record.at() > upTo) {
                continue;
            }
            long bucket = record.at() / LiveDataService.SERIES_BUCKET_MILLIS * LiveDataService.SERIES_BUCKET_MILLIS;
            series.merge(bucket, 1.0, Double::sum);
        }
        return series;
    }
}
