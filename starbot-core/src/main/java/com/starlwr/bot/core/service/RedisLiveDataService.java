package com.starlwr.bot.core.service;

import com.starlwr.bot.core.model.UserScore;
import com.starlwr.bot.core.util.FaceUrlCodec;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 带累计数据的直播数据服务
 * <p>
 * <b>本场数据仍走 {@link DefaultLiveDataService}</b>——它已在生产稳定运行，
 * 数据量小且随开播清零，没有换掉的理由。Redis 只承担**跨场次累计**：
 * 那部分随时间无限增长，JSON 文件迟早撑不住。
 * <p>
 * 仅在配置了 {@code spring.data.redis.host} 时注册。未配置时累计类查询会明确
 * 回复「需配置 Redis」，而不是静默返回 0——后者会让人以为是数据丢了。
 * <p>
 * 键设计：
 * <ul>
 *     <li>{@code nb:total:<platform>:<uid>} — 哈希，字段为指标名，值为累计量</li>
 *     <li>{@code nb:total:user:<platform>:<uid>:<metric>} — 有序集合，成员为用户 UID，分值为累计得分</li>
 *     <li>{@code nb:name:<platform>:<uid>} — 哈希，字段为用户 UID，值为昵称</li>
 *     <li>{@code nb:face:<platform>:<uid>} — 哈希，字段为用户 UID，值为头像地址</li>
 *     <li>{@code nb:face:<platform>:<uid>} — 哈希，字段为用户 UID，值为头像地址</li>
 * </ul>
 */
@Slf4j
@Primary
@Service
@ConditionalOnProperty(prefix = "spring.data.redis", name = "host")
public class RedisLiveDataService implements LiveDataService {
    /**
     * 键前缀。与其他共用同一实例的程序区分开
     */
    private static final String PREFIX = "nb:";

    /**
     * 本场数据委托给 JSON 实现
     */
    private final DefaultLiveDataService delegate;

    private final StringRedisTemplate redis;

    @Autowired
    public RedisLiveDataService(DefaultLiveDataService delegate, StringRedisTemplate redis) {
        this.delegate = delegate;
        this.redis = redis;
        log.info("累计数据存储已启用 (Redis)，「总数据」类查询可用");
    }

    // ================ 累计数据 ================

    @Override
    public boolean supportsTotalData() {
        return true;
    }

    /**
     * 把本场数据并入累计
     * <p>
     * 逐项累加而非整体覆盖：同一主播的累计量由历次直播叠加而成。
     */
    @Override
    public void mergeLiveDataIntoTotal(@NonNull String platform, @NonNull Long uid) {
        try {
            for (Map.Entry<String, Double> entry : delegate.liveMetrics(platform, uid).entrySet()) {
                redis.opsForHash().increment(totalKey(platform, uid), entry.getKey(), entry.getValue());
            }

            for (Map.Entry<String, Map<Long, Double>> byMetric : delegate.liveUserMetrics(platform, uid).entrySet()) {
                String key = totalUserKey(platform, uid, byMetric.getKey());
                for (Map.Entry<Long, Double> entry : byMetric.getValue().entrySet()) {
                    redis.opsForZSet().incrementScore(key, String.valueOf(entry.getKey()), entry.getValue());
                }
            }

            Map<Long, String> names = delegate.liveUserNames(platform, uid);
            if (!names.isEmpty()) {
                Map<String, String> byUid = new java.util.HashMap<>();
                names.forEach((userUid, name) -> byUid.put(String.valueOf(userUid), name));
                redis.opsForHash().putAll(nameKey(platform, uid), byUid);
            }

            Map<Long, String> faces = delegate.liveUserFaces(platform, uid);
            if (!faces.isEmpty()) {
                Map<String, String> byUid = new java.util.HashMap<>();
                faces.forEach((userUid, face) -> byUid.put(String.valueOf(userUid), face));
                redis.opsForHash().putAll(faceKey(platform, uid), byUid);
            }

            log.info("主播 {} 的本场数据已并入累计", uid);
        } catch (Exception e) {
            // 并入失败只影响累计统计，不该波及下播推送本身
            log.error("把主播 {} 的本场数据并入累计时异常", uid, e);
        }
    }

    @Override
    public double getTotalMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric) {
        try {
            Object value = redis.opsForHash().get(totalKey(platform, uid), metric);
            return value == null ? 0 : Double.parseDouble(String.valueOf(value));
        } catch (Exception e) {
            log.error("读取主播 {} 的累计指标 {} 异常", uid, metric, e);
            return 0;
        }
    }

    @Override
    public double getTotalUserMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric,
                                     @NonNull Long userUid) {
        try {
            Double score = redis.opsForZSet().score(totalUserKey(platform, uid, metric), String.valueOf(userUid));
            return score == null ? 0 : score;
        } catch (Exception e) {
            log.error("读取用户 {} 在主播 {} 的累计得分异常", userUid, uid, e);
            return 0;
        }
    }

    @Override
    public List<UserScore> getTotalUserRanking(@NonNull String platform, @NonNull Long uid,
                                               @NonNull String metric, int limit) {
        if (limit <= 0) {
            return List.of();
        }

        try {
            // zset 自带排序，取前 N 名是 O(log n + N)，不必像 JSON 那样全量取出再排
            Set<ZSetOperations.TypedTuple<String>> top =
                    redis.opsForZSet().reverseRangeWithScores(totalUserKey(platform, uid, metric), 0, limit - 1);
            if (top == null || top.isEmpty()) {
                return List.of();
            }

            List<UserScore> result = new ArrayList<>(top.size());
            for (ZSetOperations.TypedTuple<String> tuple : top) {
                String member = tuple.getValue();
                if (member == null) {
                    continue;
                }
                try {
                    Long userUid = Long.parseLong(member);
                    Object name = redis.opsForHash().get(nameKey(platform, uid), member);
                    Object face = redis.opsForHash().get(faceKey(platform, uid), member);
                    result.add(new UserScore(userUid, name == null ? null : String.valueOf(name),
                            face == null ? null : FaceUrlCodec.expand(String.valueOf(face)),
                            Optional.ofNullable(tuple.getScore()).orElse(0.0)));
                } catch (NumberFormatException ignored) {
                    // 手工写入等情况下可能混入非法成员，跳过即可
                }
            }
            return result;
        } catch (Exception e) {
            log.error("读取主播 {} 的累计排行榜 {} 异常", uid, metric, e);
            return List.of();
        }
    }

    @Override
    public int getTotalUserRank(@NonNull String platform, @NonNull Long uid, @NonNull String metric,
                                @NonNull Long userUid) {
        try {
            Long rank = redis.opsForZSet().reverseRank(totalUserKey(platform, uid, metric), String.valueOf(userUid));
            // zset 的名次从 0 开始，对外统一成从 1 开始；成员不存在时返回 null
            return rank == null ? 0 : rank.intValue() + 1;
        } catch (Exception e) {
            log.error("读取用户 {} 在主播 {} 的累计名次异常", userUid, uid, e);
            return 0;
        }
    }

    @Override
    public int getTotalMetricUserCount(@NonNull String platform, @NonNull Long uid, @NonNull String metric) {
        try {
            Long size = redis.opsForZSet().size(totalUserKey(platform, uid, metric));
            return size == null ? 0 : size.intValue();
        } catch (Exception e) {
            log.error("读取主播 {} 的累计参与人数 {} 异常", uid, metric, e);
            return 0;
        }
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
    public void incrementLiveSeries(@NonNull String platform, @NonNull Long uid, @NonNull String metric,
                                    long timestamp, double delta) {
        delegate.incrementLiveSeries(platform, uid, metric, timestamp, delta);
    }

    @Override
    public Map<Long, Double> getLiveSeries(@NonNull String platform, @NonNull Long uid, @NonNull String metric) {
        return delegate.getLiveSeries(platform, uid, metric);
    }

    @Override
    public void incrementLiveWordFrequency(@NonNull String platform, @NonNull Long uid, @NonNull String word) {
        delegate.incrementLiveWordFrequency(platform, uid, word);
    }

    @Override
    public Map<String, Integer> getLiveWordFrequencies(@NonNull String platform, @NonNull Long uid) {
        return delegate.getLiveWordFrequencies(platform, uid);
    }

    private String totalKey(String platform, Long uid) {
        return PREFIX + "total:" + platform + ":" + uid;
    }

    private String totalUserKey(String platform, Long uid, String metric) {
        return PREFIX + "total:user:" + platform + ":" + uid + ":" + metric;
    }

    private String nameKey(String platform, Long uid) {
        return PREFIX + "name:" + platform + ":" + uid;
    }

    private String faceKey(String platform, Long uid) {
        return PREFIX + "face:" + platform + ":" + uid;
    }
}
