package org.frostnova.nova.core.api;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccountStore;
import org.frostnova.nova.core.service.NovaStateStore;
import org.junit.jupiter.api.BeforeEach;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 程序接口的来源这一道只分本机与别的机器，控制台「允许访问的来源 IP」名单不参与
 * <p>
 * 本机：对端是回环、请求不带任何转发头。别的来访者（直连的别的机器、经本机反向代理进来的）
 * 只有请求的这组接口在「对任何地址开放的接口组」里才过。
 */
class NovaApiFilterLocalOnlyTest {
    private static final String OUTSIDER = "203.0.113.9";

    private static final String NEIGHBOUR = "192.168.1.30";

    @TempDir
    Path directory;

    private NovaCoreProperties properties;

    private NovaApiTokenStore tokens;

    private MockMvc mvc;

    /** 一组普通接口 */
    static final class ParcelApi implements NovaApiProvider {
        @Override
        public String segment() {
            return "parcel";
        }

        @Override
        public List<NovaApiScope> scopes() {
            return List.of(new NovaApiScope("parcel:read", "读取取件", "测试用"));
        }

        @Override
        public List<NovaApiRoute> routes() {
            return List.of(NovaApiRoute.get("items", "parcel:read", request -> NovaApiPage.last(List.of(JSONObject.of("n", 1)))));
        }
    }

    @BeforeEach
    void api() {
        properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(directory.resolve("live.json").toString());
        NovaStateStore state = new NovaStateStore(properties);
        tokens = new NovaApiTokenStore(state, new NovaApiTokenStoreTest.TestClock());
        NovaApiRegistry registry = new NovaApiRegistry(List.of(new ParcelApi()));
        NovaApiFilter filter = new NovaApiFilter(
                new NovaApiOpenGroups(properties.getConfigUi()::getApiAnySourceGroups, registry), tokens,
                new ConfigUiAccountStore(state), () -> true, registry, new NovaApiThrottle(), () -> List.of("bilibili"),
                List::of);
        mvc = MockMvcBuilders.standaloneSetup().addFilters(filter).build();
    }

    // 抓的故障：控制台放在公网上的人只好让「允许访问的来源 IP」放行所有地址，接口各组的开关随之形同虚设：
    // 任何拿到有效令牌的人都能从外面调全部接口，直连进来的与经本机反向代理进来的都一样。
    @Test
    void consoleListAdmittingEveryAddressLeavesClosedGroupsLocalOnly() throws Exception {
        String token = issue();
        for (List<String> everyone : List.of(List.of("0.0.0.0/0", "::/0"), List.of("127.0.0.1/32", "0.0.0.0/1", "128.0.0.0/1"))) {
            properties.getConfigUi().setAllowIps(everyone);

            MockHttpServletResponse direct = perform(items(token).with(peer(OUTSIDER)));
            assertEquals(403, direct.getStatus(), everyone + " 直连: " + direct.getContentAsString());
            assertError(direct, "source_not_allowed");
            String said = message(direct);
            assertTrue(said.contains("不是从本机直接发来的") && said.contains("本机以外") && said.contains("从别的机器访问"), said);
            assertFalse(said.contains("名单"), "不再叫人去看控制台名单: " + said);

            MockHttpServletResponse proxied = perform(items(token).with(peer("127.0.0.1")).header("X-Forwarded-For", OUTSIDER));
            assertEquals(403, proxied.getStatus(), everyone + " 经代理: " + proxied.getContentAsString());
            assertError(proxied, "source_not_allowed");

            assertEquals(403, perform(me(token).with(peer(OUTSIDER))).getStatus(), "所有组都关着时 /me 也只许本机");
            assertEquals(200, perform(items(token)).getStatus(), "本机照常");
        }

        // 打开这一组之后，直连与经代理的才过
        properties.getConfigUi().setApiAnySourceGroups(List.of("parcel"));
        assertEquals(200, perform(items(token).with(peer(OUTSIDER))).getStatus());
        assertEquals(200, perform(items(token).with(peer("127.0.0.1")).header("X-Forwarded-For", OUTSIDER)).getStatus());
        assertEquals(200, perform(me(token).with(peer(OUTSIDER))).getStatus(), "至少一组开着时 /me 对任何地址开放");
    }

    // 抓的故障：把内网某台机器加进控制台名单（只为在那台机器上打开控制台），它就顺带调得了全部接口，开关管不住。
    @Test
    void machineInTheConsoleListStillNeedsTheGroupSwitch() throws Exception {
        String token = issue();
        properties.getConfigUi().setAllowIps(List.of("127.0.0.1/32", "::1/128", NEIGHBOUR, "203.0.113.0/24"));

        MockHttpServletResponse direct = perform(items(token).with(peer(NEIGHBOUR)));
        assertEquals(403, direct.getStatus(), direct.getContentAsString());
        assertError(direct, "source_not_allowed");
        MockHttpServletResponse proxied = perform(items(token).with(peer("127.0.0.1"))
                .header("X-Forwarded-For", OUTSIDER).header("X-Real-IP", OUTSIDER));
        assertEquals(403, proxied.getStatus(), "经本机反向代理进来、转发头认得出名单里的地址: " + proxied.getContentAsString());

        properties.getConfigUi().setApiAnySourceGroups(List.of("parcel"));
        assertEquals(200, perform(items(token).with(peer(NEIGHBOUR))).getStatus(), "开关开着才过");
    }

    // 抓的故障：管理员把控制台名单改成只有公网或内网的地址（不再列回环），本机上的脚本就调不了接口了。
    @Test
    void localCallerPassesWhenTheConsoleListHasNoLoopback() throws Exception {
        String token = issue();
        for (List<String> list : List.<List<String>>of(List.of("192.168.1.0/24"), List.of())) {
            properties.getConfigUi().setAllowIps(list);
            assertEquals(200, perform(items(token)).getStatus(), list + " 本机 IPv4");
            assertEquals(200, perform(items(token).with(peer("::1"))).getStatus(), list + " 本机 IPv6");
            assertEquals(200, perform(me(token).with(peer("127.0.0.2"))).getStatus(), list + " 回环段里的别的地址");
        }
    }

    // 抓的故障：经本机反向代理进来的请求，只要转发头写着回环地址就被当成本机，没开开关也调得通。
    @Test
    void proxiedRequestClaimingLoopbackIsNotLocal() throws Exception {
        String token = issue();
        List<String[]> claims = List.of(new String[]{"X-Forwarded-For", "127.0.0.1"}, new String[]{"X-Real-IP", "::1"},
                new String[]{"Forwarded", "for=127.0.0.1"}, new String[]{"Forwarded", "for=\"[::1]\""});
        for (String[] claim : claims) {
            MockHttpServletResponse response = perform(items(token).with(peer("127.0.0.1")).header(claim[0], claim[1]));
            assertEquals(403, response.getStatus(), claim[0] + ": " + claim[1] + " → " + response.getContentAsString());
            assertError(response, "source_not_allowed");
            assertEquals(403, perform(me(token).with(peer("::1")).header(claim[0], claim[1])).getStatus(), claim[0]);
        }
        properties.getConfigUi().setApiAnySourceGroups(List.of("parcel"));
        assertEquals(200, perform(items(token).with(peer("127.0.0.1")).header("X-Forwarded-For", "127.0.0.1")).getStatus(),
                "开关开着时照常过");
    }

    // 抓的故障：转发头认不出地址的请求，回包还叫人去看控制台名单。
    @Test
    void unknownVisitorRefusalSpeaksOfLocalOnly() throws Exception {
        String said = message(perform(items(issue()).with(peer("127.0.0.1")).header("X-Forwarded-For", "garbage")));
        assertTrue(said.contains("当作本机以外的来源") && !said.contains("名单"), said);
    }

    private String issue() {
        return tokens.issue(ConfigUiAccount.LOCAL_ID, "脚本", List.of("parcel:read"), null).plaintext();
    }

    private static MockHttpServletRequestBuilder items(String token) {
        return get("/nova/api/v1/parcel/items").header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static MockHttpServletRequestBuilder me(String token) {
        return get("/nova/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static RequestPostProcessor peer(String address) {
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
