package org.frostnova.nova.core.api;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConsoleAllowList;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccountStore;
import org.frostnova.nova.core.config.ui.auth.oidc.OidcClient;
import org.frostnova.nova.core.service.NovaStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 按接口组对任何来源地址开放：开放只免去来源名单这一道
 */
class NovaApiOpenGroupsFilterTest {
    private static final String OUTSIDER = "203.0.113.9";

    @TempDir
    Path directory;

    private NovaCoreProperties properties;

    private ConfigUiAccountStore accounts;

    private NovaApiTokenStore tokens;

    private final NovaApiTokenStoreTest.TestClock clock = new NovaApiTokenStoreTest.TestClock();

    private MockMvc mvc;

    /** 要开放的那一组 */
    static final class OutboxLike implements NovaApiProvider {
        @Override
        public String segment() {
            return "parcel";
        }

        @Override
        public String name() {
            return "取件";
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

    /** 没开放的另一组，其中一项范围仅管理员 */
    static final class Ops implements NovaApiProvider {
        @Override
        public String segment() {
            return "ops";
        }

        @Override
        public List<NovaApiScope> scopes() {
            return List.of(new NovaApiScope("ops:read", "读取运行", "测试用"),
                    NovaApiScope.adminOnly("ops:restart", "重启推送", "测试用"));
        }

        @Override
        public List<NovaApiRoute> routes() {
            return List.of(NovaApiRoute.get("status", "ops:read", request -> JSONObject.of("up", true)),
                    NovaApiRoute.post("restart", "ops:restart", request -> JSONObject.of("restarted", true)));
        }
    }

    @BeforeEach
    void api() {
        properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(directory.resolve("live.json").toString());
        NovaStateStore state = new NovaStateStore(properties);
        accounts = new ConfigUiAccountStore(state);
        tokens = new NovaApiTokenStore(state, clock);
        rebuild(new NovaApiThrottle(100, Duration.ofMinutes(1), clock));
    }

    private void rebuild(NovaApiThrottle throttle) {
        NovaApiRegistry registry = new NovaApiRegistry(List.of(new OutboxLike(), new Ops()));
        NovaApiFilter filter = new NovaApiFilter(new ConsoleAllowList(properties.getConfigUi()),
                new NovaApiOpenGroups(properties.getConfigUi()::getApiAnySourceGroups, registry), tokens, accounts,
                () -> true, registry, throttle, () -> List.of("bilibili"), List::of);
        mvc = MockMvcBuilders.standaloneSetup().addFilters(filter).build();
    }

    // 抓的故障：新装或升级后什么都没改，名单以外的地址就能调接口；或者被拒时只回一句「不在名单里」，使用者不知道该去哪里打开。
    @Test
    void nothingIsOpenByDefaultAndTheRefusalSaysWhereToOpenIt() throws Exception {
        assertTrue(properties.getConfigUi().getApiAnySourceGroups().isEmpty(), "默认清单为空");
        String token = issue(ConfigUiAccount.LOCAL_ID, List.of("parcel:read"));
        assertEquals(200, perform(call(token, "/nova/api/v1/parcel/items")).getStatus(), "本机直连照常可用");

        MockHttpServletResponse refused = perform(call(token, "/nova/api/v1/parcel/items").with(peer(OUTSIDER)));
        assertEquals(403, refused.getStatus());
        assertError(refused, "source_not_allowed");
        String message = message(refused);
        assertTrue(message.contains("没有对名单以外的地址开放") && message.contains("「访问令牌」页"), message);
        assertTrue(message.contains("parcel"), "已登记的组名写出来，好对上页面上那一行: " + message);

        // 请求路径里没登记的东西不回显
        MockHttpServletResponse unknown = perform(call(token, "/nova/api/v1/zz-not-a-group/x").with(peer(OUTSIDER)));
        assertEquals(403, unknown.getStatus());
        assertFalse(message(unknown).contains("zz-not-a-group"), message(unknown));
    }

    // 抓的故障：打开了外发箱，别的接口组也跟着对外开了；或者打开之后外面的人连自查接口 /me 都调不了。
    @Test
    void openingOneGroupOpensThatGroupAndMeOnly() throws Exception {
        properties.getConfigUi().setApiAnySourceGroups(List.of("parcel"));
        String token = issue(ConfigUiAccount.LOCAL_ID, List.of("parcel:read", "ops:read"));
        assertEquals(200, perform(call(token, "/nova/api/v1/parcel/items").with(peer(OUTSIDER))).getStatus());
        assertEquals(200, perform(call(token, "/nova/api/v1/me").with(peer(OUTSIDER))).getStatus());

        MockHttpServletResponse other = perform(call(token, "/nova/api/v1/ops/status").with(peer(OUTSIDER)));
        assertEquals(403, other.getStatus(), "另一组没开放");
        assertError(other, "source_not_allowed");
        assertEquals(403, perform(call(token, "/nova/api/v1/nothing/here").with(peer(OUTSIDER))).getStatus());
        assertEquals(403, perform(call(token, "/nova/api/v1").with(peer(OUTSIDER))).getStatus());
        assertEquals(403, perform(call(token, "/nova/api/v2/parcel/items").with(peer(OUTSIDER))).getStatus());
        assertEquals(200, perform(call(token, "/nova/api/v1/ops/status")).getStatus(), "本机照常调得了另一组");
    }

    // 抓的故障：开了开关就等于不用令牌；或者乱试令牌的人在开放的组上不再被限速。
    @Test
    void openGroupsStillNeedAValidTokenAndCountInvalidOnes() throws Exception {
        rebuild(new NovaApiThrottle(3, Duration.ofMinutes(1), clock));
        properties.getConfigUi().setApiAnySourceGroups(List.of("parcel"));
        String valid = issue(ConfigUiAccount.LOCAL_ID, List.of("parcel:read"));

        MockHttpServletResponse bare = perform(get("/nova/api/v1/parcel/items").with(peer(OUTSIDER)));
        assertEquals(401, bare.getStatus());
        assertError(bare, "unauthenticated");
        for (int i = 0; i < 3; i++) {
            assertEquals(401, perform(call("nvb_wrong-" + i, "/nova/api/v1/parcel/items").with(peer(OUTSIDER))).getStatus());
        }
        MockHttpServletResponse limited = perform(call("nvb_wrong-again", "/nova/api/v1/parcel/items").with(peer(OUTSIDER)));
        assertEquals(429, limited.getStatus());
        assertError(limited, "too_many_requests");
        assertEquals(200, perform(call(valid, "/nova/api/v1/parcel/items").with(peer(OUTSIDER))).getStatus(),
                "被限的来源出示有效令牌照常可用");
        assertEquals(401, perform(call("nvb_wrong-x", "/nova/api/v1/parcel/items").with(peer("198.51.100.3"))).getStatus(),
                "另一个来源不受连累");
    }

    // 抓的故障：开放之后，令牌没勾的范围、仅管理员的范围、路径写法检查都被一并免掉了。
    @Test
    void scopesAdminOnlyScopesAndPathChecksStillApply() throws Exception {
        properties.getConfigUi().setApiAnySourceGroups(List.of("parcel", "ops"));
        String noScope = issue(ConfigUiAccount.LOCAL_ID, List.of());
        MockHttpServletResponse scope = perform(call(noScope, "/nova/api/v1/parcel/items").with(peer(OUTSIDER)));
        assertEquals(403, scope.getStatus());
        assertError(scope, "insufficient_scope");

        var admin = accounts.role(member("subject-1", "管理员甲").id(), ConfigUiAccount.Role.ADMIN);
        String adminToken = issue(admin.id(), List.of("ops:restart"));
        accounts.role(admin.id(), ConfigUiAccount.Role.MEMBER);
        MockHttpServletResponse demoted = perform(post("/nova/api/v1/ops/restart")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).with(peer(OUTSIDER)));
        assertEquals(403, demoted.getStatus());
        assertError(demoted, "forbidden");

        String token = issue(ConfigUiAccount.LOCAL_ID, List.of("parcel:read"));
        for (String path : List.of("/nova/api/v1/parcel/%2e%2e/me", "/nova/api/v1/parcel;x=1/items", "/nova/api/v1/parcel/../ops/status")) {
            MockHttpServletResponse bad = perform(get(URI.create(path)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .with(peer(OUTSIDER)));
            assertEquals(400, bad.getStatus(), path);
        }
    }

    // 抓的故障：清单里写了不存在的组名（插件卸了、写错了），程序报错，或者连带把 /me 开了出去。
    @Test
    void unregisteredNamesInTheListAreIgnored() throws Exception {
        String token = issue(ConfigUiAccount.LOCAL_ID, List.of("parcel:read"));
        properties.getConfigUi().setApiAnySourceGroups(List.of("nothing", "Parcel", "me", " "));
        assertEquals(403, perform(call(token, "/nova/api/v1/me").with(peer(OUTSIDER))).getStatus(),
                "没有一组真的开放，/me 也不开");
        assertEquals(403, perform(call(token, "/nova/api/v1/parcel/items").with(peer(OUTSIDER))).getStatus());

        properties.getConfigUi().setApiAnySourceGroups(List.of("nothing", "parcel"));
        assertEquals(200, perform(call(token, "/nova/api/v1/parcel/items").with(peer(OUTSIDER))).getStatus(), "其余照常");
    }

    // 抓的故障：关掉开关后外网还能继续调，要等重启才拦得住。
    @Test
    void closingTakesEffectOnTheNextRequest() throws Exception {
        String token = issue(ConfigUiAccount.LOCAL_ID, List.of("parcel:read"));
        properties.getConfigUi().setApiAnySourceGroups(List.of("parcel"));
        assertEquals(200, perform(call(token, "/nova/api/v1/parcel/items").with(peer(OUTSIDER))).getStatus());
        properties.getConfigUi().setApiAnySourceGroups(List.of());
        assertEquals(403, perform(call(token, "/nova/api/v1/parcel/items").with(peer(OUTSIDER))).getStatus());
        assertEquals(403, perform(call(token, "/nova/api/v1/me").with(peer(OUTSIDER))).getStatus());
    }

    // 抓的故障：开放之后，吊销、过期、账号停用的令牌又能用了。
    @Test
    void revokedExpiredOrDisabledTokensStayRefusedWhenOpen() throws Exception {
        properties.getConfigUi().setApiAnySourceGroups(List.of("parcel"));
        var member = member("subject-2", "成员乙");
        String revoked = issue(member.id(), List.of("parcel:read"));
        String expiring = tokens.issue(member.id(), "短期", List.of("parcel:read"), Duration.ofDays(30)).plaintext();
        String disabled = issue(member.id(), List.of("parcel:read"));
        tokens.revoke(tokens.match(revoked).orElseThrow().id());
        assertEquals(401, perform(call(revoked, "/nova/api/v1/parcel/items").with(peer(OUTSIDER))).getStatus());
        clock.now = clock.now.plus(Duration.ofDays(30));
        assertEquals(401, perform(call(expiring, "/nova/api/v1/parcel/items").with(peer(OUTSIDER))).getStatus());
        accounts.status(member.id(), ConfigUiAccount.Status.DISABLED);
        assertEquals(401, perform(call(disabled, "/nova/api/v1/parcel/items").with(peer(OUTSIDER))).getStatus());
    }

    // 抓的故障：经本机反向代理进来的人，开了开关也调不了；或者转发头乱写的人借开关绕过了令牌。
    @Test
    void proxiedVisitorsFollowTheSameRules() throws Exception {
        properties.getConfigUi().setApiAnySourceGroups(List.of("parcel"));
        String token = issue(ConfigUiAccount.LOCAL_ID, List.of("parcel:read", "ops:read"));
        assertEquals(200, perform(call(token, "/nova/api/v1/parcel/items").header("X-Forwarded-For", OUTSIDER)).getStatus());
        assertEquals(403, perform(call(token, "/nova/api/v1/ops/status").header("X-Forwarded-For", OUTSIDER)).getStatus());

        assertEquals(200, perform(call(token, "/nova/api/v1/parcel/items").header("X-Forwarded-For", "garbage")).getStatus(),
                "认不出来访地址的当作名单以外的来访者：组开放着，凭有效令牌可用");
        assertEquals(401, perform(get("/nova/api/v1/parcel/items").header("X-Forwarded-For", "garbage")).getStatus());
        assertEquals(403, perform(call(token, "/nova/api/v1/ops/status").header("X-Forwarded-For", "garbage")).getStatus());
    }

    private ConfigUiAccount member(String subject, String name) {
        var account = accounts.register(new OidcClient.Identity("https://accounts.example.com/tenant", subject, name, null),
                Instant.now());
        return accounts.status(account.id(), ConfigUiAccount.Status.ENABLED);
    }

    private String issue(String accountId, List<String> scopes) {
        return tokens.issue(accountId, "脚本", scopes, null).plaintext();
    }

    private static MockHttpServletRequestBuilder call(String token, String path) {
        return get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
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
