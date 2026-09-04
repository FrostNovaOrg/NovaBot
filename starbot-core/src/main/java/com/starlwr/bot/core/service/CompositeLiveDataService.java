package com.starlwr.bot.core.service;

import com.starlwr.bot.core.model.LiveGap;
import com.starlwr.bot.core.model.UserScore;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 常驻的直播数据服务：本场走本地，累计走可热换的外部存储
 * <p>
 * <b>这是唯一一个注册的 {@link LiveDataService}</b>，不再按配置择一。此前是两个实现二选一，
 * 而选哪一个在启动那一刻就定死了——于是「配好累计存储」与「累计数据真的能用」之间
 * 隔着一次重启，中途所有界面都在说一句过期的话。
 * <p>
 * 分工：本场数据一律委托 {@link DefaultLiveDataService}（数据量小、随开播清零，JSON 够用），
 * 累计数据一律问 {@link TotalDataStorage}（它自己回答此刻配没配、连不连得上）。
 * <p>
 * 累计不可用时，这里的行为与「压根没有累计能力」<b>完全一致</b>——
 * {@link #supportsTotalData()} 回假，各查询回默认值。调用方据此明确告诉使用者
 * 「本机没开累计数据」，而不是把一片 0 画出来当结论。
 */
@Slf4j
@Primary
@Service
public class CompositeLiveDataService implements LiveDataService {
    /**
     * 本场数据
     */
    private final DefaultLiveDataService delegate;

    /**
     * 累计数据
     */
    private final TotalDataStorage total;

    @Autowired
    public CompositeLiveDataService(DefaultLiveDataService delegate, TotalDataStorage total) {
        this.delegate = delegate;
        this.total = total;
    }

    // ================ 累计数据 ================

    @Override
    public boolean supportsTotalData() {
        return total.isAvailable();
    }

    @Override
    public void mergeLiveDataIntoTotal(@NonNull String platform, @NonNull Long uid) {
        RedisTotalDataStore store = total.active();
        if (store == null) {
            // 没开这个能力时下播不该报错：本场报告照发，只是没有「累计」那几行
            return;
        }

        store.merge(platform, uid, new RedisTotalDataStore.LiveSnapshot(
                delegate.liveMetrics(platform, uid),
                delegate.liveUserMetrics(platform, uid),
                delegate.liveUserNames(platform, uid),
                delegate.liveUserFaces(platform, uid)));
    }

    @Override
    public double getTotalMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric) {
        RedisTotalDataStore store = total.active();
        return store == null ? 0 : store.getTotalMetric(platform, uid, metric);
    }

    @Override
    public double getTotalUserMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric,
                                     @NonNull Long userUid) {
        RedisTotalDataStore store = total.active();
        return store == null ? 0 : store.getTotalUserMetric(platform, uid, metric, userUid);
    }

    @Override
    public List<UserScore> getTotalUserRanking(@NonNull String platform, @NonNull Long uid,
                                               @NonNull String metric, int limit) {
        RedisTotalDataStore store = total.active();
        return store == null ? List.of() : store.getTotalUserRanking(platform, uid, metric, limit);
    }

    @Override
    public int getTotalUserRank(@NonNull String platform, @NonNull Long uid, @NonNull String metric,
                                @NonNull Long userUid) {
        RedisTotalDataStore store = total.active();
        return store == null ? 0 : store.getTotalUserRank(platform, uid, metric, userUid);
    }

    @Override
    public int getTotalMetricUserCount(@NonNull String platform, @NonNull Long uid, @NonNull String metric) {
        RedisTotalDataStore store = total.active();
        return store == null ? 0 : store.getTotalMetricUserCount(platform, uid, metric);
    }

    // ================ 本场数据一律委托 ================

    @Override
    public Optional<Boolean> getLiveStatus(@NonNull String platform, @NonNull Long uid) {
        return delegate.getLiveStatus(platform, uid);
    }

    @Override
    public void setLiveStatus(@NonNull String platform, @NonNull Long uid, boolean status) {
        delegate.setLiveStatus(platform, uid, status);
    }

    @Override
    public Optional<Long> getLiveStartTime(@NonNull String platform, @NonNull Long uid) {
        return delegate.getLiveStartTime(platform, uid);
    }

    @Override
    public void setLiveStartTime(@NonNull String platform, @NonNull Long uid, long startTime) {
        delegate.setLiveStartTime(platform, uid, startTime);
    }

    @Override
    public Optional<Long> getLastSaveTime() {
        return delegate.getLastSaveTime();
    }

    @Override
    public Optional<Boolean> wasCleanShutdown() {
        return delegate.wasCleanShutdown();
    }

    @Override
    public void recordDowntime(long from, long to, @NonNull LiveGap.Reason reason) {
        delegate.recordDowntime(from, to, reason);
    }

    @Override
    public List<LiveGap> downtimeIntervals(long from, long to) {
        return delegate.downtimeIntervals(from, to);
    }

    @Override
    public Optional<Long> getLiveEndTime(@NonNull String platform, @NonNull Long uid) {
        return delegate.getLiveEndTime(platform, uid);
    }

    @Override
    public void setLiveEndTime(@NonNull String platform, @NonNull Long uid, long endTime) {
        delegate.setLiveEndTime(platform, uid, endTime);
    }

    @Override
    public void deleteLiveEndTime(@NonNull String platform, @NonNull Long uid) {
        delegate.deleteLiveEndTime(platform, uid);
    }

    @Override
    public void resetLiveData(@NonNull String platform, @NonNull Long uid) {
        delegate.resetLiveData(platform, uid);
    }

    @Override
    public void incrementLiveMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric, double delta) {
        delegate.incrementLiveMetric(platform, uid, metric, delta);
    }

    @Override
    public void setLiveMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric, double value) {
        delegate.setLiveMetric(platform, uid, metric, value);
    }

    @Override
    public void maxLiveMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric, double value) {
        delegate.maxLiveMetric(platform, uid, metric, value);
    }

    @Override
    public double getLiveMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric) {
        return delegate.getLiveMetric(platform, uid, metric);
    }

    @Override
    public int getLiveMetricUserCount(@NonNull String platform, @NonNull Long uid, @NonNull String metric) {
        return delegate.getLiveMetricUserCount(platform, uid, metric);
    }

    @Override
    public void incrementLiveUserMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric,
                                        @NonNull Long userUid, double delta) {
        delegate.incrementLiveUserMetric(platform, uid, metric, userUid, delta);
    }

    @Override
    public double getLiveUserMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric,
                                    @NonNull Long userUid) {
        return delegate.getLiveUserMetric(platform, uid, metric, userUid);
    }

    @Override
    public List<UserScore> getLiveUserRanking(@NonNull String platform, @NonNull Long uid,
                                              @NonNull String metric, int limit) {
        return delegate.getLiveUserRanking(platform, uid, metric, limit);
    }

    @Override
    public int getLiveUserRank(@NonNull String platform, @NonNull Long uid, @NonNull String metric,
                               @NonNull Long userUid) {
        return delegate.getLiveUserRank(platform, uid, metric, userUid);
    }

    @Override
    public void recordLiveUserName(@NonNull String platform, @NonNull Long uid, @NonNull Long userUid, String userName) {
        delegate.recordLiveUserName(platform, uid, userUid, userName);
    }

    @Override
    public void recordLiveUserFace(@NonNull String platform, @NonNull Long uid, @NonNull Long userUid, String userFace) {
        delegate.recordLiveUserFace(platform, uid, userUid, userFace);
    }

    @Override
    public Map<String, Double> getLiveMetrics(@NonNull String platform, @NonNull Long uid) {
        return delegate.getLiveMetrics(platform, uid);
    }

    @Override
    public Map<String, Integer> getLiveMetricUserCounts(@NonNull String platform, @NonNull Long uid) {
        return delegate.getLiveMetricUserCounts(platform, uid);
    }

    /**
     * 本场名单同样走本地委托：外部存储只存跨场累计，本场数据一律在本地。
     * 与人数那一对方法保持同一条路径，避免两者读到不同的数据源。
     */
    @Override
    public Map<String, List<Long>> getLiveMetricUserSets(@NonNull String platform, @NonNull Long uid) {
        return delegate.getLiveMetricUserSets(platform, uid);
    }

    @Override
    public void recordRoomOutage(@NonNull String platform, @NonNull Long uid, long from, long to) {
        delegate.recordRoomOutage(platform, uid, from, to);
    }

    @Override
    public List<LiveGap> roomOutageIntervals(@NonNull String platform, @NonNull Long uid, long from, long to) {
        return delegate.roomOutageIntervals(platform, uid, from, to);
    }

    @Override
    public void incrementLiveSeries(@NonNull String platform, @NonNull Long uid, @NonNull String metric,
                                    long timestamp, double delta) {
        delegate.incrementLiveSeries(platform, uid, metric, timestamp, delta);
    }

    @Override
    public void maxLiveSeries(@NonNull String platform, @NonNull Long uid, @NonNull String metric,
                              long timestamp, double value) {
        delegate.maxLiveSeries(platform, uid, metric, timestamp, value);
    }

    @Override
    public Map<Long, Double> getLiveSeries(@NonNull String platform, @NonNull Long uid, @NonNull String metric) {
        return delegate.getLiveSeries(platform, uid, metric);
    }

    @Override
    public Set<String> getLiveSeriesMetrics(@NonNull String platform, @NonNull Long uid) {
        return delegate.getLiveSeriesMetrics(platform, uid);
    }

    @Override
    public void incrementLiveWordFrequency(@NonNull String platform, @NonNull Long uid, @NonNull String word) {
        delegate.incrementLiveWordFrequency(platform, uid, word);
    }

    @Override
    public Map<String, Integer> getLiveWordFrequencies(@NonNull String platform, @NonNull Long uid) {
        return delegate.getLiveWordFrequencies(platform, uid);
    }
}
