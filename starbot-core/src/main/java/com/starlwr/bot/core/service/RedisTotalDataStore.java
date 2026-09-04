package com.starlwr.bot.core.service;

import com.starlwr.bot.core.model.UserScore;
import com.starlwr.bot.core.util.FaceUrlCodec;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.Lifecycle;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 存在 Redis 里的那份跨场次累计数据
 * <p>
 * <b>只管累计。</b>本场数据仍在 {@link DefaultLiveDataService} 那边——它已在生产稳定运行，
 * 数据量小且随开播清零，没有换掉的理由。Redis 只承担跨场次累计：那部分随时间无限增长，
 * JSON 文件迟早撑不住。两边怎么合成一个服务，见 {@link CompositeLiveDataService}。
 * <p>
 * 键设计（<b>与 5.0 相同，换存储实现不换键</b>，否则老数据在新版本里等于不存在）：
 * <ul>
 *     <li>{@code nb:total:<platform>:<uid>} — 哈希，字段为指标名，值为累计量</li>
 *     <li>{@code nb:total:user:<platform>:<uid>:<metric>} — 有序集合，成员为用户 UID，分值为累计得分</li>
 *     <li>{@code nb:name:<platform>:<uid>} — 哈希，字段为用户 UID，值为昵称</li>
 *     <li>{@code nb:face:<platform>:<uid>} — 哈希，字段为用户 UID，值为头像地址</li>
 * </ul>
 * <p>
 * <b>连接工厂由外面给、也由本类负责关。</b>地址是运行期可改的（见 {@link TotalDataStorage}），
 * 改一次就换一个工厂，旧的那个必须有人关掉——不关的话每改一次地址就漏一组连接与线程，
 * 而这件事在界面上、日志里都看不出来，只有跑上几个月之后表现为「越来越慢」。
 */
@Slf4j
public class RedisTotalDataStore {
    /**
     * 键前缀。与其他共用同一实例的程序区分开
     */
    private static final String PREFIX = "nb:";

    /**
     * 一场直播并入累计时要带过去的四份东西
     * <p>
     * 由调用方从本场数据里取好再传进来，本类<b>不认识本场数据存在哪</b>：
     * 直接把 {@link DefaultLiveDataService} 传进来的话，累计存储就反过来依赖了本场存储的实现，
     * 而这两者本来是可以各换各的。
     *
     * @param metrics 指标名 → 累计量
     * @param userMetrics 指标名 →（用户 UID → 得分）
     * @param userNames 用户 UID → 昵称
     * @param userFaces 用户 UID → 头像地址
     */
    public record LiveSnapshot(Map<String, Double> metrics,
                               Map<String, Map<Long, Double>> userMetrics,
                               Map<Long, String> userNames,
                               Map<Long, String> userFaces) {
    }

    private final RedisConnectionFactory factory;

    private final StringRedisTemplate redis;

    /**
     * @param factory 连接工厂，本类接手它的生命周期
     */
    public RedisTotalDataStore(@NonNull RedisConnectionFactory factory) {
        this.factory = factory;
        start(factory);
        this.redis = new StringRedisTemplate(factory);
    }

    /**
     * 现在还连得上吗
     * <p>
     * <b>只问一句 PING，不碰任何键。</b>判定「累计数据此刻可不可用」这件事每次开菜单、
     * 每次刷首页都要问一遍，读一个真实的键既慢又会在库里留下访问痕迹。
     * <p>
     * 任何异常都读成「连不上」而不是往上抛：调用方要的是一个能拿去画界面的判定，
     * 而不是一个会把首页整页打挂的异常。
     * @return 连得上为 true
     */
    public boolean reachable() {
        RedisConnection connection = null;
        try {
            connection = factory.getConnection();
            connection.ping();
            return true;
        } catch (Exception e) {
            log.debug("累计数据存储探活失败: {}", e.toString());
            return false;
        } finally {
            close(connection);
        }
    }

    /**
     * 关掉这份存储
     * <p>
     * 幂等：关过之后再关一次不报错，因为「换地址」与「进程退出」两条路都会走到这里。
     */
    public void shutdown() {
        try {
            if (factory instanceof Lifecycle lifecycle) {
                lifecycle.stop();
            }
            if (factory instanceof DisposableBean disposable) {
                disposable.destroy();
            }
        } catch (Exception e) {
            log.warn("关闭累计数据存储的连接时异常: {}", e.toString());
        }
    }

    // ================ 累计数据 ================

    /**
     * 把本场数据并入累计
     * <p>
     * 逐项累加而非整体覆盖：同一主播的累计量由历次直播叠加而成。
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param snapshot 本场数据快照
     */
    public void merge(@NonNull String platform, @NonNull Long uid, @NonNull LiveSnapshot snapshot) {
        try {
            for (Map.Entry<String, Double> entry : snapshot.metrics().entrySet()) {
                redis.opsForHash().increment(totalKey(platform, uid), entry.getKey(), entry.getValue());
            }

            for (Map.Entry<String, Map<Long, Double>> byMetric : snapshot.userMetrics().entrySet()) {
                String key = totalUserKey(platform, uid, byMetric.getKey());
                for (Map.Entry<Long, Double> entry : byMetric.getValue().entrySet()) {
                    redis.opsForZSet().incrementScore(key, String.valueOf(entry.getKey()), entry.getValue());
                }
            }

            Map<Long, String> names = snapshot.userNames();
            if (!names.isEmpty()) {
                Map<String, String> byUid = new HashMap<>();
                names.forEach((userUid, name) -> byUid.put(String.valueOf(userUid), name));
                redis.opsForHash().putAll(nameKey(platform, uid), byUid);
            }

            Map<Long, String> faces = snapshot.userFaces();
            if (!faces.isEmpty()) {
                Map<String, String> byUid = new HashMap<>();
                faces.forEach((userUid, face) -> byUid.put(String.valueOf(userUid), face));
                redis.opsForHash().putAll(faceKey(platform, uid), byUid);
            }

            log.info("主播 {} 的本场数据已并入累计", uid);
        } catch (Exception e) {
            // 并入失败只影响累计统计，不该波及下播推送本身
            log.error("把主播 {} 的本场数据并入累计时异常", uid, e);
        }
    }

    /**
     * 获取累计的统计指标
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param metric 指标名
     * @return 指标值，未记录时为 0
     */
    public double getTotalMetric(@NonNull String platform, @NonNull Long uid, @NonNull String metric) {
        try {
            Object value = redis.opsForHash().get(totalKey(platform, uid), metric);
            return value == null ? 0 : Double.parseDouble(String.valueOf(value));
        } catch (Exception e) {
            log.error("读取主播 {} 的累计指标 {} 异常", uid, metric, e);
            return 0;
        }
    }

    /**
     * 获取某个用户的累计得分
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param metric 指标名
     * @param userUid 用户 UID
     * @return 得分，未记录时为 0
     */
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

    /**
     * 获取累计的用户排行
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param metric 指标名
     * @param limit 取前多少名
     * @return 按得分降序排列的用户
     */
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

    /**
     * 获取某个用户在累计排行中的名次
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param metric 指标名
     * @param userUid 用户 UID
     * @return 名次，从 1 开始；未上榜时为 0
     */
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

    /**
     * 获取累计参与某项互动的独立用户数
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param metric 指标名
     * @return 独立用户数，未记录时为 0
     */
    public int getTotalMetricUserCount(@NonNull String platform, @NonNull Long uid, @NonNull String metric) {
        try {
            Long size = redis.opsForZSet().size(totalUserKey(platform, uid, metric));
            return size == null ? 0 : size.intValue();
        } catch (Exception e) {
            log.error("读取主播 {} 的累计参与人数 {} 异常", uid, metric, e);
            return 0;
        }
    }

    /**
     * 把连接工厂带起来
     * <p>
     * 按接口而不是按具体类型走这两步：真实的 Lettuce 工厂两个接口都实现，
     * 而判据里那个假工厂一个都不实现——于是同一段代码在两边都跑得动，
     * 不必为「能不能被判据看见」把生产路径改成另一条。
     */
    private static void start(RedisConnectionFactory factory) {
        try {
            if (factory instanceof InitializingBean bean) {
                bean.afterPropertiesSet();
            }
            if (factory instanceof Lifecycle lifecycle) {
                lifecycle.start();
            }
        } catch (Exception e) {
            throw new IllegalStateException("累计数据存储的连接工厂启动失败", e);
        }
    }

    private static void close(RedisConnection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (Exception ignored) {
            // 探活用完就还回去，还不回去也不影响这一次的结论
        }
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
