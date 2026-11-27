package org.frostnova.nova.core.config.ui;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.frostnova.nova.core.config.ui.auth.LoginThrottle;
import org.frostnova.nova.core.util.IpMatcher;
import org.frostnova.nova.core.web.ForwardedHeadersWiring;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.frostnova.nova.core.web.ForwardedHeadersWiring.request;
import static org.frostnova.nova.core.web.ForwardedHeadersWiring.through;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 开着 {@code server.forward-headers-strategy=framework} 时，控制台看到的来源地址
 * <p>
 * 控制台的来源名单、按来源的登录锁定、通行密钥的登录挑战，看的都是 {@link ConfigUiSecurityFilter#clientAddress}，
 * 也就是排在最前的那道转发头过滤器改写之后的 {@code getRemoteAddr()}。过滤器取自照运行中那样装出来的上下文。
 * <p>
 * 抓的故障：外面的人自带一个 {@code Forwarded} 或 {@code X-Forwarded-For} 头，就让程序把他当成名单里的地址、
 * 每次换一个地址躲开登录锁定，或者冒充主人的地址把主人锁在外面。
 */
@DisplayName("控制台来源：开着 framework 时只认可信代理送来的 X-Forwarded-For")
class ConfigUiSourceBehindProxyTest {
    private static final String REAL = "198.51.100.23";

    private static final String FORGED = "192.0.2.77";

    private static final String OUTSIDER = "203.0.113.9";

    private static final String PATH = ConfigUiController.BASE_PATH + "/api/status";

    private static Filter framework;

    @BeforeAll
    static void install() {
        framework = ForwardedHeadersWiring.framework();
    }

    private static String source(Filter filter, MockHttpServletRequest request) throws Exception {
        return ConfigUiSecurityFilter.clientAddress(through(filter, request));
    }

    @Test
    @DisplayName("本机代理：X-Forwarded-For 写真实地址、客户端另带 Forwarded 伪造地址 → 真实地址")
    void loopbackProxyWithForgedForwardedYieldsTheForwardedForAddress() throws Exception {
        assertEquals(REAL, source(framework, request(PATH, "127.0.0.1",
                "X-Forwarded-For", REAL, "Forwarded", "for=" + FORGED)));
    }

    @Test
    @DisplayName("本机代理：只有 Forwarded 伪造地址 → 对端 127.0.0.1")
    void forwardedAloneNeverRewritesTheSource() throws Exception {
        assertEquals("127.0.0.1", source(framework, request(PATH, "127.0.0.1", "Forwarded", "for=" + FORGED)));
    }

    @Test
    @DisplayName("私有网段里的代理 10.0.0.5：X-Forwarded-For 写真实地址 → 真实地址")
    void privateNetworkProxyIsTrusted() throws Exception {
        assertEquals(REAL, source(framework, request(PATH, "10.0.0.5", "X-Forwarded-For", REAL)));
    }

    @Test
    @DisplayName("对端 203.0.113.9 不是可信代理：自带 X-Forwarded-For 伪造地址 → 对端")
    void forwardedForFromAnUntrustedPeerIsIgnored() throws Exception {
        assertEquals(OUTSIDER, source(framework, request(PATH, OUTSIDER, "X-Forwarded-For", FORGED)));
    }

    @Test
    @DisplayName("对端 203.0.113.9 不是可信代理：自带 Forwarded: for=127.0.0.1 → 对端")
    void forwardedFromAnUntrustedPeerIsIgnored() throws Exception {
        assertEquals(OUTSIDER, source(framework, request(PATH, OUTSIDER, "Forwarded", "for=127.0.0.1")));
    }

    @Test
    @DisplayName("追加了 203.0.113.0/24 之后，对端 203.0.113.9 的 X-Forwarded-For → 真实地址")
    void extraTrustedNetworkIsHonoured() throws Exception {
        Filter extended = ForwardedHeadersWiring.framework("novabot.core.config-ui.trusted-proxies[0]=203.0.113.0/24");
        assertEquals(REAL, source(extended, request(PATH, OUTSIDER, "X-Forwarded-For", REAL)));
        assertEquals(OUTSIDER, source(framework, request(PATH, OUTSIDER, "X-Forwarded-For", REAL)),
                "没追加时同一个请求只认对端");
    }

    @Test
    @DisplayName("名单只放行主人网段：外面的人自带转发头写主人的地址，进不来")
    void allowListCannotBeBypassedWithAForgedHeader() throws Exception {
        IpMatcher owner = new IpMatcher(List.of("198.51.100.0/24"));
        assertFalse(ConfigUiSecurityFilter.admits(owner, through(framework,
                request(PATH, OUTSIDER, "X-Forwarded-For", REAL))), "直连后端端口、自带 X-Forwarded-For");
        assertFalse(ConfigUiSecurityFilter.admits(owner, through(framework,
                request(PATH, "127.0.0.1", "X-Forwarded-For", OUTSIDER, "Forwarded", "for=" + REAL))),
                "经只覆写了 X-Forwarded-For 的本机代理、自带 Forwarded");
        assertTrue(ConfigUiSecurityFilter.admits(owner, through(framework,
                request(PATH, "127.0.0.1", "X-Forwarded-For", REAL))), "主人经本机代理照常进得来");
    }

    @Test
    @DisplayName("登录锁定：轮换伪造地址躲不开，冒充主人的地址锁不住主人")
    void loginLockoutFollowsTheRealSource() throws Exception {
        LoginThrottle throttle = new LoginThrottle(5, Duration.ofMinutes(15));
        Instant now = Instant.parse("2026-11-27T10:00:00Z");
        for (int i = 0; i < 5; i++) {
            // 经只覆写了 X-Forwarded-For 的本机代理进来，每次自带一个不同的 Forwarded 地址
            throttle.recordFailure(source(framework, request(PATH, "127.0.0.1",
                    "X-Forwarded-For", OUTSIDER, "Forwarded", "for=192.0.2." + (10 + i))), now);
        }
        assertFalse(throttle.remainingLockout(OUTSIDER, now).isZero(), "攻击者自己被锁");

        LoginThrottle second = new LoginThrottle(5, Duration.ofMinutes(15));
        for (int i = 0; i < 5; i++) {
            // 直连后端端口，自带转发头写主人的地址
            second.recordFailure(source(framework, request(PATH, OUTSIDER, "Forwarded", "for=" + REAL,
                    "X-Forwarded-For", REAL)), now);
        }
        assertTrue(second.remainingLockout(REAL, now).isZero(), "主人没有被锁");
        assertFalse(second.remainingLockout(OUTSIDER, now).isZero(), "被锁的是攻击者自己");
    }

    @Test
    @DisplayName("没开这个策略、或用 native：程序不装转发头过滤器，控制台看到的就是对端")
    void otherStrategiesInstallNoServletFilter() throws Exception {
        Filter none = ForwardedHeadersWiring.install().filter();
        assertNull(none);
        assertNull(ForwardedHeadersWiring.install("server.forward-headers-strategy=native").filter(),
                "native 时由 Tomcat 处理，程序这边一道过滤器也不加");

        for (String[] row : List.of(new String[]{"127.0.0.1", "Forwarded", "for=" + FORGED},
                new String[]{OUTSIDER, "X-Forwarded-For", FORGED}, new String[]{OUTSIDER, "Forwarded", "for=127.0.0.1"})) {
            HttpServletRequest seen = through(none, request(PATH, row[0], row[1], row[2]));
            assertEquals(row[0], ConfigUiSecurityFilter.clientAddress(seen), String.join(" ", row));
        }
    }
}
