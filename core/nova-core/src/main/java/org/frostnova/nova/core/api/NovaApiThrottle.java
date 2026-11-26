package org.frostnova.nova.core.api;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 无效令牌的按来源限速
 *
 * <h2>只数无效的那些</h2>
 * 出示了令牌、却没通过校验（不存在、已吊销、已过期、账号已停用或删除、成员此刻登不进控制台）的请求才记一次；
 * 一个来源在一个时间窗里超过 {@link #maxFailures} 次，之后的<b>无效</b>请求回 429，窗口过了自动恢复。
 * <p>
 * <b>出示有效令牌的请求从不看这里</b>：放在反向代理后面时，所有请求都可能来自同一个地址，
 * 一个写错令牌的脚本不该把同一台机器上其它正常的程序一起挡在外面。
 * <p>
 * 也因此这道限速<b>省不下处理</b>：一个请求有效还是无效，要先算摘要、把令牌表比一遍才知道，
 * 被限的来源每个请求照样走完这一步，只是回 429 而不是 401。它管的是两件事：
 * 写错令牌狂刷的脚本不会每次都占一行日志（一个窗口只记第一次与开始被限的那一次）；
 * 调用方收到 429 与 {@code Retry-After}，知道该停下来查自己的令牌。
 * 令牌是 32 字节随机数，逐个猜不出来，这里不是防猜中的那道。
 *
 * <h2>什么算一个来源</h2>
 * IPv4 按单个地址。IPv6 按 /64 归成一个：一台机器、一户宽带通常分到整个 /64，
 * 在里面换地址是零成本的，按单个地址数等于不数。IPv4 映射写法（{@code ::ffff:192.0.2.1}）按它的 IPv4 地址算。
 *
 * <h2>计数表有上限</h2>
 * 最多记 {@value #MAX_SOURCES} 个来源。满了之后来了新来源，先删已经过了窗口的，仍不够就从窗口开得最早的删起，
 * 但<b>正在被限的来源一个也不删</b>：删掉它就等于提前放它出来，刷一批假来源就能给自己解限。
 * 表里全是正在被限的来源时，新来源记不下，它这一次无效直接回 429——这时一定有人在大批量地刷，
 * 出示有效令牌的请求照常不受影响。
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

    /** 记着的来源数上限 */
    static final int MAX_SOURCES = 10_000;

    /** 满了之后一次腾到这么多以下，免得之后每来一个新来源都要整表扫一遍 */
    private static final int PRUNE_TO = MAX_SOURCES * 9 / 10;

    /** 只含十六进制数字、冒号与点的才拿去按 IPv6 字面量解析：不让主机名走到 DNS 那一步 */
    private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9A-Fa-f:.]+");

    private final int maxFailures;

    private final Duration window;

    private final Clock clock;

    private final Map<String, Window> sources = new HashMap<>();

    /**
     * 表里全是正在被限的来源时，到这一刻之前都腾不出地方（最早的那个窗口到这一刻才过），不必每次都扫一遍
     */
    private Instant saturatedUntil = Instant.MIN;

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
    public synchronized Verdict failure(String source) {
        Instant now = clock.instant();
        String key = key(source);
        Window current = sources.get(key);
        if (current == null && sources.size() >= MAX_SOURCES && (now.isBefore(saturatedUntil) || !makeRoom(now))) {
            // 表里全是正在被限的来源：新来的记不下，按被限处理，不放它无限次地刷
            return new Verdict(true, Math.max(1, window.toSeconds()), false, false);
        }
        Window updated = current == null || expired(current, now)
                ? new Window(now, 1)
                : new Window(current.start(), current.failures() + 1);
        sources.put(key, updated);
        boolean limited = updated.failures() > maxFailures;
        long wait = Math.max(1, Duration.between(now, updated.start().plus(window)).toSeconds());
        return new Verdict(limited, limited ? wait : 0, updated.failures() == 1,
                updated.failures() == maxFailures + 1);
    }

    /**
     * 腾地方：先删过了窗口的，再从窗口开得最早的删起，正在被限的不删
     * @return 腾出了至少一个位置时为 true
     */
    private boolean makeRoom(Instant now) {
        sources.values().removeIf(entry -> expired(entry, now));
        if (sources.size() < MAX_SOURCES) {
            return true;
        }
        List<Map.Entry<String, Window>> evictable = new ArrayList<>();
        for (Map.Entry<String, Window> entry : sources.entrySet()) {
            if (entry.getValue().failures() <= maxFailures) {
                evictable.add(entry);
            }
        }
        evictable.sort(Comparator.comparing(entry -> entry.getValue().start()));
        int excess = sources.size() - PRUNE_TO;
        List<String> victims = new ArrayList<>();
        for (int i = 0; i < evictable.size() && i < excess; i++) {
            victims.add(evictable.get(i).getKey());
        }
        victims.forEach(sources::remove);
        if (sources.size() < MAX_SOURCES) {
            return true;
        }
        // 一个也删不了：全在被限。最早的那个窗口过了之前，这个局面不会变
        saturatedUntil = sources.values().stream().map(entry -> entry.start().plus(window))
                .min(Comparator.naturalOrder()).orElse(now);
        return false;
    }

    private boolean expired(Window entry, Instant now) {
        return !now.isBefore(entry.start().plus(window));
    }

    /**
     * 来源地址归到哪一个计数
     * <p>
     * IPv6 取前 64 位；IPv4 映射写法取它的 IPv4 地址；认不出的原样。只解析字面量，不查 DNS。
     * @param source 来源地址，可为 null
     * @return 计数用的键
     */
    static String key(String source) {
        if (source == null) {
            return "";
        }
        String literal = source.strip();
        int zone = literal.indexOf('%');
        if (zone >= 0) {
            literal = literal.substring(0, zone);
        }
        if (literal.length() > 1 && literal.charAt(0) == '[' && literal.charAt(literal.length() - 1) == ']') {
            literal = literal.substring(1, literal.length() - 1);
        }
        if (literal.indexOf(':') < 0 || !IPV6_LITERAL.matcher(literal).matches()) {
            return literal;
        }
        try {
            InetAddress address = InetAddress.getByName(literal);
            if (address instanceof Inet6Address) {
                return HexFormat.of().formatHex(address.getAddress(), 0, 8) + "::/64";
            }
            // ::ffff:a.b.c.d 会被解析成 IPv4 地址
            return address.getHostAddress();
        } catch (UnknownHostException malformed) {
            return literal;
        }
    }

    /** 此刻记着的来源数，测试用 */
    synchronized int size() {
        return sources.size();
    }
}
