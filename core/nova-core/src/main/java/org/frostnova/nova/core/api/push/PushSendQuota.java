package org.frostnova.nova.core.api.push;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * 推送接口按令牌的限量：任何一分钟里，同一把令牌最多发 {@value #PER_MINUTE} 条
 * <p>
 * 与对外的旧推送接口同一个数。按令牌各算各的，一把令牌被拿去刷屏，别的令牌照常能发。
 * 只放内存，重启清零：这道限量防的是失控的脚本，不是记账。
 * <p>
 * 记的是<b>放行了的</b>那些请求的时刻，按滑动的一分钟数：被拒的请求不进表，
 * 一直刷的脚本不会把自己的等待时间越刷越长，{@code Retry-After} 说的就是最早那一条满一分钟的时刻。
 */
final class PushSendQuota {
    /** 一分钟里一把令牌最多放行的条数 */
    static final int PER_MINUTE = 600;

    static final Duration WINDOW = Duration.ofMinutes(1);

    /** 记着的令牌超过这个数时，顺手清掉一分钟里没再发过的 */
    private static final int PRUNE_ABOVE = 256;

    private final int limit;

    private final Duration window;

    private final Clock clock;

    private final Map<String, Usage> tokens = new HashMap<>();

    private static final class Usage {
        private final Deque<Instant> accepted = new ArrayDeque<>();

        /** 这一轮被限之后已经报过一次 */
        private boolean reported;
    }

    /**
     * 这一次放不放行
     * @param allowed           放行
     * @param retryAfterSeconds 不放行时要等的秒数
     * @param firstRefusal      这是这一轮被限里的第一次拒绝：只在这一次往时间线与日志里记
     */
    record Verdict(boolean allowed, long retryAfterSeconds, boolean firstRefusal) {
    }

    PushSendQuota() {
        this(PER_MINUTE, WINDOW, Clock.systemUTC());
    }

    PushSendQuota(int limit, Duration window, Clock clock) {
        this.limit = limit;
        this.window = window;
        this.clock = clock;
    }

    /**
     * 记一次发送请求
     * @param tokenId 令牌编号（不是令牌本身）
     * @return 放不放行
     */
    synchronized Verdict take(String tokenId) {
        Instant now = clock.instant();
        Usage usage = tokens.computeIfAbsent(tokenId, key -> new Usage());
        expire(usage, now);
        if (usage.accepted.size() < limit) {
            usage.accepted.addLast(now);
            usage.reported = false;
            if (tokens.size() > PRUNE_ABOVE) {
                prune(now);
            }
            return new Verdict(true, 0, false);
        }
        long millis = Duration.between(now, usage.accepted.peekFirst().plus(window)).toMillis();
        boolean first = !usage.reported;
        usage.reported = true;
        return new Verdict(false, Math.max(1, (millis + 999) / 1000), first);
    }

    private void expire(Usage usage, Instant now) {
        Instant cutoff = now.minus(window);
        while (!usage.accepted.isEmpty() && !usage.accepted.peekFirst().isAfter(cutoff)) {
            usage.accepted.pollFirst();
        }
    }

    private void prune(Instant now) {
        tokens.values().removeIf(usage -> {
            expire(usage, now);
            return usage.accepted.isEmpty();
        });
    }
}
