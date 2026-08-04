package com.starlwr.bot.core.service;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.model.UserScore;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
     * 已就用户数上限告警过的「主播 + 指标」，用于每种组合只警告一次
     * <p>
     * 达到上限后每条弹幕都会走到该分支，不去重会瞬间刷屏
     */
    private final Set<String> userLimitWarned = ConcurrentHashMap.newKeySet();

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
     * 累加某个用户在本场直播的得分
     * <p>
     * 以「用户 UID → 得分」的映射而非数组存储，含判重的写入是 O(1)。
     * 独立人数即该映射的大小，因此计分与计人数共用同一份数据。
     *
     * @param platform 直播平台
     * @param uid      主播 UID
     * @param metric   指标名
     * @param userUid  用户 UID
     * @param delta    增量
     */
    @Override
    public void incrementLiveUserMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric,
                                        @NonNull Long userUid, double delta) {
        synchronized (metricLock) {
            String key = "LiveMetricUser:" + platform;
            cache.putIfAbsent(key, new JSONObject());
            JSONObject byUid = cache.getJSONObject(key);
            byUid.putIfAbsent(String.valueOf(uid), new JSONObject());
            JSONObject byMetric = byUid.getJSONObject(String.valueOf(uid));
            byMetric.putIfAbsent(metric, new JSONObject());
            JSONObject users = byMetric.getJSONObject(metric);

            String userKey = String.valueOf(userUid);
            Double current = users.getDouble(userKey);
            if (current == null) {
                // 已达上限时不再收录新用户，但已在表内的继续累加：
                // 丢弃新用户只影响长尾，而中断已有用户的累加会让其数据凭空变小
                if (users.size() >= METRIC_USER_LIMIT) {
                    if (userLimitWarned.add(uid + ":" + metric)) {
                        log.warn("主播 {} 的 {} 计分表已达 {} 人上限, 后续新用户不再收录, 排行榜与人数统计会偏小",
                                uid, metric, METRIC_USER_LIMIT);
                    }
                    return;
                }
                users.put(userKey, delta);
            } else {
                users.put(userKey, current + delta);
            }
        }
    }

    /**
     * 获取某个用户在本场直播的得分
     *
     * @param platform 直播平台
     * @param uid      主播 UID
     * @param metric   指标名
     * @param userUid  用户 UID
     * @return 得分，未记录时为 0
     */
    @Override
    public double getLiveUserMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric,
                                    @NonNull Long userUid) {
        synchronized (metricLock) {
            return Optional.ofNullable(users(platform, uid, metric))
                    .map(users -> users.getDoubleValue(String.valueOf(userUid)))
                    .orElse(0.0);
        }
    }

    /**
     * 获取本场直播某项指标的用户排行
     *
     * @param platform 直播平台
     * @param uid      主播 UID
     * @param metric   指标名
     * @param limit    取前多少名
     * @return 按得分降序排列的用户
     */
    /**
     * 取得本场全部指标的快照，供并入累计存储
     * @param platform 直播平台
     * @param uid 主播 UID
     * @return 指标名到取值的映射
     */
    public java.util.Map<String, Double> liveMetrics(@NonNull String platform, @NonNull Long uid) {
        synchronized (metricLock) {
            JSONObject metrics = Optional.ofNullable(cache.getJSONObject("LiveMetric:" + platform))
                    .map(data -> data.getJSONObject(String.valueOf(uid)))
                    .orElse(null);
            if (metrics == null) {
                return java.util.Map.of();
            }

            java.util.Map<String, Double> result = new java.util.HashMap<>();
            for (String metric : metrics.keySet()) {
                result.put(metric, metrics.getDoubleValue(metric));
            }
            return result;
        }
    }

    /**
     * 取得本场全部用户计分表的快照，供并入累计存储
     * @param platform 直播平台
     * @param uid 主播 UID
     * @return 指标名到「用户 UID → 得分」的映射
     */
    public java.util.Map<String, java.util.Map<Long, Double>> liveUserMetrics(@NonNull String platform, @NonNull Long uid) {
        synchronized (metricLock) {
            JSONObject byMetric = Optional.ofNullable(cache.getJSONObject("LiveMetricUser:" + platform))
                    .map(data -> data.getJSONObject(String.valueOf(uid)))
                    .orElse(null);
            if (byMetric == null) {
                return java.util.Map.of();
            }

            java.util.Map<String, java.util.Map<Long, Double>> result = new java.util.HashMap<>();
            for (String metric : byMetric.keySet()) {
                JSONObject users = byMetric.getJSONObject(metric);
                if (users == null) {
                    continue;
                }
                java.util.Map<Long, Double> scores = new java.util.HashMap<>();
                for (String userKey : users.keySet()) {
                    try {
                        scores.put(Long.parseLong(userKey), users.getDoubleValue(userKey));
                    } catch (NumberFormatException ignored) {
                        // 非法用户键跳过即可，不必让整次并入失败
                    }
                }
                result.put(metric, scores);
            }
            return result;
        }
    }

    /**
     * 取得本场记录到的用户昵称快照，供并入累计存储
     * @param platform 直播平台
     * @param uid 主播 UID
     * @return 用户 UID 到昵称的映射
     */
    public java.util.Map<Long, String> liveUserNames(@NonNull String platform, @NonNull Long uid) {
        synchronized (metricLock) {
            JSONObject names = Optional.ofNullable(cache.getJSONObject("LiveUserName:" + platform))
                    .map(data -> data.getJSONObject(String.valueOf(uid)))
                    .orElse(null);
            if (names == null) {
                return java.util.Map.of();
            }

            java.util.Map<Long, String> result = new java.util.HashMap<>();
            for (String userKey : names.keySet()) {
                try {
                    result.put(Long.parseLong(userKey), names.getString(userKey));
                } catch (NumberFormatException ignored) {
                    // 同上
                }
            }
            return result;
        }
    }

    /**
     * 记录用户昵称
     *
     * @param platform 直播平台
     * @param uid      主播 UID
     * @param userUid  用户 UID
     * @param userName 用户昵称
     */
    @Override
    public void recordLiveUserName(@NonNull String platform, @NonNull Long uid, @NonNull Long userUid, String userName) {
        if (userName == null || userName.isBlank()) {
            return;
        }

        synchronized (metricLock) {
            String key = "LiveUserName:" + platform;
            cache.putIfAbsent(key, new JSONObject());
            JSONObject byUid = cache.getJSONObject(key);
            byUid.putIfAbsent(String.valueOf(uid), new JSONObject());
            JSONObject names = byUid.getJSONObject(String.valueOf(uid));

            String userKey = String.valueOf(userUid);
            // 与计分表同样的容量约束：昵称表只为已计分的用户服务，不应比它更大
            if (names.containsKey(userKey) || names.size() < METRIC_USER_LIMIT) {
                names.put(userKey, userName);
            }
        }
    }

    @Override
    public List<UserScore> getLiveUserRanking(@NonNull String platform, @NonNull Long uid, @NonNull String metric, int limit) {
        if (limit <= 0) {
            return List.of();
        }

        synchronized (metricLock) {
            JSONObject users = users(platform, uid, metric);
            if (users == null) {
                return List.of();
            }

            // JSON 结构不像 Redis 的 zset 那样自带排序，只能取出后在内存里排。
            // 单直播间的用户数有上限，这个开销可以接受，且排行榜只在下播与查询时才取
            JSONObject names = Optional.ofNullable(cache.getJSONObject("LiveUserName:" + platform))
                    .map(data -> data.getJSONObject(String.valueOf(uid)))
                    .orElseGet(JSONObject::new);

            List<UserScore> scores = new ArrayList<>(users.size());
            for (String userKey : users.keySet()) {
                try {
                    scores.add(new UserScore(Long.parseLong(userKey), names.getString(userKey), users.getDoubleValue(userKey)));
                } catch (NumberFormatException e) {
                    // 手工编辑数据文件等情况下可能混入非法键，跳过即可，不必让整个排行榜失败
                    log.debug("跳过计分表中的非法用户键: {}", userKey);
                }
            }

            scores.sort(Comparator.comparingDouble(UserScore::score).reversed());
            return scores.size() <= limit ? scores : List.copyOf(scores.subList(0, limit));
        }
    }

    /**
     * 取出某项指标的用户计分表，调用方需持有 {@link #metricLock}
     */
    private JSONObject users(String platform, Long uid, String metric) {
        return Optional.ofNullable(cache.getJSONObject("LiveMetricUser:" + platform))
                .map(data -> data.getJSONObject(String.valueOf(uid)))
                .map(byMetric -> byMetric.getJSONObject(metric))
                .orElse(null);
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

    /**
     * 词频表的词汇量上限，超过后不再收录新词（已收录的词仍会累计）
     */
    private static final int WORD_FREQUENCY_LIMIT = 5_000;

    /**
     * 累计本场直播的词频
     *
     * @param platform 直播平台
     * @param uid      UID
     * @param word     词语
     */
    @Override
    public void incrementLiveWordFrequency(@NonNull String platform, @NonNull Long uid, @NonNull String word) {
        synchronized (metricLock) {
            String key = "LiveWordFrequency:" + platform;
            cache.putIfAbsent(key, new JSONObject());
            JSONObject byUid = cache.getJSONObject(key);
            byUid.putIfAbsent(String.valueOf(uid), new JSONObject());
            JSONObject words = byUid.getJSONObject(String.valueOf(uid));

            Integer current = words.getInteger(word);
            if (current == null && words.size() >= WORD_FREQUENCY_LIMIT) {
                return;
            }
            words.put(word, current == null ? 1 : current + 1);
        }
    }

    /**
     * 获取本场直播的词频表
     *
     * @param platform 直播平台
     * @param uid      UID
     * @return 词语到出现次数的映射，未记录时为空表
     */
    @Override
    public Map<String, Integer> getLiveWordFrequencies(@NonNull String platform, @NonNull Long uid) {
        synchronized (metricLock) {
            JSONObject words = Optional.ofNullable(cache.getJSONObject("LiveWordFrequency:" + platform))
                    .map(data -> data.getJSONObject(String.valueOf(uid)))
                    .orElse(null);
            if (words == null) {
                return Map.of();
            }

            Map<String, Integer> result = new HashMap<>();
            for (String word : words.keySet()) {
                Integer count = words.getInteger(word);
                if (count != null) {
                    result.put(word, count);
                }
            }
            return result;
        }
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
            Optional.ofNullable(cache.getJSONObject("LiveWordFrequency:" + platform))
                    .ifPresent(data -> data.remove(String.valueOf(uid)));
            Optional.ofNullable(cache.getJSONObject("LiveUserName:" + platform))
                    .ifPresent(data -> data.remove(String.valueOf(uid)));
        }
    }
}
