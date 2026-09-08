package com.starlwr.bot.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("IP 白名单匹配器")
class IpMatcherTest {
    @Test
    @DisplayName("精确 IPv4 匹配")
    void exactIpv4() {
        IpMatcher matcher = new IpMatcher(List.of("127.0.0.1"));

        assertTrue(matcher.matches("127.0.0.1"));
        assertFalse(matcher.matches("127.0.0.2"));
    }

    @Test
    @DisplayName("IPv4 CIDR 网段匹配")
    void cidrIpv4() {
        IpMatcher matcher = new IpMatcher(List.of("192.168.1.0/24"));

        assertTrue(matcher.matches("192.168.1.1"));
        assertTrue(matcher.matches("192.168.1.255"));
        assertFalse(matcher.matches("192.168.2.1"));
    }

    @Test
    @DisplayName("非 8 的倍数的前缀长度按位匹配")
    void cidrNonByteAlignedPrefix() {
        IpMatcher matcher = new IpMatcher(List.of("10.0.0.0/12"));

        assertTrue(matcher.matches("10.0.0.1"));
        assertTrue(matcher.matches("10.15.255.254"));
        assertFalse(matcher.matches("10.16.0.1"));
    }

    @Test
    @DisplayName("IPv6 精确与网段匹配")
    void ipv6() {
        IpMatcher matcher = new IpMatcher(List.of("::1/128", "fd00::/8"));

        assertTrue(matcher.matches("::1"));
        assertTrue(matcher.matches("fd12:3456::1"));
        assertFalse(matcher.matches("2001:db8::1"));
    }

    @Test
    @DisplayName("IPv6 地址携带 Zone ID 时仍可匹配")
    void ipv6WithZoneId() {
        IpMatcher matcher = new IpMatcher(List.of("fe80::/10"));

        assertTrue(matcher.matches("fe80::1%eth0"));
    }

    @Test
    @DisplayName("IPv4 规则不会误匹配 IPv6 地址")
    void familiesDoNotCross() {
        IpMatcher matcher = new IpMatcher(List.of("127.0.0.1/32"));

        assertFalse(matcher.matches("::1"));
    }

    @Test
    @DisplayName("非法规则被忽略且不影响其余规则")
    void invalidRuleIgnored() {
        IpMatcher matcher = new IpMatcher(List.of("not-an-ip", "192.168.1.0/99", "127.0.0.1"));

        assertTrue(matcher.matches("127.0.0.1"));
        assertFalse(matcher.matches("192.168.1.1"));
    }

    @Test
    @DisplayName("空白名单拒绝一切地址")
    void emptyMatcherRejectsAll() {
        IpMatcher matcher = new IpMatcher(List.of());

        assertTrue(matcher.isEmpty());
        assertFalse(matcher.matches("127.0.0.1"));
    }

    @Test
    @DisplayName("非法或空的待检地址一律拒绝")
    void malformedCandidateRejected() {
        IpMatcher matcher = new IpMatcher(List.of("0.0.0.0/0"));

        assertFalse(matcher.matches(null));
        assertFalse(matcher.matches(""));
        assertFalse(matcher.matches("999.999.999.999"));
    }

    @Test
    @DisplayName("待检地址不会触发 DNS 解析")
    void hostnameIsNotResolved() {
        IpMatcher matcher = new IpMatcher(List.of("0.0.0.0/0"));

        // 若实现误用 DNS 解析, 主机名会被解析为 IP 从而命中 0.0.0.0/0
        assertFalse(matcher.matches("localhost"));
        assertFalse(matcher.matches("example.com"));
    }

    @Test
    @DisplayName("规则中的主机名被视为非法并忽略")
    void hostnameRuleIsRejected() {
        IpMatcher matcher = new IpMatcher(List.of("localhost"));

        assertTrue(matcher.isEmpty());
        assertFalse(matcher.matches("127.0.0.1"));
    }

    @Test
    @DisplayName("IPv4 简写形式不被接受")
    void shorthandIpv4Rejected() {
        IpMatcher matcher = new IpMatcher(List.of("0.0.0.0/0"));

        // 127.1 在 getByName 下等价于 127.0.0.1, 严格字面量校验应拒绝这种简写
        assertFalse(matcher.matches("127.1"));
    }

    @Test
    @DisplayName("方括号包裹的 IPv6 字面量可正常匹配")
    void bracketedIpv6() {
        IpMatcher matcher = new IpMatcher(List.of("::1/128"));

        assertTrue(matcher.matches("[::1]"));
    }
}
