package org.frostnova.nova.core.util;

import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * 消息里的图片地址（{@code {image_url=…}}）能不能去取
 * <p>
 * 机器人插件按地址取图：有的由本程序自己下载，有的把地址交给机器人程序去取。模板能由控制台成员改写，
 * 所以地址不能指向本机、内网与链路本地，否则成员能让这台机器（或机器人程序所在的机器）去访问它们。
 *
 * <h2>怎么判</h2>
 * <ul>
 *   <li>协议只收 http、https；带账号密码、没有主机名、端口不在 1 至 65535 的不收（{@link Verdict#INVALID}）。</li>
 *   <li>主机名先解析，<b>解析出的任意一个地址</b>落在 {@link #BLOCKED} 里就不收（{@link Verdict#BLOCKED}）；
 *       解析不出来也不收（{@link Verdict#UNRESOLVED}）：拿不准的往挡的方向走。</li>
 *   <li>IPv4 映射或内嵌在 IPv6 里的地址，取出里面的 IPv4 再判。</li>
 *   <li>主机写成整数（{@code 2130706433}、{@code 0}）按实际代表的地址判；带前导零或十六进制的那几种写法，
 *       各家程序读法不一，一律不收。{@code 127.1} 这类缩写 {@link URI} 认不出主机名，按没有主机名不收。</li>
 *   <li>{@code localhost} 与 {@code *.localhost} 不经解析，直接当本机回环。</li>
 *   <li>落在设置项「消息图片 · 允许的内网网段」（{@code novabot.core.push.image-allowed-networks}）里的地址照收。</li>
 * </ul>
 * {@code 198.18.0.0/15} 有意不拦：不少部署的代理软件用这一段做假地址解析，拦了所有图片都发不出去。
 *
 * <h2>已知的时间窗口</h2>
 * 这里解析一次，真正连接时取图的一方再解析一次。两次之间域名的解析结果变了（例如解析记录很短就过期、
 * 第二次换成了内网地址），这一道看不出来；本程序不处理这个窗口。机器上配了系统代理时，
 * 连接由代理去解析目标，主机名这一层的判断不起作用；直接写内网地址的照样拦得住。
 */
@Slf4j
@Component
public class MessageImageAddressPolicy {
    /** 配置键，日志里点名用 */
    static final String KEY = "novabot.core.push.image-allowed-networks";

    /**
     * 不许取的网段：任意地址、本机回环、私有网段、链路本地、运营商级地址转换、组播与保留段，
     * 以及只在本地用的 NAT64 前缀
     */
    static final List<String> BLOCKED = List.of(
            "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12",
            "192.168.0.0/16", "224.0.0.0/4", "240.0.0.0/4",
            "::/128", "::1/128", "fc00::/7", "fec0::/10", "fe80::/10", "ff00::/8", "64:ff9b:1::/48");

    private static final IpMatcher BLOCKED_NETWORKS = new IpMatcher(BLOCKED);

    /** 系统的域名解析 */
    public static final Resolver SYSTEM_RESOLVER = InetAddress::getAllByName;

    private static final InetAddress[] LOOPBACK = {loopback()};

    private final IpMatcher allowed;

    private final Resolver resolver;

    /**
     * 判的结果
     */
    public enum Verdict {
        /** 可以取 */
        ALLOWED,
        /** 地址写得不对：不是 http、https，带账号密码，没有主机名，端口不对 */
        INVALID,
        /** 主机名解析不出来 */
        UNRESOLVED,
        /** 指向本机、内网、链路本地或其他不许取的地址 */
        BLOCKED;

        /**
         * @return 可不可以取
         */
        public boolean allowed() {
            return this == ALLOWED;
        }
    }

    /**
     * 主机名解析，测试里换成不联网的那一种
     */
    @FunctionalInterface
    public interface Resolver {
        /**
         * @param host 主机名，不会是 IP 字面量
         * @return 解析出的全部地址
         * @throws UnknownHostException 解析不出来
         */
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /**
     * 按设置页里放行的网段装配；启动时读这一次
     * @param properties 核心配置
     */
    @Autowired
    public MessageImageAddressPolicy(NovaCoreProperties properties) {
        this(properties.getPush().getImageAllowedNetworks(), SYSTEM_RESOLVER);
    }

    /**
     * 不放行任何内网网段，按系统解析
     */
    public MessageImageAddressPolicy() {
        this(List.of(), SYSTEM_RESOLVER);
    }

    /**
     * @param allowedNetworks 另外放行的网段；写错的那条不收，并记一条警告
     * @param resolver 主机名解析
     */
    public MessageImageAddressPolicy(Collection<String> allowedNetworks, Resolver resolver) {
        List<String> networks = new ArrayList<>();
        if (allowedNetworks != null) {
            for (String entry : allowedNetworks) {
                String problem = IpMatcher.problemOf(entry);
                if (problem != null) {
                    log.warn("{} 里有一条写得不对, 已忽略「{}」: {}", KEY, entry, problem);
                    continue;
                }
                networks.add(entry.strip());
            }
        }
        this.allowed = new IpMatcher(networks);
        this.resolver = resolver;
    }

    /**
     * @param url 图片地址
     * @return 可以取时为 true
     */
    public boolean permits(String url) {
        return check(url).allowed();
    }

    /**
     * @param url 图片地址
     * @return 判的结果
     */
    public Verdict check(String url) {
        if (url == null) {
            return Verdict.INVALID;
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return Verdict.INVALID;
        }
        return check(uri);
    }

    /**
     * @param uri 图片地址
     * @return 判的结果
     */
    public Verdict check(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getRawUserInfo() != null || uri.getHost() == null || uri.getHost().isEmpty()) {
            return Verdict.INVALID;
        }
        int port = uri.getPort();
        if (port != -1 && (port < 1 || port > 65535)) {
            return Verdict.INVALID;
        }

        InetAddress[] addresses;
        try {
            addresses = addressesOf(uri.getHost());
        } catch (UnknownHostException e) {
            return Verdict.UNRESOLVED;
        } catch (IllegalArgumentException e) {
            return Verdict.INVALID;
        } catch (RuntimeException e) {
            return Verdict.UNRESOLVED;
        }
        if (addresses == null || addresses.length == 0) {
            return Verdict.UNRESOLVED;
        }
        for (InetAddress address : addresses) {
            if (address == null || !permitted(address)) {
                return Verdict.BLOCKED;
            }
        }
        return Verdict.ALLOWED;
    }

    /**
     * 主机代表的全部地址
     * @throws IllegalArgumentException 主机写法拿不准（前导零、十六进制、带网卡标识的 IPv6）
     */
    private InetAddress[] addressesOf(String rawHost) throws UnknownHostException {
        String host = rawHost.toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) {
            String literal = host.substring(1, host.length() - 1);
            if (literal.indexOf('%') >= 0) {
                throw new IllegalArgumentException("带网卡标识的地址");
            }
            // 含冒号的字面量 getByName 不会去解析
            return new InetAddress[]{InetAddress.getByName(literal)};
        }
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isEmpty()) {
            throw new IllegalArgumentException("没有主机名");
        }
        if (host.equals("localhost") || host.endsWith(".localhost")) {
            return LOOPBACK;
        }
        String last = host.substring(host.lastIndexOf('.') + 1);
        if (numeric(last)) {
            return new InetAddress[]{ipv4(host)};
        }
        return resolver.resolve(host);
    }

    /** 最后一段是数字（或十六进制写法）的主机，各家程序都把它当 IPv4 读 */
    private static boolean numeric(String label) {
        if (label.isEmpty()) {
            return false;
        }
        if (label.startsWith("0x")) {
            return true;
        }
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * 按一到四段的十进制写法读出 IPv4 地址，读法与常见程序一致（最后一段填满剩下的字节）
     * <p>
     * 不交给系统去读：系统读不了的写法会被当成主机名拿去解析，带前导零的段也有的程序当八进制读，
     * 判的地址就和真正连的不是同一个。
     */
    private static InetAddress ipv4(String host) throws UnknownHostException {
        String[] parts = host.split("\\.", -1);
        if (parts.length > 4) {
            throw new IllegalArgumentException("不是 IPv4 地址");
        }
        long value = 0;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 10 || (part.length() > 1 && part.charAt(0) == '0')) {
                throw new IllegalArgumentException("拿不准的 IPv4 写法");
            }
            for (int j = 0; j < part.length(); j++) {
                char c = part.charAt(j);
                if (c < '0' || c > '9') {
                    throw new IllegalArgumentException("拿不准的 IPv4 写法");
                }
            }
            long number = Long.parseLong(part);
            boolean last = i == parts.length - 1;
            int bits = last ? 8 * (5 - parts.length) : 8;
            if (number >= (1L << bits)) {
                throw new IllegalArgumentException("IPv4 地址超出范围");
            }
            value = (value << bits) | number;
        }
        byte[] bytes = {(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
        return InetAddress.getByAddress(bytes);
    }

    /**
     * 一个地址能不能取：在放行的网段里就能；否则它自己与它内嵌的 IPv4 都不能落在不许取的网段里
     */
    private boolean permitted(InetAddress address) {
        String literal = address.getHostAddress();
        if (allowed.matches(literal)) {
            return true;
        }
        if (BLOCKED_NETWORKS.matches(literal)) {
            return false;
        }
        for (InetAddress embedded : embeddedIpv4(address.getAddress())) {
            String inner = embedded.getHostAddress();
            if (!allowed.matches(inner) && BLOCKED_NETWORKS.matches(inner)) {
                return false;
            }
        }
        return true;
    }

    /**
     * IPv6 地址里带着的 IPv4：兼容写法 {@code ::a.b.c.d}、映射 {@code ::ffff:a.b.c.d}、
     * 转换 {@code ::ffff:0:a.b.c.d}、NAT64 {@code 64:ff9b::/96}、6to4 {@code 2002::/16}、Teredo {@code 2001::/32}
     */
    private static List<InetAddress> embeddedIpv4(byte[] bytes) {
        if (bytes.length != 16) {
            return List.of();
        }
        List<InetAddress> found = new ArrayList<>();
        boolean zero8 = allZero(bytes, 0, 8);
        if (zero8 && allZero(bytes, 8, 12)) {
            found.add(ipv4At(bytes, 12, false));
        } else if (zero8 && bytes[8] == 0 && bytes[9] == 0 && (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff) {
            found.add(ipv4At(bytes, 12, false));
        } else if (zero8 && (bytes[8] & 0xff) == 0xff && (bytes[9] & 0xff) == 0xff && bytes[10] == 0 && bytes[11] == 0) {
            found.add(ipv4At(bytes, 12, false));
        } else if (bytes[0] == 0 && bytes[1] == 0x64 && (bytes[2] & 0xff) == 0xff && (bytes[3] & 0xff) == 0x9b
                && allZero(bytes, 4, 12)) {
            found.add(ipv4At(bytes, 12, false));
        } else if (bytes[0] == 0x20 && bytes[1] == 0x02) {
            found.add(ipv4At(bytes, 2, false));
        } else if (bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0 && bytes[3] == 0) {
            found.add(ipv4At(bytes, 4, false));
            found.add(ipv4At(bytes, 12, true));
        }
        return found;
    }

    private static InetAddress loopback() {
        try {
            return InetAddress.getByAddress("localhost", new byte[]{127, 0, 0, 1});
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean allZero(byte[] bytes, int from, int to) {
        for (int i = from; i < to; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static InetAddress ipv4At(byte[] bytes, int offset, boolean inverted) {
        byte[] v4 = new byte[4];
        for (int i = 0; i < 4; i++) {
            v4[i] = inverted ? (byte) ~bytes[offset + i] : bytes[offset + i];
        }
        try {
            return InetAddress.getByAddress(v4);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
