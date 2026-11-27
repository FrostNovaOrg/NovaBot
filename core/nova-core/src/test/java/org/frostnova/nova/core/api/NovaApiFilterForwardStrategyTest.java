package org.frostnova.nova.core.api;

import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.Filter;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccountStore;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.web.ForwardedHeadersWiring;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 开着 {@code server.forward-headers-strategy=framework} 时程序接口怎么判本机
 * <p>
 * 转发头过滤器排在程序接口那道之前，先改写对端地址、删掉转发头。这里装的是照运行中那样装出来的那一道，
 * 排在 {@link NovaApiFilter} 前面，与运行中的先后一样。该组没有对外开放，令牌有效。
 * <p>
 * 抓的故障：反向代理没清 {@code Forwarded}（或者什么头都不动、或者有人直连后端端口）时，
 * 外面的人自带 {@code Forwarded: for=127.0.0.1} 就被当成本机，绕过逐组的对外开关。
 */
@DisplayName("程序接口：开着 framework 时，请求原本带过转发头就不算本机")
class NovaApiFilterForwardStrategyTest {
    @TempDir
    Path directory;

    private static Filter framework;

    private NovaApiTokenStore tokens;

    private String token;

    private final NovaApiTokenStoreTest.TestClock clock = new NovaApiTokenStoreTest.TestClock();

    private NovaApiFilter api;

    @BeforeAll
    static void install() {
        framework = ForwardedHeadersWiring.framework();
    }

    @BeforeEach
    void api() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(directory.resolve("live.json").toString());
        NovaStateStore state = new NovaStateStore(properties);
        tokens = new NovaApiTokenStore(state, clock);
        NovaApiRegistry registry = new NovaApiRegistry(List.of(new NovaApiFilterBehindProxyTest.ProbeApi()));
        api = new NovaApiFilter(new NovaApiOpenGroups(properties.getConfigUi()::getApiAnySourceGroups, registry),
                tokens, new ConfigUiAccountStore(state), () -> true, registry,
                new NovaApiThrottle(100, Duration.ofMinutes(1), clock), () -> List.of("bilibili"), List::of);
        token = tokens.issue(ConfigUiAccount.LOCAL_ID, "脚本", List.of("probe:read"), null).plaintext();
    }

    private MockHttpServletResponse perform(Filter forwarding, MockHttpServletRequestBuilder builder) throws Exception {
        var setup = MockMvcBuilders.standaloneSetup();
        MockMvc mvc = forwarding == null ? setup.addFilters(api).build() : setup.addFilters(forwarding, api).build();
        return mvc.perform(builder).andReturn().getResponse();
    }

    private MockHttpServletRequestBuilder items() {
        return get("/nova/api/v1/probe/items").header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static RequestPostProcessor peer(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private static void assertRefused(MockHttpServletResponse response) throws Exception {
        assertEquals(403, response.getStatus(), response.getContentAsString());
        assertEquals("source_not_allowed",
                JSONObject.parseObject(response.getContentAsString()).getJSONObject("error").getString("code"));
    }

    @Test
    @DisplayName("反代覆写 X-Forwarded-For、清掉 Forwarded，客户端什么也没带 → 403")
    void proxyThatOverwritesAndClearsIsRefused() throws Exception {
        assertRefused(perform(framework, items().with(peer("127.0.0.1")).header("X-Forwarded-For", "203.0.113.9")));
    }

    @Test
    @DisplayName("反代只覆写 X-Forwarded-For，客户端自带 Forwarded: for=127.0.0.1 → 403")
    void forgedForwardedBehindAProxyThatOnlyOverwritesForwardedForIsRefused() throws Exception {
        assertRefused(perform(framework, items().with(peer("127.0.0.1"))
                .header("X-Forwarded-For", "203.0.113.9").header("Forwarded", "for=127.0.0.1")));
    }

    @Test
    @DisplayName("反代什么头都不动、原样透传，客户端自带 Forwarded: for=127.0.0.1 → 403")
    void forgedForwardedThroughAPassThroughProxyIsRefused() throws Exception {
        assertRefused(perform(framework, items().with(peer("127.0.0.1")).header("Forwarded", "for=127.0.0.1")));
    }

    @Test
    @DisplayName("不经反代、直连后端端口，对端不是回环，自带 Forwarded: for=127.0.0.1 → 403")
    void forgedForwardedOnADirectConnectionIsRefused() throws Exception {
        assertRefused(perform(framework, items().with(peer("198.51.100.7")).header("Forwarded", "for=127.0.0.1")));
    }

    @Test
    @DisplayName("本机回环直连、不带任何转发头 → 200，本机照旧不受开关管")
    void plainLocalCallerStillPasses() throws Exception {
        MockHttpServletResponse response = perform(framework, items().with(peer("127.0.0.1")));
        assertEquals(200, response.getStatus(), response.getContentAsString());
    }

    @Test
    @DisplayName("经本机代理进来、X-Forwarded-For 写着 127.0.0.1，也不算本机 → 403")
    void loopbackClaimInForwardedForDoesNotMakeALocalCaller() throws Exception {
        assertRefused(perform(framework, items().with(peer("127.0.0.1")).header("X-Forwarded-For", "127.0.0.1")));
        assertRefused(perform(framework, items().with(peer("::1")).header("X-Forwarded-For", "::1")));
        assertRefused(perform(framework, items().with(peer("127.0.0.1")).header("X-Real-IP", "127.0.0.1")));
    }

    @Test
    @DisplayName("没开这个策略、或用 native：程序接口照旧按对端与转发头原样判")
    void otherStrategiesKeepTheCurrentJudgement() throws Exception {
        assertNull(ForwardedHeadersWiring.install().filter());
        assertNull(ForwardedHeadersWiring.install("server.forward-headers-strategy=native").filter());
        assertRefused(perform(null, items().with(peer("127.0.0.1")).header("X-Forwarded-For", "203.0.113.9")));
        assertRefused(perform(null, items().with(peer("127.0.0.1"))
                .header("X-Forwarded-For", "203.0.113.9").header("Forwarded", "for=127.0.0.1")));
        assertRefused(perform(null, items().with(peer("127.0.0.1")).header("Forwarded", "for=127.0.0.1")));
        assertRefused(perform(null, items().with(peer("198.51.100.7")).header("Forwarded", "for=127.0.0.1")));
        assertEquals(200, perform(null, items().with(peer("127.0.0.1"))).getStatus());
    }
}
