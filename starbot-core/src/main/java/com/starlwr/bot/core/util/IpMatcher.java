package com.starlwr.bot.core.util;

import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * IP 白名单匹配器，支持精确 IP 与 CIDR 网段，同时兼容 IPv4 与 IPv6
 * <p>
 * 传入的规则在构造时一次性解析完毕，非法规则会被丢弃并记录日志，
 * 避免把一条写错的规则变成运行期的静默放行。
 */
@Slf4j
public class IpMatcher {
    /**
     * 严格的点分十进制 IPv4 字面量，四段且每段取值 0-255
     */
    private static final Pattern IPV4_LITERAL = Pattern.compile("(25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)){3}");

    /**
     * 已解析的规则列表
     */
    private final List<Rule> rules = new ArrayList<>();

    /**
     * 构造 IP 匹配器
     * @param patterns 规则列表，形如 127.0.0.1、192.168.1.0/24、::1、fd00::/8
     */
    public IpMatcher(Collection<String> patterns) {
        if (patterns == null) {
            return;
        }

        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }

            try {
                rules.add(parse(pattern.trim()));
            } catch (Exception e) {
                log.error("IP 白名单规则 {} 解析失败, 已忽略该条规则", pattern, e);
            }
        }
    }

    /**
     * 判断规则列表是否为空
     * @return 规则列表是否为空
     */
    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /**
     * 判断指定 IP 是否命中白名单
     * @param ip IP 地址字符串
     * @return 是否命中
     */
    public boolean matches(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }

        byte[] candidate;
        try {
            candidate = parseLiteral(ip.trim());
        } catch (UnknownHostException e) {
            return false;
        }

        for (Rule rule : rules) {
            if (rule.matches(candidate)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 解析单条规则
     * @param pattern 规则字符串
     * @return 解析结果
     * @throws UnknownHostException IP 部分非法时抛出
     */
    private Rule parse(String pattern) throws UnknownHostException {
        int slash = pattern.indexOf('/');
        if (slash < 0) {
            byte[] address = parseLiteral(pattern);
            return new Rule(address, address.length * 8);
        }

        byte[] address = parseLiteral(pattern.substring(0, slash));

        int prefix = Integer.parseInt(pattern.substring(slash + 1).trim());
        int maxPrefix = address.length * 8;
        if (prefix < 0 || prefix > maxPrefix) {
            throw new IllegalArgumentException("CIDR 前缀长度 " + prefix + " 超出 0-" + maxPrefix + " 范围");
        }

        return new Rule(address, prefix);
    }

    /**
     * 将 IP 字面量解析为网络地址字节
     * <p>
     * 只接受 IP 字面量，绝不接受主机名。若直接使用 {@link InetAddress#getByName}，传入主机名时会触发
     * DNS 解析：一方面会让白名单在语义上被主机名绕过，另一方面解析动作发生在请求处理线程上，
     * 攻击者可借由伪造的请求头指定一个解析缓慢的域名来阻塞线程，构成拒绝服务面。
     * @param ip IP 字面量，允许携带 IPv6 Zone ID
     * @return 网络地址字节
     * @throws UnknownHostException 入参不是合法 IP 字面量时抛出
     */
    private static byte[] parseLiteral(String ip) throws UnknownHostException {
        // 去除 IPv6 地址中的 Zone ID，例如 fe80::1%eth0 -> fe80::1
        int percent = ip.indexOf('%');
        String literal = percent < 0 ? ip : ip.substring(0, percent);

        // 去除 IPv6 字面量的方括号，例如 [::1] -> ::1
        if (literal.length() > 1 && literal.charAt(0) == '[' && literal.charAt(literal.length() - 1) == ']') {
            literal = literal.substring(1, literal.length() - 1);
        }

        if (literal.isEmpty()) {
            throw new UnknownHostException("空地址");
        }

        // 含冒号者按 IPv6 字面量处理，getByName 不会对其发起 DNS 解析；
        // 其余必须严格匹配点分十进制，从而排除主机名与 127.1 这类简写形式
        if (literal.indexOf(':') < 0 && !IPV4_LITERAL.matcher(literal).matches()) {
            throw new UnknownHostException("不是合法的 IP 字面量: " + literal);
        }

        return InetAddress.getByName(literal).getAddress();
    }

    /**
     * 单条白名单规则
     * @param address 网络地址字节
     * @param prefix 前缀长度
     */
    private record Rule(byte[] address, int prefix) {
        /**
         * 判断候选地址是否落在本规则的网段内
         * @param candidate 候选地址字节
         * @return 是否命中
         */
        boolean matches(byte[] candidate) {
            // IPv4 与 IPv6 规则互不匹配, 长度不同直接判否
            if (candidate.length != address.length) {
                return false;
            }

            int fullBytes = prefix / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != address[i]) {
                    return false;
                }
            }

            int remainingBits = prefix % 8;
            if (remainingBits == 0) {
                return true;
            }

            int mask = 0xFF << (8 - remainingBits);
            return (candidate[fullBytes] & mask) == (address[fullBytes] & mask);
        }
    }
}
