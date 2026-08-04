package com.starlwr.bot.core.service;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 默认直播数据服务实现
 */
@Slf4j
@Service
public class DefaultLiveDataService implements LiveDataService {
    private final StarBotCoreProperties properties;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private JSONObject cache = new JSONObject();

    @Autowired
    public DefaultLiveDataService(StarBotCoreProperties properties) {
        this.properties = properties;
    }

    /**
     * 加载直播数据
     */
    @Order(-10000)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReadyEvent() {
        if (properties.getLive().isSaveLiveData()) {
            String liveDataPath = properties.getLive().getLiveDataPath();
            log.info("开始从 {} 中加载直播数据", liveDataPath);
            try {
                cache = JSONObject.parseObject(Files.readString(Path.of(liveDataPath)));
            } catch (NoSuchFileException e) {
                log.warn("直播数据文件 {} 不存在, 建立新文件", liveDataPath);
            } catch (Exception e) {
                log.error("读取直播数据 {} 异常", liveDataPath, e);
            }
            log.info("直播数据加载完成");
            autoSave();
        }
    }

    /**
     * 保存直播数据
     */
    @Order(0)
    @EventListener(ContextClosedEvent.class)
    public void onContextClosedEvent() {
        // 先停掉自动保存，避免与此处的收尾保存同时写同一个文件
        scheduler.shutdownNow();

        if (cache.isEmpty()) {
            return;
        }

        if (properties.getLive().isSaveLiveData()) {
            String liveDataPath = properties.getLive().getLiveDataPath();
            log.info("开始保存直播数据至 {}", liveDataPath);
            try {
                Files.writeString(Path.of(liveDataPath), snapshot());
            } catch (Exception e) {
                log.error("保存直播数据至 {} 异常", liveDataPath, e);
            }
            log.info("直播数据已保存至 {}", liveDataPath);
        }
    }

    public void autoSave() {
        int interval = properties.getLive().getAutoSaveLiveDataInterval();
        Path path = Path.of(properties.getLive().getLiveDataPath());

        scheduler.scheduleWithFixedDelay(() -> {
            Thread.currentThread().setName("auto-save-data");

            try {
                Files.writeString(path, snapshot());
            } catch (Exception e) {
                log.error("自动保存直播数据异常", e);
            }

        }, interval, interval, TimeUnit.SECONDS);
    }

    /**
     * 生成缓存的 JSON 快照
     * <p>
     * 统计指标随直播间消息高频写入，序列化遍历期间若结构变化会直接抛异常，
     * 故拿锁序列化；文件写入在锁外进行，避免磁盘慢时阻塞指标写入。
     */
    private String snapshot() {
        synchronized (metricLock) {
            return cache.toJSONString();
        }
    }

    // ================ 直播间状态 ================

    /**
     * 获取直播间状态
     *
     * @param platform 直播平台
     * @param uid      UID
     * @return 直播间状态，true：已开播，false：未开播
     */
    @Override
    public Optional<Boolean> getLiveStatus(@NonNull String platform, @NonNull Long uid) {
        String key = "LiveStatus:" + platform;
        return Optional.ofNullable(cache.getJSONObject(key)).map(data -> data.getBoolean(String.valueOf(uid)));
    }

    /**
     * 设置直播间状态
     *
     * @param platform 直播平台
     * @param uid      UID
     * @param status   直播间状态，true：已开播，false：未开播
     */
    @Override
    public void setLiveStatus(@NonNull String platform, @NonNull Long uid, boolean status) {
        String key = "LiveStatus:" + platform;
        cache.putIfAbsent(key, new JSONObject());
        cache.getJSONObject(key).put(String.valueOf(uid), status);
    }

    // ================ 直播开始时间 ================

    /**
     * 获取最近一场直播开始时间戳
     *
     * @param platform 直播平台
     * @param uid      UID
     * @return 最近一场直播开始时间戳
     */
    @Override
    public Optional<Long> getLiveStartTime(@NonNull String platform, @NonNull Long uid) {
        String key = "LiveStartTime:" + platform;
        return Optional.ofNullable(cache.getJSONObject(key)).map(data -> data.getLong(String.valueOf(uid)));
    }

    /**
     * 设置最近一场直播开始时间戳
     *
     * @param platform  直播平台
     * @param uid       UID
     * @param startTime 最近一场直播开始时间戳
     */
    @Override
    public void setLiveStartTime(@NonNull String platform, @NonNull Long uid, long startTime) {
        String key = "LiveStartTime:" + platform;
        cache.putIfAbsent(key, new JSONObject());
        cache.getJSONObject(key).put(String.valueOf(uid), startTime);
    }

    // ================ 直播结束时间 ================

    /**
     * 获取最近一场直播结束时间戳
     *
     * @param platform 直播平台
     * @param uid      UID
     * @return 最近一场直播结束时间戳
     */
    @Override
    public Optional<Long> getLiveEndTime(@NonNull String platform, @NonNull Long uid) {
        String key = "LiveEndTime:" + platform;
        return Optional.ofNullable(cache.getJSONObject(key)).map(data -> data.getLong(String.valueOf(uid)));
    }

    /**
     * 设置最近一场直播结束时间戳
     *
     * @param platform 直播平台
     * @param uid      UID
     * @param endTime  最近一场直播结束时间戳
     */
    @Override
    public void setLiveEndTime(@NonNull String platform, @NonNull Long uid, long endTime) {
        String key = "LiveEndTime:" + platform;
        cache.putIfAbsent(key, new JSONObject());
        cache.getJSONObject(key).put(String.valueOf(uid), endTime);
    }

    /**
     * 删除最近一场直播结束时间戳
     *
     * @param platform 直播平台
     * @param uid      UID
     */
    @Override
    public void deleteLiveEndTime(@NonNull String platform, @NonNull Long uid) {
        String key = "LiveEndTime:" + platform;
        Optional.ofNullable(cache.getJSONObject(key)).ifPresent(data -> data.remove(String.valueOf(uid)));
    }

    // ================ 本场直播统计指标 ================

    /**
     * 独立用户集合的容量上限，防止超大直播间把用户列表撑到不可收拾
     */
    private static final int METRIC_USER_LIMIT = 100_000;

    /**
     * 统计指标的写锁
     * <p>
     * 指标随直播间消息高频写入（弹幕高峰可达每秒数百条），与自动保存的序列化并发时
     * 会让 fastjson2 在遍历中途遇到结构变化。开关播状态等低频写入维持原状不加锁。
     */
    private final Object metricLock = new Object();

    /**
     * 累加本场直播的统计指标
     *
     * @param platform 直播平台
     * @param uid      UID
     * @param metric   指标名
     * @param delta    增量
     */
    @Override
    public void incrementLiveMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric, double delta) {
        synchronized (metricLock) {
            JSONObject metrics = metricsOf(platform, uid);
            metrics.put(metric, metrics.getDoubleValue(metric) + delta);
        }
    }

    /**
     * 以取最大值的方式更新本场直播的统计指标
     *
     * @param platform 直播平台
     * @param uid      UID
     * @param metric   指标名
     * @param value    候选值
     */
    @Override
    public void maxLiveMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric, double value) {
        synchronized (metricLock) {
            JSONObject metrics = metricsOf(platform, uid);
            metrics.put(metric, Math.max(metrics.getDoubleValue(metric), value));
        }
    }

    /**
     * 获取本场直播的统计指标
     *
     * @param platform 直播平台
     * @param uid      UID
     * @param metric   指标名
     * @return 指标值，未记录时为 0
     */
    @Override
    public double getLiveMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric) {
        synchronized (metricLock) {
            return Optional.ofNullable(cache.getJSONObject("LiveMetric:" + platform))
                    .map(data -> data.getJSONObject(String.valueOf(uid)))
                    .map(metrics -> metrics.getDoubleValue(metric))
                    .orElse(0.0);
        }
    }

    /**
     * 记录参与某项互动的用户
     * <p>
     * 以「用户 UID → 1」的映射而非数组存储，含判重的写入是 O(1)
     *
     * @param platform 直播平台
     * @param uid      UID
     * @param metric   指标名
     * @param userUid  参与用户的 UID
     */
    @Override
    public void recordLiveMetricUser(@NonNull String platform, @NonNull Long uid, @NonNull String metric, @NonNull Long userUid) {
        synchronized (metricLock) {
            String key = "LiveMetricUser:" + platform;
            cache.putIfAbsent(key, new JSONObject());
            JSONObject byUid = cache.getJSONObject(key);
            byUid.putIfAbsent(String.valueOf(uid), new JSONObject());
            JSONObject byMetric = byUid.getJSONObject(String.valueOf(uid));
            byMetric.putIfAbsent(metric, new JSONObject());
            JSONObject users = byMetric.getJSONObject(metric);

            if (users.size() < METRIC_USER_LIMIT) {
                users.put(String.valueOf(userUid), 1);
            }
        }
    }

    /**
     * 获取参与某项互动的独立用户数
     *
     * @param platform 直播平台
     * @param uid      UID
     * @param metric   指标名
     * @return 独立用户数，未记录时为 0
     */
    @Override
    public int getLiveMetricUserCount(@NonNull String platform, @NonNull Long uid, @NonNull String metric) {
        synchronized (metricLock) {
            return Optional.ofNullable(cache.getJSONObject("LiveMetricUser:" + platform))
                    .map(data -> data.getJSONObject(String.valueOf(uid)))
                    .map(byMetric -> byMetric.getJSONObject(metric))
                    .map(JSONObject::size)
                    .orElse(0);
        }
    }

    /**
     * 取出（必要时创建）指定主播的指标容器，调用方需持有 {@link #metricLock}
     */
    private JSONObject metricsOf(String platform, Long uid) {
        String key = "LiveMetric:" + platform;
        cache.putIfAbsent(key, new JSONObject());
        JSONObject byUid = cache.getJSONObject(key);
        byUid.putIfAbsent(String.valueOf(uid), new JSONObject());
        return byUid.getJSONObject(String.valueOf(uid));
    }

    // ================ 其他操作 ================

    /**
     * 重置最近一场直播数据
     * <p>
     * 在开播时被调用，清空上一场直播累计的统计指标，让新一场从零开始
     *
     * @param platform 直播平台
     * @param uid      UID
     */
    @Override
    public void resetLiveData(@NonNull String platform, @NonNull Long uid) {
        synchronized (metricLock) {
            Optional.ofNullable(cache.getJSONObject("LiveMetric:" + platform))
                    .ifPresent(data -> data.remove(String.valueOf(uid)));
            Optional.ofNullable(cache.getJSONObject("LiveMetricUser:" + platform))
                    .ifPresent(data -> data.remove(String.valueOf(uid)));
        }
    }
}
