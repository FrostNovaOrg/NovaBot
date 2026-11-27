package org.frostnova.nova.core.web;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.valves.RemoteIpValve;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.frostnova.nova.core.web.ForwardedHeadersWiring.request;
import static org.frostnova.nova.core.web.ForwardedHeadersWiring.through;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 顶替框架那一道的转发头过滤器：主机名、协议与网段这几处
 * <p>
 * 来源地址那几行在 {@code ConfigUiSourceBehindProxyTest} 与 {@code NovaApiFilterForwardStrategyTest} 里逐行核，
 * 这里核的是同一道过滤器的另外几处：客户端自带的头改不了主机名与协议、不可信对端的头后面看不到、
 * 缺省网段与 Tomcat 同一套、写错的追加网段往不信任的方向走。
 */
@DisplayName("转发头过滤器：只认可信代理送来的 X-Forwarded-*")
class TrustedProxyForwardedHeaderFilterTest {
    private static final String PATH = "/config/api/status";

    // 抓的故障：缺省网段与 Tomcat 的 internal-proxies 走了样，手册里「与 Tomcat 同一套」那句不再成立。
    @Test
    @DisplayName("缺省信任的网段与 Tomcat RemoteIpValve 缺省的 internal-proxies 同一套")
    void defaultNetworksMatchTomcat() {
        Set<String> tomcat = Arrays.stream(new RemoteIpValve().getInternalProxies().split(","))
                .map(String::strip).filter(entry -> !entry.isEmpty()).collect(Collectors.toSet());
        assertEquals(tomcat, Set.copyOf(TrustedProxyForwardedHeaderFilter.DEFAULT_TRUSTED_PROXIES));
    }

    // 抓的故障：Boot 那一道还在，或者装了两道。
    @Test
    @DisplayName("开着 framework 时装上的是这一道")
    void frameworkInstallsThisFilter() {
        assertInstanceOf(TrustedProxyForwardedHeaderFilter.class, ForwardedHeadersWiring.framework());
    }

    // 抓的故障：外面的人自带 Forwarded 写 proto=https、host=别的站，程序就以为连接是 https、主机是那个站。
    @Test
    @DisplayName("Forwarded 改不了协议与主机名；可信代理送来的 X-Forwarded-Proto、X-Forwarded-Host 照常认")
    void forwardedNeverRewritesSchemeOrHost() throws Exception {
        Filter filter = ForwardedHeadersWiring.framework();
        HttpServletRequest forged = through(filter, request(PATH, "127.0.0.1",
                "Forwarded", "for=192.0.2.1;proto=https;host=evil.example"));
        assertEquals("http", forged.getScheme());
        assertFalse(forged.isSecure());
        assertEquals("localhost", forged.getServerName());
        assertNull(forged.getHeader("Forwarded"), "后面的各环看不到 Forwarded");

        HttpServletRequest proxied = through(filter, request(PATH, "127.0.0.1",
                "Host", "bot.example", "X-Forwarded-Proto", "https", "Forwarded", "proto=http;host=evil.example"));
        assertEquals("https", proxied.getScheme());
        assertTrue(proxied.isSecure());
        assertEquals("bot.example", proxied.getServerName());
        assertNull(proxied.getHeader("Forwarded"));
        assertNull(proxied.getHeader("X-Forwarded-Proto"), "与框架那一道一样，认过的头从请求里去掉");
    }

    // 抓的故障：反代原样透传了客户端自带的 X-Forwarded-Host，程序看到的主机名成了客户端写的那个站。
    @Test
    @DisplayName("可信代理送来的 X-Forwarded-Host 不认：主机名以 Host 为准，这个头从请求里去掉")
    void trustedProxyForwardedHostIsIgnored() throws Exception {
        Filter filter = ForwardedHeadersWiring.framework();
        for (String[] extra : List.of(new String[]{}, new String[]{"X-Forwarded-Proto", "https"})) {
            List<String> headers = new ArrayList<>(List.of("Host", "bot.example",
                    "X-Forwarded-Host", "evil.example:8443"));
            headers.addAll(List.of(extra));
            HttpServletRequest seen = through(filter, request(PATH, "127.0.0.1", headers.toArray(String[]::new)));
            assertEquals("bot.example", seen.getServerName(), "带着 " + headers);
            assertTrue(seen.getServerPort() != 8443, "带着 " + headers + "，端口成了 " + seen.getServerPort());
            assertNull(seen.getHeader("X-Forwarded-Host"));
            assertFalse(seen.getHeaders("X-Forwarded-Host").hasMoreElements());
            assertTrue(Collections.list(seen.getHeaderNames()).stream().noneMatch("X-Forwarded-Host"::equalsIgnoreCase));
            assertEquals("bot.example", seen.getHeader("Host"), "Host 原样留着");
        }
    }

    // 抓的故障：反代原样透传了客户端自带的 X-Forwarded-Port，程序看到的端口成了客户端写的那个。
    @Test
    @DisplayName("可信代理送来的 X-Forwarded-Port 不认：协议照 X-Forwarded-Proto，端口不跟着客户端写的走")
    void trustedProxyForwardedPortIsIgnored() throws Exception {
        HttpServletRequest seen = through(ForwardedHeadersWiring.framework(), request(PATH, "127.0.0.1",
                "Host", "bot.example", "X-Forwarded-Port", "1", "X-Forwarded-Proto", "https"));
        assertEquals("https", seen.getScheme());
        assertTrue(seen.isSecure());
        assertEquals("bot.example", seen.getServerName());
        assertTrue(seen.getServerPort() != 1, "端口成了客户端写的 " + seen.getServerPort());
        assertEquals(443, seen.getServerPort());
        assertNull(seen.getHeader("X-Forwarded-Port"));
        assertTrue(Collections.list(seen.getHeaderNames()).stream().noneMatch("X-Forwarded-Port"::equalsIgnoreCase));
    }

    // 抓的故障：反代原样透传了客户端自带的 X-Forwarded-Prefix，请求路径与上下文前缀跟着改，原样路径的检查看到的不是原样。
    @Test
    @DisplayName("可信代理送来的 X-Forwarded-Prefix 不认：请求路径与上下文前缀原样，这个头从请求里去掉")
    void trustedProxyForwardedPrefixIsIgnored() throws Exception {
        Filter filter = ForwardedHeadersWiring.framework();
        for (String[] extra : List.of(new String[]{}, new String[]{"X-Forwarded-Proto", "https"})) {
            List<String> headers = new ArrayList<>(List.of("Host", "bot.example", "X-Forwarded-Prefix", "/p"));
            headers.addAll(List.of(extra));
            HttpServletRequest seen = through(filter, request("//config/api/status", "127.0.0.1",
                    headers.toArray(String[]::new)));
            assertEquals("//config/api/status", seen.getRequestURI(), "带着 " + headers);
            assertEquals("", seen.getContextPath(), "带着 " + headers);
            assertNull(seen.getHeader("X-Forwarded-Prefix"));
            assertTrue(Collections.list(seen.getHeaderNames()).stream().noneMatch("X-Forwarded-Prefix"::equalsIgnoreCase));
        }
    }

    // 抓的故障：程序里一次相对地址的跳转，被改写成客户端自带的 X-Forwarded-Host 指的那个站。
    @Test
    @DisplayName("相对地址的跳转不指向客户端自带 X-Forwarded-Host 写的站")
    void relativeRedirectStaysOnHost() throws Exception {
        Filter filter = ForwardedHeadersWiring.framework();
        for (String[] extra : List.of(new String[]{}, new String[]{"X-Forwarded-Proto", "https"},
                new String[]{"X-Forwarded-Proto", "https", "X-Forwarded-Prefix", "/p", "X-Forwarded-Port", "1"})) {
            List<String> headers = new ArrayList<>(List.of("Host", "bot.example",
                    "X-Forwarded-Host", "evil.example"));
            headers.addAll(List.of(extra));
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request(PATH, "127.0.0.1", headers.toArray(String[]::new)), response,
                    (passed, out) -> ((HttpServletResponse) out).sendRedirect("/config"));
            String location = response.getHeader("Location");
            System.out.printf("REDIRECT %s → Location: %s%n", headers, location);
            assertNotNull(location, "带着 " + headers);
            assertFalse(location.contains("evil.example"), "带着 " + headers + " → " + location);
            assertFalse(location.contains("/p/"), "带着 " + headers + " → " + location);
            assertTrue(location.endsWith("/config"), location);
            if (location.contains("://")) {
                assertTrue(location.startsWith("https://bot.example/"), location);
            }
        }
    }

    // 抓的故障：去掉那三种头时连带把来源地址与协议的采信也拿掉了。
    @Test
    @DisplayName("可信代理送来的 X-Forwarded-For、X-Forwarded-Proto 照旧认，夹着那三种头也一样")
    void trustedProxyForAndProtoStillHonoured() throws Exception {
        Filter filter = ForwardedHeadersWiring.framework();
        for (String[] extra : List.of(new String[]{}, new String[]{"X-Forwarded-Host", "evil.example",
                "X-Forwarded-Port", "1", "X-Forwarded-Prefix", "/p"})) {
            List<String> headers = new ArrayList<>(List.of("Host", "bot.example",
                    "X-Forwarded-For", "198.51.100.1", "X-Forwarded-Proto", "https"));
            headers.addAll(List.of(extra));
            HttpServletRequest seen = through(filter, request(PATH, "127.0.0.1", headers.toArray(String[]::new)));
            assertEquals("198.51.100.1", seen.getRemoteAddr(), "带着 " + headers);
            assertEquals("https", seen.getScheme(), "带着 " + headers);
            assertTrue(seen.isSecure());
            assertEquals("bot.example", seen.getServerName());
            assertEquals(443, seen.getServerPort());
            assertEquals(PATH, seen.getRequestURI());
            assertEquals(new TrustedProxyForwardedHeaderFilter.Arrival("127.0.0.1", true, true, true),
                    TrustedProxyForwardedHeaderFilter.arrival(seen));
        }
        HttpServletRequest ssl = through(filter, request(PATH, "127.0.0.1", "Host", "bot.example",
                "X-Forwarded-Ssl", "on", "X-Forwarded-Host", "evil.example"));
        assertEquals("https", ssl.getScheme(), "X-Forwarded-Ssl 照旧认");
        assertEquals("bot.example", ssl.getServerName());
    }

    // 抓的故障：没开这个策略或用 native 时，程序这边也动了这几个头。
    @Test
    @DisplayName("没开这个策略、或用 native：程序不装转发头过滤器，这几个头原样留给后面")
    void otherStrategiesLeaveHeadersAlone() {
        assertNull(ForwardedHeadersWiring.install().filter());
        assertNull(ForwardedHeadersWiring.install("server.forward-headers-strategy=native").filter());
        assertNull(ForwardedHeadersWiring.install("server.forward-headers-strategy=none").filter());
    }

    // 抓的故障：直连后端端口的人自带 X-Forwarded-Proto、X-Forwarded-Host，后面直接读这两个头的地方（通行密钥）照样采信。
    @Test
    @DisplayName("不可信的对端：X-Forwarded-* 与 Forwarded 不改写，后面也看不到；X-Real-IP 原样留着")
    void untrustedPeerHeadersAreRemoved() throws Exception {
        Filter filter = ForwardedHeadersWiring.framework();
        HttpServletRequest seen = through(filter, request(PATH, "203.0.113.9",
                "X-Forwarded-Proto", "https", "X-Forwarded-Host", "bot.example", "X-Forwarded-Prefix", "/elsewhere",
                "X-Forwarded-Port", "8443", "X-Forwarded-Ssl", "on", "Forwarded", "for=127.0.0.1", "X-Real-IP", "198.51.100.1"));
        assertEquals("203.0.113.9", seen.getRemoteAddr());
        assertEquals("http", seen.getScheme());
        assertEquals("localhost", seen.getServerName());
        assertEquals(PATH, seen.getRequestURI());
        List<String> names = Collections.list(seen.getHeaderNames());
        for (String name : List.of("X-Forwarded-Proto", "X-Forwarded-Host", "X-Forwarded-Prefix", "X-Forwarded-Port",
                "X-Forwarded-Ssl", "Forwarded")) {
            assertNull(seen.getHeader(name), name);
            assertFalse(seen.getHeaders(name).hasMoreElements(), name);
            assertTrue(names.stream().noneMatch(name::equalsIgnoreCase), name + " 不该还在 " + names);
        }
        assertEquals("198.51.100.1", seen.getHeader("X-Real-IP"));

        TrustedProxyForwardedHeaderFilter.Arrival arrival = TrustedProxyForwardedHeaderFilter.arrival(seen);
        assertEquals(new TrustedProxyForwardedHeaderFilter.Arrival("203.0.113.9", false, true, false), arrival);
    }

    // 抓的故障：追加网段里写错的一条让整份失效，或者被当成了「全信」。
    @Test
    @DisplayName("追加网段里写错的那条不收，其余照收；IPv6 网段也认")
    void malformedExtraEntriesAreSkipped() throws Exception {
        TrustedProxyForwardedHeaderFilter filter = new TrustedProxyForwardedHeaderFilter(
                List.of("not-a-network", "0.0.0.0/99", "203.0.113.0/24", " 2001:db8::/32 "));
        assertEquals("198.51.100.23", through(filter, request(PATH, "203.0.113.9", "X-Forwarded-For", "198.51.100.23"))
                .getRemoteAddr());
        assertEquals("198.51.100.23", through(filter, request(PATH, "2001:db8::5", "X-Forwarded-For", "198.51.100.23"))
                .getRemoteAddr());
        assertEquals("198.51.100.9", through(filter, request(PATH, "198.51.100.9", "X-Forwarded-For", "192.0.2.1"))
                .getRemoteAddr(), "不在任何一条网段里的对端照旧不信");
    }

    // 抓的故障：不带任何转发头的请求也被包了一层，跳转、路径这些行为变了。
    @Test
    @DisplayName("不带任何转发头的请求原样放过，不记到达原样")
    void plainRequestsPassUntouched() throws Exception {
        var request = request(PATH, "203.0.113.9");
        HttpServletRequest seen = through(ForwardedHeadersWiring.framework(), request);
        assertTrue(seen == request, "原样交给后面");
        assertNull(TrustedProxyForwardedHeaderFilter.arrival(seen));
    }
}
