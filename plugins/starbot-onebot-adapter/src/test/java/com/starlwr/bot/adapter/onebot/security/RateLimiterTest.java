package com.starlwr.bot.adapter.onebot.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("令牌桶限流器")
class RateLimiterTest {
    @Test
    @DisplayName("放行量不超过桶容量")
    void burstIsCapped() {
        RateLimiter limiter = new RateLimiter(60, 10);
        long now = 0L;

        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire("k", now), "第 " + (i + 1) + " 次请求应放行");
        }

        assertFalse(limiter.tryAcquire("k", now), "超出桶容量后应拒绝");
    }

    @Test
    @DisplayName("随时间推移按速率补充令牌")
    void refillsOverTime() {
        // 60 次/分钟 = 1 次/秒
        RateLimiter limiter = new RateLimiter(60, 5);
        long now = 0L;

        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("k", now));
        }
        assertFalse(limiter.tryAcquire("k", now));

        // 推进 1 秒，应恰好补充 1 个令牌
        now += TimeUnit.SECONDS.toNanos(1);
        assertTrue(limiter.tryAcquire("k", now));
        assertFalse(limiter.tryAcquire("k", now));
    }

    @Test
    @DisplayName("补充令牌不会超过桶容量")
    void refillDoesNotExceedBurst() {
        RateLimiter limiter = new RateLimiter(60, 3);
        long now = 0L;

        // 静置很久后, 桶最多只能被填满到容量上限
        now += TimeUnit.HOURS.toNanos(1);

        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire("k", now));
        }
        assertFalse(limiter.tryAcquire("k", now));
    }

    @Test
    @DisplayName("不同限流键互不影响")
    void keysAreIndependent() {
        RateLimiter limiter = new RateLimiter(60, 1);
        long now = 0L;

        assertTrue(limiter.tryAcquire("a", now));
        assertFalse(limiter.tryAcquire("a", now));
        assertTrue(limiter.tryAcquire("b", now));
    }

    @Test
    @DisplayName("空闲令牌桶可被回收，避免键无限增长")
    void idleBucketsAreEvicted() {
        RateLimiter limiter = new RateLimiter(60, 5);
        long now = 0L;

        limiter.tryAcquire("a", now);
        limiter.tryAcquire("b", now);
        assertEquals(2, limiter.size());

        limiter.evictIdle(now + TimeUnit.SECONDS.toNanos(1));
        assertEquals(2, limiter.size(), "尚未超过空闲阈值不应回收");

        limiter.evictIdle(now + TimeUnit.HOURS.toNanos(1));
        assertEquals(0, limiter.size());
    }

    @Test
    @DisplayName("并发获取令牌时总放行量不超过桶容量")
    void concurrentAcquireIsCapped() throws Exception {
        int burst = 50;
        int threads = 16;
        int attemptsPerThread = 20;

        RateLimiter limiter = new RateLimiter(60, burst);
        AtomicInteger granted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < attemptsPerThread; j++) {
                            // 固定时间戳, 排除补充令牌的干扰, 只检验并发下的计数正确性
                            if (limiter.tryAcquire("shared", 0L)) {
                                granted.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "并发测试超时");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(burst, granted.get(), "并发下放行量应恰好等于桶容量");
    }

    @Test
    @DisplayName("非法参数被拒绝")
    void invalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(60, 0));
    }
}
