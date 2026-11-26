package org.frostnova.nova.core.api;

import jakarta.servlet.http.HttpServletRequest;

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
 * <h2>判法</h2>
 * <ol>
 *   <li>对端不是本机回环：来访者就是对端，转发头一概不看。外面直连进来的人写什么头都冒充不了本机。</li>
 *   <li>对端是本机回环、请求里没有转发头（{@code X-Forwarded-For}、{@code X-Real-IP}、{@code Forwarded}
 *       三种都没有）：本机来访。</li>
 *   <li>对端是本机回环、带了转发头：当作经本机上的反向代理进来，来访者取转发头里离 NovaBot 最近的那一段：
 *     <ul>
 *       <li>{@code X-Forwarded-For} 取最右一段（与推送接口同一个取法）：这个头是追加式的，
 *           最左边那几段由客户端说了算，最右一段是本机那层代理写上的；</li>
 *       <li>{@code X-Real-IP} 只能有一个值；</li>
 *       <li>{@code Forwarded} 取最后一项里的 {@code for=}。</li>
 *     </ul>
 *     带了几种就几种都要解析得出、而且<b>指的是同一个地址</b>。有一种解析不出合法地址、
 *     或者几种说的不是同一个，都当作<b>未知来访者</b>：不是本机，也不在任何名单里。
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
 * @param address 名单判定与日志用的地址；未知来访者为 null
 * @param kind    怎么判出来的
 */
public record NovaApiVisitor(String address, Kind kind) {
    /** 来访者是怎么判出来的 */
    public enum Kind {
        /** 对端不是本机回环，就是对端 */
        DIRECT,
        /** 本机直连：对端是回环，没带转发头 */
        LOCAL,
        /** 经本机的反向代理进来，取自转发头 */
        PROXIED,
        /** 经本机的反向代理进来，但转发头解析不出合法地址，或几种头说的不是同一个 */
        UNKNOWN
    }

    static final String X_FORWARDED_FOR = "X-Forwarded-For";

    static final String X_REAL_IP = "X-Real-IP";

    static final String FORWARDED = "Forwarded";

    /** 未知来访者在限速表里共用的那一格：解析不出地址的请求不该各占一格，也不该与本机同一格 */
    static final String UNKNOWN_KEY = "unknown-forwarded";

    /** 严格的点分十进制 IPv4，四段、每段 0-255，与来源名单的判法同一条 */
    private static final Pattern IPV4 = Pattern.compile(
            "(25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)){3}");

    /** 只含十六进制数字、冒号与点的才拿去按 IPv6 字面量解析：不让主机名走到 DNS 那一步 */
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]+");

    private static final Pattern PORT = Pattern.compile(":\\d{1,5}");

    /** 一段地址最长多少个字：IPv6 带方括号与端口也到不了这么长，更长的不必解析 */
    private static final int MAX_LITERAL = 64;

    /**
     * 判出这个请求的来访者
     * @param request 请求
     * @return 来访者
     */
    public static NovaApiVisitor of(HttpServletRequest request) {
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
            claims.add(literal(rightmost(String.join(",", forwardedFor))));
        }
        if (!realIp.isEmpty()) {
            claims.add(realIp.size() == 1 && realIp.get(0).indexOf(',') < 0 ? literal(realIp.get(0)) : null);
        }
        if (!forwarded.isEmpty()) {
            claims.add(literal(forwardedFor(String.join(",", forwarded))));
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
            case PROXIED -> address + "（经本机反向代理）";
            case UNKNOWN -> "未知来访者（经本机反向代理，转发头里认不出来访地址）";
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

    /**
     * {@code X-Forwarded-For} 最右侧那一段
     * @param joined 各行按逗号接起来的原文
     * @return 最右侧非空的一段；整个头只有逗号与空白时为 null
     */
    static String rightmost(String joined) {
        String[] hops = joined.split(",", -1);
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].strip();
            if (!hop.isEmpty()) {
                return hop;
            }
        }
        return null;
    }

    /**
     * {@code Forwarded} 最后一项里的 {@code for=}
     * <p>
     * 形如 {@code for=192.0.2.60;proto=https, for="[2001:db8::17]:4711"}。值可以带双引号；
     * 引号里出现反斜杠（转义）的不认。最后一项没有 {@code for=} 也不认。
     * @param joined 各行按逗号接起来的原文
     * @return {@code for=} 的值，去掉了引号；认不出时为 null
     */
    static String forwardedFor(String joined) {
        String last = rightmost(joined);
        if (last == null) {
            return null;
        }
        String found = null;
        for (String pair : last.split(";", -1)) {
            int equals = pair.indexOf('=');
            if (equals < 0) {
                continue;
            }
            if (pair.substring(0, equals).strip().toLowerCase(Locale.ROOT).equals("for")) {
                if (found != null) {
                    return null;
                }
                found = pair.substring(equals + 1).strip();
            }
        }
        if (found == null) {
            return null;
        }
        if (found.length() >= 2 && found.startsWith("\"") && found.endsWith("\"")) {
            found = found.substring(1, found.length() - 1);
            if (found.indexOf('\\') >= 0 || found.indexOf('"') >= 0) {
                return null;
            }
        }
        return found;
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
            if (!rest.isEmpty() && !PORT.matcher(rest).matches()) {
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
            return PORT.matcher(value.substring(colon)).matches() ? ipv4(value.substring(0, colon)) : null;
        }
        return ipv6(value);
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
