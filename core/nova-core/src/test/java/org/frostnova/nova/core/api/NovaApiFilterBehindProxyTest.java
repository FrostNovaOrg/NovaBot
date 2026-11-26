package org.frostnova.nova.core.api;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigUiSecurityFilter;
import org.frostnova.nova.core.config.ui.ConsoleAllowList;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccountStore;
import org.frostnova.nova.core.service.NovaStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 程序接口放在本机的反向代理后面时，来访者怎么认
 * <p>
 * 这里只用「一组也不对名单以外开放」的那种装配：量的是来访者判定本身，与按组开放无关。
 */
class NovaApiFilterBehindProxyTest {
    @TempDir
    Path directory;

    private NovaCoreProperties properties;

    private NovaApiTokenStore tokens;

    private final NovaApiTokenStoreTest.TestClock clock = new NovaApiTokenStoreTest.TestClock();

    private MockMvc mvc;

    static final class ProbeApi implements NovaApiProvider {
        @Override
        public String segment() {
            return "probe";
        }

        @Override
        public List<NovaApiScope> scopes() {
            return List.of(new NovaApiScope("probe:read", "读取探针", "测试用"));
        }

        @Override
        public List<NovaApiRoute> routes() {
            return List.of(NovaApiRoute.get("items", "probe:read", request -> NovaApiPage.last(List.of(JSONObject.of("n", 1)))));
        }
    }

    @BeforeEach
    void api() {
        properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(directory.resolve("live.json").toString());
        NovaStateStore state = new NovaStateStore(properties);
        tokens = new NovaApiTokenStore(state, clock);
        rebuild(new NovaApiThrottle(100, Duration.ofMinutes(1), clock), state);
    }

    private void rebuild(NovaApiThrottle throttle, NovaStateStore state) {
        NovaApiFilter filter = new NovaApiFilter(new ConsoleAllowList(properties.getConfigUi()), tokens,
                new ConfigUiAccountStore(state), () -> true, new NovaApiRegistry(List.of(new ProbeApi())), throttle,
                () -> List.of("bilibili"), List::of);
        mvc = MockMvcBuilders.standaloneSetup().addFilters(filter).build();
    }

    // 抓的故障：没打开任何开关，外网的人经本机反向代理进来就能拿令牌调接口——代理连进来的永远是本机，默认名单就把它放了。
    @Test
    void outsiderThroughTheLocalProxyIsRefusedLikeADirectOutsider() throws Exception {
        String token = issue();
        MockHttpServletResponse proxied = perform(me(token).with(peer("127.0.0.1"))
                .header("X-Forwarded-For", "203.0.113.9").header("X-Real-IP", "203.0.113.9"));
        assertEquals(403, proxied.getStatus(), proxied.getContentAsString());
        assertError(proxied, "source_not_allowed");
        assertTrue(message(proxied).contains("访问令牌"), "提示语要说清去哪里打开: " + message(proxied));

        // 同一个外网来访者直连进来，回的是同一个代码
        MockHttpServletResponse direct = perform(me(token).with(peer("203.0.113.9")));
        assertEquals(403, direct.getStatus());
        assertError(direct, "source_not_allowed");

        // 代理只写了其中一种头也一样
        for (String header : List.of("X-Forwarded-For", "X-Real-IP")) {
            assertEquals(403, perform(me(token).with(peer("127.0.0.1")).header(header, "203.0.113.9")).getStatus(), header);
        }
        assertEquals(403, perform(me(token).with(peer("::1")).header("Forwarded", "for=203.0.113.9;proto=https")).getStatus());
    }

    // 抓的故障：经本机反向代理进来的人明明在名单里（或者就是本机上的浏览器），也被挡在外面；本机直连的程序也跟着被挡。
    @Test
    void listedVisitorsThroughTheProxyAndPlainLocalCallersStillPass() throws Exception {
        String token = issue();
        assertEquals(200, perform(me(token)).getStatus(), "本机直连、不带转发头，与改动之前一样");
        assertEquals(200, perform(me(token).with(peer("::1"))).getStatus());
        assertEquals(200, perform(me(token).with(peer("127.0.0.1")).header("X-Forwarded-For", "127.0.0.1")).getStatus(),
                "本机上的浏览器经代理进来，代理看到的就是本机");

        properties.getConfigUi().setAllowIps(List.of("127.0.0.1/32", "::1/128", "203.0.113.0/24", "2001:db8::/32"));
        // 客户端自己写在最左边的那一段不算数，取的是代理接在最右边的那一段
        assertEquals(200, perform(me(token).with(peer("127.0.0.1"))
                .header("X-Forwarded-For", "198.51.100.77, 203.0.113.9").header("X-Real-IP", "203.0.113.9")).getStatus());
        assertEquals(200, perform(me(token).with(peer("127.0.0.1")).header("X-Forwarded-For", "203.0.113.9:51515")).getStatus());
        assertEquals(200, perform(me(token).with(peer("127.0.0.1"))
                .header("Forwarded", "for=198.51.100.1, for=\"[2001:db8::5]:4711\";proto=https")).getStatus());
        assertEquals(200, perform(me(token).with(peer("127.0.0.1")).header("X-Forwarded-For", "2001:db8::5")).getStatus());
    }

    // 抓的故障：客户端在最左边写一个 127.0.0.1、或在代理不写的那个头里写一个名单里的地址，就冒充成了本机或名单里的机器。
    @Test
    void clientWrittenHopsDoNotCount() throws Exception {
        String token = issue();
        assertEquals(403, perform(me(token).with(peer("127.0.0.1"))
                .header("X-Forwarded-For", "127.0.0.1, 203.0.113.9")).getStatus());
        // 代理只写了 X-Real-IP，客户端自带的 X-Forwarded-For 原样送了下来：两种头说的不是同一个，当作未知来访者
        MockHttpServletResponse disagree = perform(me(token).with(peer("127.0.0.1"))
                .header("X-Forwarded-For", "127.0.0.1").header("X-Real-IP", "203.0.113.9"));
        assertEquals(403, disagree.getStatus());
        assertTrue(message(disagree).contains("转发头"), message(disagree));
        assertEquals(403, perform(me(token).with(peer("127.0.0.1"))
                .header("X-Real-IP", "203.0.113.9").header("Forwarded", "for=127.0.0.1")).getStatus());
    }

    // 抓的故障：转发头是乱写的（主机名、unknown、半截地址），被当成本机放行。
    @Test
    void garbledForwardingHeadersMakeAnUnknownVisitor() throws Exception {
        String token = issue();
        List<String[]> garbled = List.of(
                new String[]{"X-Forwarded-For", "not-an-ip"},
                new String[]{"X-Forwarded-For", "203.0.113.9, unknown"},
                new String[]{"X-Forwarded-For", " , , "},
                new String[]{"X-Forwarded-For", ""},
                new String[]{"X-Forwarded-For", "localhost"},
                new String[]{"X-Forwarded-For", "127.1"},
                new String[]{"X-Forwarded-For", "[::1"},
                new String[]{"X-Forwarded-For", "fe80::1%eth0"},
                new String[]{"X-Real-IP", "127.0.0.1, 127.0.0.1"},
                new String[]{"X-Real-IP", "999.0.0.1"},
                new String[]{"Forwarded", "for=_hidden"},
                new String[]{"Forwarded", "for=unknown"},
                new String[]{"Forwarded", "proto=https"},
                new String[]{"Forwarded", "for=\"127.0.0.1\\\""});
        for (String[] header : garbled) {
            MockHttpServletResponse response = perform(me(token).with(peer("127.0.0.1")).header(header[0], header[1]));
            assertEquals(403, response.getStatus(), header[0] + ": " + header[1] + " → " + response.getContentAsString());
            assertError(response, "source_not_allowed");
        }
    }

    // 抓的故障：有人从外面直连、自己带一个声称是 127.0.0.1 的转发头，冒充本机。
    @Test
    void forwardingHeadersFromANonLoopbackPeerAreIgnored() throws Exception {
        String token = issue();
        for (String[] header : List.of(new String[]{"X-Forwarded-For", "127.0.0.1"}, new String[]{"X-Real-IP", "127.0.0.1"},
                new String[]{"Forwarded", "for=127.0.0.1"})) {
            MockHttpServletResponse forged = perform(me(token).with(peer("198.51.100.7")).header(header[0], header[1]));
            assertEquals(403, forged.getStatus(), header[0]);
        }
        properties.getConfigUi().setAllowIps(List.of("198.51.100.7/32"));
        assertEquals(200, perform(me(token).with(peer("198.51.100.7")).header("X-Forwarded-For", "203.0.113.9")).getStatus(),
                "对端不是本机回环时只看对端，名单里的对端不会因为一个乱写的头被挡");
    }

    // 抓的故障：一个人乱试令牌，把同一个反向代理后面的所有人都限速了。
    @Test
    void invalidTokensAreCountedPerVisitorBehindTheSameProxy() throws Exception {
        rebuild(new NovaApiThrottle(3, Duration.ofMinutes(1), clock), new NovaStateStore(properties));
        properties.getConfigUi().setAllowIps(List.of("127.0.0.1/32", "203.0.113.0/24", "198.51.100.0/24"));
        String valid = issue();
        for (int i = 0; i < 3; i++) {
            assertEquals(401, perform(me("nvb_wrong-" + i).with(peer("127.0.0.1"))
                    .header("X-Forwarded-For", "203.0.113.9")).getStatus());
        }
        assertEquals(429, perform(me("nvb_wrong-again").with(peer("127.0.0.1"))
                .header("X-Forwarded-For", "203.0.113.9")).getStatus(), "乱试的那一位自己被限");
        assertEquals(401, perform(me("nvb_typo").with(peer("127.0.0.1"))
                .header("X-Forwarded-For", "198.51.100.20")).getStatus(), "同一代理后面的另一位不受连累");
        assertEquals(401, perform(me("nvb_typo")).getStatus(), "本机直连的程序不受连累");
        assertEquals(200, perform(me(valid).with(peer("127.0.0.1"))
                .header("X-Forwarded-For", "203.0.113.9")).getStatus(), "被限的那一位出示有效令牌照常可用");
    }

    // 抓的故障：为了让程序接口认转发头，连控制台的来源判断也一起改了（控制台经本机反向代理访问的行为变了）。
    @Test
    void consoleStillJudgesByTheConnectionPeer() {
        MockHttpServletRequest proxied = new MockHttpServletRequest("GET", "/config");
        proxied.setRemoteAddr("127.0.0.1");
        proxied.addHeader("X-Forwarded-For", "203.0.113.9");
        assertEquals("127.0.0.1", ConfigUiSecurityFilter.clientAddress(proxied));
        assertTrue(ConfigUiSecurityFilter.admits(new ConsoleAllowList(properties.getConfigUi()).get(), proxied));
    }

    private String issue() {
        return tokens.issue(ConfigUiAccount.LOCAL_ID, "脚本", List.of("probe:read"), null).plaintext();
    }

    private static MockHttpServletRequestBuilder me(String token) {
        return get("/nova/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor peer(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder builder) throws Exception {
        return mvc.perform(builder).andReturn().getResponse();
    }

    private static String message(MockHttpServletResponse response) throws Exception {
        return JSONObject.parseObject(response.getContentAsString()).getJSONObject("error").getString("message");
    }

    private static void assertError(MockHttpServletResponse response, String code) throws Exception {
        JSONObject json = JSONObject.parseObject(response.getContentAsString());
        assertEquals(false, json.get("ok"), response.getContentAsString());
        assertEquals(code, json.getJSONObject("error").getString("code"), response.getContentAsString());
    }
}
