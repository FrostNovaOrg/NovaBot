package com.starlwr.bot.adapter.onebot.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 令牌桶限流器，按调用方维度（推送接口路径 + 客户端 IP）独立计数
 * <p>
 * 采用惰性补充策略，不额外占用调度线程：每次取令牌时按距上次补充的时间差换算应补充的令牌数。
 * 这样即使限流键数量较多，也不会带来常驻的定时任务开销，适合 VPS 等资源受限环境。
 */
public class RateLimiter {
    /**
     * 每秒补充的令牌数
     */
    private final double permitsPerSecond;

    /**
     * 桶容量，决定可容忍的瞬时突发量
     */
    private final double burst;

    /**
     * 空闲桶的回收阈值，单位：纳秒
     */
    private final long idleEvictNanos;

    /**
     * 限流键 -> 令牌桶
     */
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * 构造限流器
     * @param permitsPerMinute 每分钟允许的请求数
     * @param burst 桶容量，即可容忍的瞬时突发请求数
     */
    public RateLimiter(int permitsPerMinute, int burst) {
        if (permitsPerMinute <= 0) {
            throw new IllegalArgumentException("每分钟允许的请求数必须大于 0");
        }
        if (burst <= 0) {
            throw new IllegalArgumentException("桶容量必须大于 0");
        }

        this.permitsPerSecond = permitsPerMinute / 60.0;
        this.burst = burst;
        // 桶被填满后再静置一个填充周期即可安全回收, 避免长期运行下 Map 无限增长
        this.idleEvictNanos = (long) (burst / permitsPerSecond * 2 * 1_000_000_000L);
    }

    /**
     * 尝试获取一个令牌
     * @param key 限流键
     * @return 是否放行
     */
    public boolean tryAcquire(String key) {
        return tryAcquire(key, System.nanoTime());
    }

    /**
     * 尝试获取一个令牌，允许外部传入时间戳，便于测试
     * @param key 限流键
     * @param nanos 当前时间，单位：纳秒
     * @return 是否放行
     */
    boolean tryAcquire(String key, long nanos) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(burst, nanos));

        synchronized (bucket) {
            double elapsedSeconds = (nanos - bucket.lastRefillNanos) / 1_000_000_000.0;
            if (elapsedSeconds > 0) {
                bucket.tokens = Math.min(burst, bucket.tokens + elapsedSeconds * permitsPerSecond);
                bucket.lastRefillNanos = nanos;
            }

            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return true;
            }

            return false;
        }
    }

    /**
     * 清理长期空闲的令牌桶，防止限流键随客户端 IP 增长而无限累积
     * @param nanos 当前时间，单位：纳秒
     */
    public void evictIdle(long nanos) {
        buckets.entrySet().removeIf(entry -> {
            Bucket bucket = entry.getValue();
            synchronized (bucket) {
                return nanos - bucket.lastRefillNanos > idleEvictNanos;
            }
        });
    }

    /**
     * 当前维护的限流键数量
     * @return 限流键数量
     */
    public int size() {
        return buckets.size();
    }

    /**
     * 令牌桶
     */
    private static final class Bucket {
        /**
         * 当前剩余令牌数
         */
        private double tokens;

        /**
         * 上次补充令牌的时间，单位：纳秒
         */
        private long lastRefillNanos;

        private Bucket(double tokens, long lastRefillNanos) {
            this.tokens = tokens;
            this.lastRefillNanos = lastRefillNanos;
        }
    }
}
