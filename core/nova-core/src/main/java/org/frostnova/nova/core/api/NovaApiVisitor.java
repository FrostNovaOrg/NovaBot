package org.frostnova.nova.core.api;

import jakarta.servlet.http.HttpServletRequest;
import org.frostnova.nova.core.web.TrustedProxyForwardedHeaderFilter;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 统一接口面认的「来访者」：这个请求实际是从哪个地址来的
 *
 * <h2>只给 {@code /nova/api/v1/} 用</h2>
 * 控制台、事件流、推送接口各有各的判法，这里一处也不碰：它们照旧只看连接的对端地址
 * （推送接口另有自己的「信任反向代理」开关）。全局的 {@code server.forward-headers-strategy} 也不靠它——
 * 打开那一项会把事件流「只认本机回环」的判据一并改写。
 *
 * <h2>开着 {@code server.forward-headers-strategy=framework} 时</h2>
 * 转发头过滤器（{@link TrustedProxyForwardedHeaderFilter}）排在最前，先改写了对端、删掉了转发头。
 * 它经手过的请求按它记下的到达原样判：直接对端是回环、请求原本不带任何一种转发头，才算本机；
 * 原本带过的不算本机，来访地址取它按可信代理送来的 {@code X-Forwarded-For} 改写出来的那个，
 * 对端是回环而没有这样的地址时当作未知来访者。下面的判法是它没经手过的请求（没开这一项、用 {@code native}，
 * 或请求不带任何转发头）的判法。
 *
 * <h2>判法</h2>
 * <ol>
 *   <li>对端不是本机回环：来访者就是对端，转发头一概不看。外面直连进来的人写什么头都冒充不了本机
 *       （开着 {@code framework} 时这一条照样成立：直连进来的人自带的转发头不会把对端改写成回环，
 *       请求原本带过转发头也就不算本机）。</li>
 *   <li>对端是本机回环、请求里没有转发头（{@code X-Forwarded-For}、{@code X-Real-IP}、{@code Forwarded}
 *       三种都没有）：本机来访。</li>
 *   <li>对端是本机回环、带了转发头：当作经本机上的反向代理进来，来访者取转发头里离 NovaBot 最近的那一段：
 *     <ul>
 *       <li>{@code X-Forwarded-For} 取最右一段（与推送接口同一个取法）：这个头是追加式的，
 *           最左边那几段由客户端说了算，最右一段是本机那层代理写上的；</li>
 *       <li>{@code X-Real-IP} 只能有一行、一个值；</li>
 *       <li>{@code Forwarded} 取最后一项里的 {@code for=}。</li>
 *     </ul>
 *     带了几种就几种都要解析得出、而且<b>指的是同一个地址</b>。有一种解析不出合法地址、
 *     或者几种说的不是同一个，都当作<b>未知来访者</b>：不是本机，也认不出是哪个地址。
 *     <p>
 *     🔴 <b>转发头里只要有一处写得不合规，整个就判未知来访者，绝不跳过那一处、退而采用别的一段。</b>
 *     {@code X-Forwarded-For} 里每一段（不只最右那一段）都得是合法地址，空段（末尾多了逗号、中间连着两个逗号）也算不合规；
 *     {@code Forwarded} 按它自己的写法规矩整段读一遍（分项、分参数、引号），读不干净的、某一项没有 {@code for=}
 *     或同一参数写了两次的、某个 {@code for=} 不是合法地址的，都算不合规；端口只认 1–65535、不带前导零。
 *     宽松地「跳过读不懂的那一处」正是冒充的入口：一个多出来的逗号，就能让前一段——由客户端写的那一段——顶上来。
 *     这一条比推送接口「先看 X-Forwarded-For、没有再看 X-Real-IP」更严：代理只写了其中一种、
 *     另一种由客户端原样带过来时，按先后取的话客户端自己写的那一种就可能被采信。</li>
 * </ol>
 * 只认 IP 字面量（可带端口、IPv6 可带方括号），主机名、{@code unknown}、{@code _hidden} 这类一概不认，
 * 也就不会为了解析一个请求头去查 DNS。
 *
 * <h2>前提</h2>
 * 本机上的反向代理要把这几个头写好（样板见使用说明附录 C）。代理一个头也不写的话，
 * 经它进来的请求在这里与本机直连分不出来，都算本机来访。
 *
 * @param address 限速与日志用的地址；未知来访者为 null
 * @param kind    怎么判出来的
 */
public record NovaApiVisitor(String address, Kind kind) {
    /** 来访者是怎么判出来的 */
    public enum Kind {
        /** 对端不是本机回环，就是对端 */
        DIRECT,
        /** 本机直连：对端是回环，没带转发头 */
        LOCAL,
        /** 经反向代理进来，取自转发头（开着 framework 时取自可信代理送来的 X-Forwarded-For） */
        PROXIED,
        /** 经反向代理进来，但转发头解析不出合法地址、几种头说的不是同一个，或没有能采信的地址 */
        UNKNOWN
    }

    static final String X_FORWARDED_FOR = "X-Forwarded-For";

    static final String X_REAL_IP = "X-Real-IP";

    static final String FORWARDED = "Forwarded";

    /** 未知来访者在限速表里共用的那一格：解析不出地址的请求不该各占一格，也不该与本机同一格 */
    static final String UNKNOWN_KEY = "unknown-forwarded";

    /** 严格的点分十进制 IPv4，四段、每段 0-255，与 IpMatcher 的判法同一条 */
    private static final Pattern IPV4 = Pattern.compile(
            "(25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)){3}");

    /** 只含十六进制数字、冒号与点的才拿去按 IPv6 字面量解析：不让主机名走到 DNS 那一步 */
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]+");

    /** 端口：1–65535，不带前导零（数值另核） */
    private static final Pattern PORT = Pattern.compile(":[1-9]\\d{0,4}");

    /** 一段地址最长多少个字：IPv6 带方括号与端口也到不了这么长，更长的不必解析 */
    private static final int MAX_LITERAL = 64;

    /**
     * 判出这个请求的来访者
     * @param request 请求
     * @return 来访者
     */
    public static NovaApiVisitor of(HttpServletRequest request) {
        TrustedProxyForwardedHeaderFilter.Arrival arrival = TrustedProxyForwardedHeaderFilter.arrival(request);
        if (arrival != null) {
            return behindForwardedHeaderFilter(request, arrival);
        }
        String peer = request.getRemoteAddr();
        if (!loopback(peer)) {
            return new NovaApiVisitor(peer, Kind.DIRECT);
        }
        List<String> forwardedFor = values(request, X_FORWARDED_FOR);
        List<String> realIp = values(request, X_REAL_IP);
        List<String> forwarded = values(request, FORWARDED);
        if (forwardedFor.isEmpty() && realIp.isEmpty() && forwarded.isEmpty()) {
            return new NovaApiVisitor(peer, Kind.LOCAL);
        }
        List<InetAddress> claims = new ArrayList<>();
        if (!forwardedFor.isEmpty()) {
            claims.add(last(forwardedForHops(String.join(",", forwardedFor))));
        }
        if (!realIp.isEmpty()) {
            claims.add(realIp.size() == 1 && realIp.get(0).indexOf(',') < 0 ? literal(realIp.get(0)) : null);
        }
        if (!forwarded.isEmpty()) {
            claims.add(last(forwardedFors(String.join(",", forwarded))));
        }
        InetAddress first = claims.get(0);
        for (InetAddress claim : claims) {
            if (claim == null || !claim.equals(first)) {
                return new NovaApiVisitor(null, Kind.UNKNOWN);
            }
        }
        return new NovaApiVisitor(first.getHostAddress(), Kind.PROXIED);
    }

    /**
     * 开着 {@code server.forward-headers-strategy=framework}、转发头过滤器经手过这个请求时
     * <p>
     * 那一道排在最前，已经改写了对端地址、删掉了转发头，这里看到的不是原样。「本机」按它记下的到达原样判：
     * 直接对端是回环、请求原本不带任何一种转发头，才算本机。原本带过的，转发头写着什么都不算本机；
     * 来访地址只取那一道按可信代理送来的 {@code X-Forwarded-For} 改写出来的那个（与控制台看到的是同一个），
     * {@code Forwarded}、{@code X-Real-IP} 不拿来定地址。
     * 对端是回环、转发头里又没有能采信的地址，或改写出来的不是 IP 字面量，都当作未知来访者。
     */
    private static NovaApiVisitor behindForwardedHeaderFilter(HttpServletRequest request,
                                                              TrustedProxyForwardedHeaderFilter.Arrival arrival) {
        String peer = arrival.peer();
        if (!arrival.forwarded()) {
            return new NovaApiVisitor(peer, loopback(peer) ? Kind.LOCAL : Kind.DIRECT);
        }
        if (arrival.rewritten()) {
            InetAddress address = literal(request.getRemoteAddr());
            return address == null ? new NovaApiVisitor(null, Kind.UNKNOWN)
                    : new NovaApiVisitor(address.getHostAddress(), Kind.PROXIED);
        }
        return loopback(peer) ? new NovaApiVisitor(null, Kind.UNKNOWN) : new NovaApiVisitor(peer, Kind.DIRECT);
    }

    /**
     * 无效令牌限速按哪一格记
     * <p>
     * 能判出地址的按地址（IPv6 再由限速那边归成 /64）；未知来访者共用一格，
     * 不与本机、也不与任何一个真实来访者共用。
     * @return 限速用的来源
     */
    public String throttleKey() {
        return address == null ? UNKNOWN_KEY : address;
    }

    /**
     * 日志里怎么写这位来访者
     * <p>
     * 只写判出来的地址，转发头的原文一个字也不进日志：那是请求方写的。
     * @return 给人看的一段
     */
    public String describe() {
        return switch (kind) {
            case DIRECT, LOCAL -> address;
            case PROXIED -> address + "（经反向代理）";
            case UNKNOWN -> "未知来访者（经反向代理，转发头里认不出来访地址）";
        };
    }

    /**
     * 对端是不是本机回环
     * @param peer 连接的对端地址
     * @return 是 {@code 127.0.0.0/8} 或 {@code ::1}（含 IPv4 映射写法）时为 true
     */
    static boolean loopback(String peer) {
        if (peer == null) {
            return false;
        }
        String value = peer.strip();
        int zone = value.indexOf('%');
        if (zone >= 0) {
            value = value.substring(0, zone);
        }
        InetAddress address = literal(value);
        return address != null && address.isLoopbackAddress();
    }

    private static List<String> values(HttpServletRequest request, String name) {
        Enumeration<String> headers = request.getHeaders(name);
        return headers == null ? List.of() : Collections.list(headers);
    }

    private static InetAddress last(List<InetAddress> hops) {
        return hops == null || hops.isEmpty() ? null : hops.get(hops.size() - 1);
    }

    /**
     * {@code X-Forwarded-For} 的每一段，从左到右
     * <p>
     * 每一段都得是合法地址：有一段是空的（末尾多了逗号、中间连着两个逗号、整个头是空的）或读不出地址，
     * 整个头就不算数。不跳过读不懂的那一段去取前一段——那一段多半就是客户端自己写的。
     * @param joined 各行按逗号接起来的原文
     * @return 各段的地址；有一段不合规时为 null
     */
    static List<InetAddress> forwardedForHops(String joined) {
        List<InetAddress> hops = new ArrayList<>();
        for (String hop : joined.split(",", -1)) {
            InetAddress address = literal(hop);
            if (address == null) {
                return null;
            }
            hops.add(address);
        }
        return hops;
    }

    /** 参数名与不带引号的参数值只能由这些字符组成 */
    private static boolean tchar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
    }

    /** 项与项之间可以有的空白 */
    private static boolean ows(char c) {
        return c == ' ' || c == '\t';
    }

    /**
     * {@code Forwarded} 每一项里 {@code for=} 的地址，从左到右
     * <p>
     * 按这个头自己的写法整段读一遍：项与项之间是逗号（两侧可以有空白），一项里的参数之间是分号（不带空白），
     * 参数是「名=值」，值要么全由允许的字符组成，要么整个包在一对双引号里。
     * 下面这些都算不合规、整个头不算数：
     * <ul>
     *   <li>读不干净：空项（末尾多了逗号、两个逗号连着）、参数名或值是空的、少了等号、引号没收口、
     *       引号后面跟着别的字、多出来的空白；</li>
     *   <li>引号里出现反斜杠、引号、逗号、分号、等号、空白或控制字符——这些在地址里不会出现，
     *       出现了就是想让切分的人切错地方；</li>
     *   <li>同一项里一个参数写了两次；某一项没有 {@code for=}；</li>
     *   <li>任何一个 {@code for=} 读不出合法地址（{@code unknown}、{@code _hidden} 这类也算）。</li>
     * </ul>
     * 不带引号的值里不会有冒号与方括号，所以带端口的、IPv6 的地址必须带引号，否则读不出地址。
     * @param joined 各行按逗号接起来的原文
     * @return 各项的地址；有一处不合规时为 null
     */
    static List<InetAddress> forwardedFors(String joined) {
        List<InetAddress> result = new ArrayList<>();
        int n = joined.length();
        int i = 0;
        while (true) {
            while (i < n && ows(joined.charAt(i))) {
                i++;
            }
            java.util.Set<String> names = new java.util.HashSet<>();
            String found = null;
            while (true) {
                int nameStart = i;
                while (i < n && tchar(joined.charAt(i))) {
                    i++;
                }
                if (i == nameStart || i >= n || joined.charAt(i) != '=') {
                    return null;
                }
                String name = joined.substring(nameStart, i).toLowerCase(Locale.ROOT);
                i++;
                String value;
                if (i < n && joined.charAt(i) == '"') {
                    int close = i + 1;
                    while (close < n && joined.charAt(close) != '"') {
                        char c = joined.charAt(close);
                        if (c <= ' ' || c >= 0x7f || "\\,;=".indexOf(c) >= 0) {
                            return null;
                        }
                        close++;
                    }
                    if (close >= n) {
                        return null;
                    }
                    value = joined.substring(i + 1, close);
                    i = close + 1;
                } else {
                    int valueStart = i;
                    while (i < n && tchar(joined.charAt(i))) {
                        i++;
                    }
                    value = joined.substring(valueStart, i);
                }
                if (value.isEmpty() || !names.add(name)) {
                    return null;
                }
                if (name.equals("for")) {
                    found = value;
                }
                if (i < n && joined.charAt(i) == ';') {
                    i++;
                    continue;
                }
                break;
            }
            InetAddress address = found == null ? null : literal(found);
            if (address == null) {
                return null;
            }
            result.add(address);
            while (i < n && ows(joined.charAt(i))) {
                i++;
            }
            if (i == n) {
                return result;
            }
            if (joined.charAt(i) != ',') {
                return null;
            }
            i++;
        }
    }

    /**
     * 把一段地址读成 IP
     * <p>
     * 认这几种：{@code 192.0.2.1}、{@code 192.0.2.1:8080}、{@code 2001:db8::1}、{@code [2001:db8::1]}、
     * {@code [2001:db8::1]:8080}。别的（主机名、{@code unknown}、带区域标识的、写得半截的）一律不认。
     * @param text 一段地址，可为 null
     * @return 读出的地址；不认时为 null
     */
    static InetAddress literal(String text) {
        if (text == null) {
            return null;
        }
        String value = text.strip();
        if (value.isEmpty() || value.length() > MAX_LITERAL) {
            return null;
        }
        if (value.charAt(0) == '[') {
            int close = value.indexOf(']');
            if (close < 0) {
                return null;
            }
            String rest = value.substring(close + 1);
            if (!rest.isEmpty() && !port(rest)) {
                return null;
            }
            String inner = value.substring(1, close);
            return inner.indexOf(':') < 0 ? null : ipv6(inner);
        }
        int colon = value.indexOf(':');
        if (colon < 0) {
            return ipv4(value);
        }
        if (colon == value.lastIndexOf(':')) {
            // 只有一个冒号：只能是「IPv4:端口」，IPv6 至少两个冒号
            return port(value.substring(colon)) ? ipv4(value.substring(0, colon)) : null;
        }
        return ipv6(value);
    }

    /**
     * 「:端口」写得对不对：1–65535，不带前导零
     */
    private static boolean port(String text) {
        return PORT.matcher(text).matches() && Integer.parseInt(text.substring(1)) <= 65535;
    }

    private static InetAddress ipv4(String value) {
        if (!IPV4.matcher(value).matches()) {
            return null;
        }
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException malformed) {
            return null;
        }
    }

    private static InetAddress ipv6(String value) {
        if (value.indexOf(':') < 0 || !IPV6.matcher(value).matches()) {
            return null;
        }
        try {
            // 含冒号、只有十六进制数字与点的串，getByName 按字面量解析，不查 DNS
            return InetAddress.getByName(value);
        } catch (UnknownHostException malformed) {
            return null;
        }
    }
}
