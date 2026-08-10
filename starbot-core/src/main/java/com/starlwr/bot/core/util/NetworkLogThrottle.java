package com.starlwr.bot.core.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 网络日志的同类去重抑制
 * <p>
 * 打开 {@code network-log} 之后，每个请求都会写一行。而这个程序的请求绝大多数是轮询：
 * 三个房间、十秒一轮，一小时就是上千行几乎一样的记录，**要查的那一条异常正好淹在里面**。
 * 排障日志的用处取决于它读不读得下去。
 *
 * <h2>「同类」怎么定义</h2>
 *
 * 按 <b>请求方法 + 去掉查询串的地址</b> 归类。查询串里装的正是房间号、uid、时间戳这些
 * 每次都不同的东西——带上它就没有两条请求是同类的，抑制也就永远不生效。
 * <p>
 * 代价是「同一个接口、不同房间」会被算成同类。这是有意的：查一个接口是否正常时，
 * 关心的是它整体的成败与耗时，而不是逐个房间各来一行。
 *
 * <h2>抑制掉的不会消失</h2>
 *
 * 被抑制的次数攒着，等窗口过去后随下一条日志一起报出来
 * （「期间同类 N 次已抑制」）。<b>只字不提地丢掉才是真的丢信息</b>，
 * 那会让人以为这段时间没有请求。
 * <p>
 * <b>失败一律放行，不参与抑制</b>：抑制的目的是让异常显出来，
 * 把异常也抑制掉就本末倒置了。
 */
public final class NetworkLogThrottle {
    /**
     * 各类请求的抑制状态
     */
    private final Map<String, State> states = new ConcurrentHashMap<>();

    /**
     * 判断这一条是否应当写日志，并把抑制掉的条数带回来
     * <p>
     * @param method 请求方法
     * @param url 请求地址，查询串会被去掉后参与归类
     * @param windowSeconds 抑制窗口，单位：秒；非正数表示不抑制
     * @param nowMillis 当前时刻
     * @return 决定：是否放行、以及放行时应当附带的「已抑制条数」
     */
    public Decision decide(String method, String url, int windowSeconds, long nowMillis) {
        if (windowSeconds <= 0) {
            return new Decision(true, 0);
        }

        State state = states.computeIfAbsent(method + " " + strip(url), key -> new State());
        return state.decide(windowSeconds * 1000L, nowMillis);
    }

    /**
     * 去掉查询串与片段
     */
    private static String strip(String url) {
        if (url == null) {
            return "";
        }
        int cut = url.length();
        for (char separator : new char[]{'?', '#'}) {
            int index = url.indexOf(separator);
            if (index >= 0 && index < cut) {
                cut = index;
            }
        }
        return url.substring(0, cut);
    }

    /**
     * 一类请求的抑制状态
     */
    private static final class State {
        /**
         * 「还没放行过任何一条」的哨兵
         * <p>
         * <b>不能拿 {@code Long.MIN_VALUE} 当哨兵再去做减法</b>：
         * {@code nowMillis - Long.MIN_VALUE} 会溢出成负数，于是「早该放行」被算成
         * 「还在窗口内」，**第一条日志就被抑制掉**。改成显式判哨兵，不参与减法。
         */
        private static final long NEVER = Long.MIN_VALUE;

        private final AtomicInteger suppressed = new AtomicInteger();

        private long lastLoggedAt = NEVER;

        private synchronized Decision decide(long windowMillis, long nowMillis) {
            if (lastLoggedAt == NEVER || nowMillis - lastLoggedAt >= windowMillis) {
                lastLoggedAt = nowMillis;
                return new Decision(true, suppressed.getAndSet(0));
            }
            suppressed.incrementAndGet();
            return new Decision(false, 0);
        }
    }

    /**
     * 抑制决定
     * @param log 是否应当写这一条
     * @param suppressedCount 上一次放行之后被抑制掉的同类条数，放行时才有意义
     */
    public record Decision(boolean log, int suppressedCount) {
        /**
         * 附在日志末尾的说明，没有被抑制的条数时为空串
         */
        public String suffix() {
            return suppressedCount > 0 ? "（期间同类 " + suppressedCount + " 次已抑制）" : "";
        }
    }
}
