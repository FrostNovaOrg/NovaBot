package org.frostnova.nova.core.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 无效令牌的按来源限速
 *
 * <h2>只数无效的那些</h2>
 * 出示了令牌、却没通过校验（不存在、已吊销、已过期、账号已停用）的请求才记一次；
 * 一个来源在一个时间窗里超过 {@link #maxFailures} 次，之后的<b>无效</b>请求回 429，窗口过了自动恢复。
 * <p>
 * <b>出示有效令牌的请求从不看这里</b>：放在反向代理后面时，所有请求都可能来自同一个地址，
 * 一个写错令牌的脚本不该把同一台机器上其它正常的程序一起挡在外面。
 * 令牌是 32 字节随机数，逐个猜不出来；这道限速防的是刷日志、白耗处理，不是猜中。
 *
 * <h2>与控制台登录的失败锁定分开</h2>
 * 这里另起一份计数，不经手 {@code LoginThrottle}：拿着令牌的程序写错了，
 * 不该连累人在浏览器里登录控制台；反过来也一样。
 */
public class NovaApiThrottle {
    /** 一个窗口内容忍的无效次数 */
    static final int DEFAULT_MAX_FAILURES = 10;

    /** 窗口长度 */
    static final Duration DEFAULT_WINDOW = Duration.ofMinutes(1);

    /** 记着的来源数上限，超了先清掉过期的，仍超就整表重来 */
    private static final int MAX_SOURCES = 10_000;

    private final int maxFailures;

    private final Duration window;

    private final Clock clock;

    private final Map<String, Window> sources = new ConcurrentHashMap<>();

    private record Window(Instant start, int failures) {
    }

    /**
     * 记一次无效之后的结论
     * @param limited           这一次要回 429
     * @param retryAfterSeconds 回 429 时要等多久
     * @param first             这是本窗口里的第一次无效
     * @param justLimited       这是本窗口里第一次被限
     */
    public record Verdict(boolean limited, long retryAfterSeconds, boolean first, boolean justLimited) {
    }

    public NovaApiThrottle() {
        this(DEFAULT_MAX_FAILURES, DEFAULT_WINDOW, Clock.systemUTC());
    }

    public NovaApiThrottle(int maxFailures, Duration window, Clock clock) {
        this.maxFailures = maxFailures;
        this.window = window;
        this.clock = clock;
    }

    /**
     * 记下这个来源的一次无效尝试
     * @param source 来源地址
     * @return 这一次该回 401 还是 429
     */
    public Verdict failure(String source) {
        Instant now = clock.instant();
        if (sources.size() > MAX_SOURCES) {
            sources.values().removeIf(w -> !now.isBefore(w.start().plus(window)));
            if (sources.size() > MAX_SOURCES) {
                sources.clear();
            }
        }
        Window updated = sources.compute(source == null ? "" : source, (key, current) ->
                current == null || !now.isBefore(current.start().plus(window))
                        ? new Window(now, 1)
                        : new Window(current.start(), current.failures() + 1));
        boolean limited = updated.failures() > maxFailures;
        long wait = Math.max(1, Duration.between(now, updated.start().plus(window)).toSeconds());
        return new Verdict(limited, limited ? wait : 0, updated.failures() == 1,
                updated.failures() == maxFailures + 1);
    }
}
