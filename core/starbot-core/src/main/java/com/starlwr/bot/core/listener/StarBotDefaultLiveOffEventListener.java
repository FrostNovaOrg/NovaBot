package com.starlwr.bot.core.listener;

import com.starlwr.bot.core.analytics.LiveDetail;
import com.starlwr.bot.core.analytics.LiveHighlightFinder;
import com.starlwr.bot.core.enums.LiveEndReason;
import com.starlwr.bot.core.event.live.common.LiveOffEvent;
import com.starlwr.bot.core.model.DanmuRecord;
import com.starlwr.bot.core.model.LiveGap;
import com.starlwr.bot.core.model.LiveSession;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.SeriesPeak;
import com.starlwr.bot.core.model.UserScore;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.LiveDetailArchive;
import com.starlwr.bot.core.service.LiveInterventionTracker;
import com.starlwr.bot.core.service.LiveRoomInfoHistory;
import com.starlwr.bot.core.service.LiveSessionArchive;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * StarBot 下播事件监听器
 */
@Slf4j
@Component
public class StarBotDefaultLiveOffEventListener {
    private final LiveDataService liveDataService;

    private final LiveSessionArchive archive;

    private final LiveInterventionTracker interventionTracker;

    private final LiveRoomInfoHistory roomInfoHistory;

    /**
     * 本场明细留档。场次归档留「这一场发生过」，它留「这一场的原始数据」
     */
    private final LiveDetailArchive details;

    @Autowired
    public StarBotDefaultLiveOffEventListener(LiveDataService liveDataService, LiveSessionArchive archive,
                                              LiveInterventionTracker interventionTracker, LiveRoomInfoHistory roomInfoHistory,
                                              LiveDetailArchive details) {
        this.liveDataService = liveDataService;
        this.archive = archive;
        this.interventionTracker = interventionTracker;
        this.roomInfoHistory = roomInfoHistory;
        this.details = details;
    }

    /**
     * 更新房间数据
     * @param event 事件
     */
    @Order(-10000)
    @EventListener
    public void onLiveOffEvent(LiveOffEvent event) {
        log.info("[{}] [下播] {}(UID: {}, 房间号: {})", event.getPlatform(), event.getSource().getUname(), event.getSource().getUid(), event.getSource().getRoomIdString());

        liveDataService.setLiveStatus(event.getPlatform(), event.getSource().getUid(), false);
        liveDataService.setLiveEndTime(event.getPlatform(), event.getSource().getUid(), event.getTimestamp());

        // 归档与并入累计读的是同一份尚未清零的本场数据，互不影响，先后无所谓；
        // 但两者都必须赶在**开播清零之前**，也就是趁下播这一刻做掉
        archiveSession(event);

        // 本场数据并入累计。选在下播而非开播清零前，是因为程序可能在两场之间重启，
        // 拖到开播才并入会整场丢失。本场数据本身保留到下次开播，报告仍读得到
        liveDataService.mergeLiveDataIntoTotal(event.getPlatform(), event.getSource().getUid());
    }

    /**
     * 把本场直播归档，供运营分析
     * <p>
     * 没有开播时间就不归档：一条没有起点的记录既算不出时长，也无法归入任何统计周期，
     * 留着只会污染分析结果。这种情况多见于程序在直播中途才启动。
     */
    private void archiveSession(LiveOffEvent event) {
        LiveStreamerInfo source = event.getSource();
        Optional<Long> start = liveDataService.getLiveStartTime(event.getPlatform(), source.getUid());
        if (start.isEmpty()) {
            log.info("{} 没有记录到开播时间, 本场不归档（多为程序在直播中途才启动）", source.getUname());
            return;
        }

        long endTime = event.getTimestamp();
        // 时钟回拨或数据异常会让时长成为负数，夹到 0 而不是让统计里出现负值
        long duration = Math.max(0, (endTime - start.get()) / 1000);

        LiveEndReason endReason = interventionTracker.endReason(
                event.getPlatform(), source.getUid(), Instant.ofEpochMilli(endTime));
        if (endReason != LiveEndReason.NORMAL) {
            log.warn("{} 本场直播{}, 时长 {} 秒不代表正常水平", source.getUname(), endReason.getDescription(), duration);
        }

        // 本场之内程序停过的时段，只算与本场重叠的部分
        long gap = liveDataService.downtimeWithin(start.get(), endTime) / 1000;
        if (gap > 0) {
            log.warn("{} 本场有 {} 秒因程序停机未采集, 各项计数只是下界", source.getUname(), gap);
        }

        // 本场之内**这个直播间自己**断线的时段。与上面的程序停机分开算、分开存：
        // 停机期间所有房间都在断，两段必然重叠，加起来就是重复计数
        long outage = liveDataService.roomOutageWithin(event.getPlatform(), source.getUid(), start.get(), endTime) / 1000;
        if (outage > 0) {
            log.warn("{} 本场有 {} 秒因直播间断线未采集, 各项计数只是下界", source.getUname(), outage);
        }

        // 各条序列的峰值。**必须在这一刻算**：序列活在本场数据里，下一次开播即清零，
        // 事后无论如何也算不出「这一场最高多少人在看」
        Map<String, List<Long>> userSets = liveDataService.getLiveMetricUserSets(event.getPlatform(), source.getUid());
        Map<String, Map<Long, Double>> series = allSeries(event.getPlatform(), source.getUid());
        Map<String, SeriesPeak> peaks = peaks(series);

        archive.append(new LiveSession(
                event.getPlatform(),
                source.getUid(),
                source.getUname(),
                source.getRoomId(),
                start.get(),
                endTime,
                duration,
                liveDataService.getLiveMetrics(event.getPlatform(), source.getUid()),
                liveDataService.getLiveMetricUserCounts(event.getPlatform(), source.getUid()),
                endReason,
                roomInfoHistory.history(event.getPlatform(), source.getUid()),
                gap,
                // F5 名单。⚠️ 它与上面的人数是**两次独立调用**，各自持锁但彼此之间没有原子性：
                // 两次之间恰好又来一个人，size 与名单长度就会差 1。
                // 这里**不**用名单反推人数——`getLiveMetricUserSets` 是带默认实现的接口方法，
                // 自定义实现可能只覆盖了人数那一个，反推会把人数变成 0。
                // 差 1 的代价（分析侧一个计数偏差）远小于把人数打成 0。
                userSets,
                outage,
                peaks));

        try {
            archiveDetail(event, source, start.get(), endTime, duration, series, peaks);
        } catch (RuntimeException e) {
            log.error("留档直播明细失败, 该场的报告将无法重新绘制", e);
        }
    }

    /**
     * 把本场明细整份留下来
     * <p>
     * <b>排在场次归档之后</b>：两者读的是同一份尚未清零的数据，先后本不影响读数，
     * 但场次归档是运营统计的命根子，明细写盘慢得多（几十上百 KB）——
     * <b>先把小的那份落定，再去写大的那份</b>，程序若在这中间被杀，丢的是明细不是场次。
     * <p>
     * 明细失败不连累场次：{@link LiveDetailArchive} 自己吞掉异常，此处不加 try。
     */
    private void archiveDetail(LiveOffEvent event, LiveStreamerInfo source, long start, long endTime,
                               long duration, Map<String, Map<Long, Double>> series, Map<String, SeriesPeak> peaks) {
        String platform = event.getPlatform();
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
                danmuSeries(platform, uid, start), LiveDataService.SERIES_BUCKET_MILLIS, start, endTime);

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
                roomInfoHistory.history(platform, uid),
                gaps,
                peaks));
    }

    /**
     * 本场全部时间序列
     * <p>
     * 逐条问而不是按一张写死的指标名单取：核心不知道有哪些指标，
     * <b>按名单取的话，插件新加一条曲线，明细里就会安静地少一条</b>，
     * 而那一场的序列下次开播就没了。
     */
    private Map<String, Map<Long, Double>> allSeries(String platform, Long uid) {
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
    private Map<String, SeriesPeak> peaks(Map<String, Map<Long, Double>> series) {
        Map<String, SeriesPeak> peaks = new LinkedHashMap<>();
        series.forEach((metric, buckets) -> buckets.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .ifPresent(top -> peaks.put(metric, new SeriesPeak(top.getKey(), top.getValue()))));
        return peaks;
    }

    /**
     * 从本场弹幕原文数出按分钟的密度序列
     * <p>
     * 只数计入弹幕条数的那几类（见 {@link com.starlwr.bot.core.model.DanmuRecord#countsAsDanmu}）：
     * 把一条 30 元的付费留言算进弹幕密度，它在曲线上就等价于一句「哈哈」。
     * <p>
     * 没留下原文时是空表，高能时刻随之为空——那是真话：<b>没有原文就挑不出高能片段</b>。
     */
    private Map<Long, Double> danmuSeries(String platform, Long uid, long start) {
        Map<Long, Double> series = new java.util.TreeMap<>();
        for (DanmuRecord record : details.readDanmu(platform, uid, start)) {
            if (!record.countsAsDanmu()) {
                continue;
            }
            long bucket = record.at() / LiveDataService.SERIES_BUCKET_MILLIS * LiveDataService.SERIES_BUCKET_MILLIS;
            series.merge(bucket, 1.0, Double::sum);
        }
        return series;
    }
}
