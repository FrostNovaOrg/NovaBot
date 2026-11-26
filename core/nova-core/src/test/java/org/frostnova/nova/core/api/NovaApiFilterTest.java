package org.frostnova.nova.core.api;

import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.http.Cookie;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigUiSecurityFilter;
import org.frostnova.nova.core.config.ui.ConsoleAllowList;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccountStore;
import org.frostnova.nova.core.config.ui.auth.oidc.OidcClient;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.web.SecurityHeadersFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * 统一接口面 {@code /nova/api/v1/} 的门：情形表逐行
 */
class NovaApiFilterTest {
    private static final String ISSUER = "https://accounts.example.com/tenant";

    @TempDir
    Path directory;

    private NovaCoreProperties properties;

    private ConfigUiAccountStore accounts;

    private NovaApiTokenStore tokens;

    private final NovaApiTokenStoreTest.TestClock clock = new NovaApiTokenStoreTest.TestClock();

    private final List<NovaApiStreamer> roster = new ArrayList<>();

    /** 成员此刻能不能登录控制台；每个请求现读 */
    private volatile boolean membersCanSignIn = true;

    private MockMvc mvc;

    /** 不该被走到的 MVC 处理器：这个前缀下的请求从不交给后面的链 */
    @RestController
    static class Probe {
        @GetMapping("/nova/api/v1/probe/leak")
        String leak() {
            return "reached-mvc";
        }
    }

    /** 测试用插件：一项范围、几个接口 */
    static final class ProbeApi implements NovaApiProvider {
        @Override
        public String segment() {
            return "probe";
        }

        @Override
        public List<NovaApiScope> scopes() {
            return List.of(new NovaApiScope("probe:read", "读取探针", "测试用"),
                    new NovaApiScope("probe:write", "写入探针", "测试用"));
        }

        @Override
        public List<NovaApiRoute> routes() {
            return List.of(
                    NovaApiRoute.get("items", "probe:read", request -> NovaApiPage.last(List.of(
                            JSONObject.of("n", 1), JSONObject.of("n", 2)))),
                    NovaApiRoute.get("streamers/{platform}/{uid}", "probe:read", request -> {
                        if (!request.caller().canSee(request.path("platform"), Long.parseLong(request.path("uid")))) {
                            throw NovaApiException.notFound("没有这位主播");
                        }
                        return JSONObject.of("uid", request.path("uid"));
                    }),
                    NovaApiRoute.post("echo", "probe:write", request -> JSONObject.of("got", request.body().get("value"))),
                    NovaApiRoute.get("boom", "probe:read", request -> {
                        throw new IllegalStateException("内部细节 should-not-leak");
                    }),
                    NovaApiRoute.get("conflict", "probe:read", request -> {
                        throw NovaApiException.conflict("对象状态已变");
                    }));
        }
    }

    /** 测试用插件：一项仅管理员的范围，管的是整个实例 */
    static final class OpsApi implements NovaApiProvider {
        @Override
        public String segment() {
            return "ops";
        }

        @Override
        public List<NovaApiScope> scopes() {
            return List.of(NovaApiScope.adminOnly("ops:restart", "重启推送", "测试用"));
        }

        @Override
        public List<NovaApiRoute> routes() {
            return List.of(NovaApiRoute.post("restart", "ops:restart", request -> JSONObject.of("restarted", true)));
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
        NovaApiFilter filter = new NovaApiFilter(new ConsoleAllowList(properties.getConfigUi()), tokens, accounts,
                () -> membersCanSignIn, new NovaApiRegistry(List.of(new ProbeApi(), new OpsApi())), throttle,
                () -> List.of("bilibili"), () -> List.copyOf(roster));
        mvc = MockMvcBuilders.standaloneSetup(new Probe())
                .addFilters(new SecurityHeadersFilter(List.of("/config", "/nova")), filter).build();
    }

    // ———— 不带令牌 / 令牌不存在 ————

    // 抓的故障：没带令牌或拿一串乱码也能调到接口。
    @Test
    void missingOrUnknownTokenIs401Unauthenticated() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(get("/nova/api/v1/me"),
                get("/nova/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer nvb_not-a-real-token"),
                get("/nova/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz"),
                get("/nova/api/v1/probe/items"))) {
            MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
            assertEquals(401, response.getStatus());
            assertError(response, "unauthenticated");
            assertTrue(response.getHeader(HttpHeaders.WWW_AUTHENTICATE).startsWith("Bearer"));
        }
    }

    // ———— 已吊销 / 已过期 / 账号停用 / 账号删除 ————

    // 抓的故障：吊销之后、过期之后，或者账号被停用、删除之后，令牌还能用。
    @Test
    void revokedExpiredDisabledOrDeletedAccountIs401() throws Exception {
        var member = member("subject-1", "成员甲");
        String revoked = issue(member.id(), null);
        String expiring = tokens.issue(member.id(), "短期", List.of(), Duration.ofDays(30)).plaintext();
        assertEquals(200, me(revoked).getStatus());
        assertEquals(200, me(expiring).getStatus());

        tokens.revoke(tokens.match(revoked).orElseThrow().id());
        assertUnauthenticated(me(revoked));

        clock.now = clock.now.plus(Duration.ofDays(30));
        MockHttpServletResponse expired = me(expiring);
        assertUnauthenticated(expired);
        assertTrue(message(expired).contains("过期"), message(expired));

        String live = issue(member.id(), null);
        assertEquals(200, me(live).getStatus());
        accounts.status(member.id(), ConfigUiAccount.Status.DISABLED);
        assertUnauthenticated(me(live));

        accounts.delete(member.id());
        assertUnauthenticated(me(live));
    }

    // ———— 范围 ————

    // 抓的故障：令牌没勾这一项范围，照样调得动要它的接口。
    @Test
    void validTokenWithoutTheRequiredScopeIs403() throws Exception {
        String readOnly = issue(ConfigUiAccount.LOCAL_ID, List.of("probe:read"));
        MockHttpServletResponse items = mvc.perform(get("/nova/api/v1/probe/items").header(HttpHeaders.AUTHORIZATION,
                "Bearer " + readOnly)).andReturn().getResponse();
        assertEquals(200, items.getStatus());

        MockHttpServletResponse write = mvc.perform(post("/nova/api/v1/probe/echo").content("{\"value\":1}")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + readOnly)).andReturn().getResponse();
        assertEquals(403, write.getStatus());
        assertError(write, "insufficient_scope");
        assertTrue(message(write).contains("写入探针"), message(write));

        String writer = issue(ConfigUiAccount.LOCAL_ID, List.of("probe:write"));
        MockHttpServletResponse echoed = mvc.perform(post("/nova/api/v1/probe/echo").content("{\"value\":7}")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + writer)).andReturn().getResponse();
        assertEquals(200, echoed.getStatus());
        assertEquals(7, body(echoed).getJSONObject("data").getIntValue("got"));
    }

    // ———— 来源 IP ————

    // 抓的故障：控制台只放行本机，却能从别的机器调这个前缀；或者改了名单要重启才生效。
    @Test
    void sourceOutsideTheConsoleAllowListIsRefusedTheSameWay() throws Exception {
        String token = issue(ConfigUiAccount.LOCAL_ID, null);
        MockHttpServletResponse outside = mvc.perform(get("/nova/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .with(request -> { request.setRemoteAddr("203.0.113.9"); return request; })).andReturn().getResponse();
        assertEquals(403, outside.getStatus());
        assertError(outside, "source_not_allowed");

        // 与控制台同一段判法：同一个请求，控制台那边的判定也是拒
        var probe = new org.springframework.mock.web.MockHttpServletRequest("GET", "/config");
        probe.setRemoteAddr("203.0.113.9");
        assertFalse(ConfigUiSecurityFilter.admits(new ConsoleAllowList(properties.getConfigUi()).get(), probe));

        // 名单每个请求现读
        properties.getConfigUi().setAllowIps(List.of("203.0.113.0/24"));
        assertEquals(200, mvc.perform(get("/nova/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .with(request -> { request.setRemoteAddr("203.0.113.9"); return request; })).andReturn().getResponse().getStatus());
        assertEquals(403, me(token).getStatus(), "名单改了之后本机不在里面，就该拒本机");
    }

    // ———— 成员的令牌调 /me ————

    // 抓的故障：成员的令牌看到了别人的主播。
    @Test
    void memberTokenListsOnlyOwnStreamers() throws Exception {
        roster.addAll(List.of(new NovaApiStreamer("bilibili", 100, "主播甲"),
                new NovaApiStreamer("bilibili", 200, "主播乙"),
                new NovaApiStreamer("bilibili", 300, null)));
        var member = accounts.streamers(member("subject-1", "成员甲").id(), List.of("bilibili:100"));
        String token = issue(member.id(), null);

        JSONObject data = body(me(token)).getJSONObject("data");
        assertEquals("MEMBER", data.getJSONObject("account").getString("role"));
        assertEquals("成员甲", data.getJSONObject("account").getString("displayName"));
        assertEquals(List.of(100L), uids(data));
        assertEquals("主播甲", data.getJSONArray("streamers").getJSONObject(0).getString("name"));

        // 插件拿到的判断方法同一个口径
        assertEquals(200, mvc.perform(get("/nova/api/v1/probe/streamers/bilibili/100")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + issue(member.id(), List.of("probe:read"))))
                .andReturn().getResponse().getStatus());
        assertEquals(404, mvc.perform(get("/nova/api/v1/probe/streamers/bilibili/200")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + issue(member.id(), List.of("probe:read"))))
                .andReturn().getResponse().getStatus());

        // 管理员全部可见
        JSONObject admin = body(me(issue(ConfigUiAccount.LOCAL_ID, null))).getJSONObject("data");
        assertEquals(List.of(100L, 200L, 300L), uids(admin));
        assertEquals("ADMIN", admin.getJSONObject("account").getString("role"));
    }

    // ———— 管理员刚改了成员名下的主播 ————

    // 抓的故障：管理员收回了成员的主播，成员的令牌还能接着看那位主播。
    @Test
    void streamerReassignmentAppliesToTheNextRequest() throws Exception {
        roster.addAll(List.of(new NovaApiStreamer("bilibili", 100, "主播甲"),
                new NovaApiStreamer("bilibili", 200, "主播乙")));
        var member = accounts.streamers(member("subject-1", "成员甲").id(), List.of("bilibili:100"));
        String token = issue(member.id(), null);
        assertEquals(List.of(100L), uids(body(me(token)).getJSONObject("data")));

        accounts.streamers(member.id(), List.of("bilibili:200"));
        assertEquals(List.of(200L), uids(body(me(token)).getJSONObject("data")));

        accounts.role(member.id(), ConfigUiAccount.Role.ADMIN);
        assertEquals("ADMIN", body(me(token)).getJSONObject("data").getJSONObject("account").getString("role"));
    }

    // ———— 成员此刻登不进控制台 ————

    // 抓的故障：管理员关掉成员登录后，成员手里的旧令牌还能取数据；或者重新开放登录后，成员得重签才能用。
    @Test
    void memberTokensStopWhileMembersCannotSignInAndResumeAfterwards() throws Exception {
        var member = member("subject-1", "成员甲");
        String memberToken = issue(member.id(), List.of("probe:read"));
        String adminToken = issue(ConfigUiAccount.LOCAL_ID, List.of("probe:read"));
        var other = member("subject-2", "成员乙");
        String disabledToken = issue(other.id(), null);
        accounts.status(other.id(), ConfigUiAccount.Status.DISABLED);
        assertEquals(200, me(memberToken).getStatus());

        membersCanSignIn = false;
        MockHttpServletResponse closed = me(memberToken);
        assertUnauthenticated(closed);
        assertTrue(closed.getHeader(HttpHeaders.WWW_AUTHENTICATE).startsWith("Bearer"));
        assertUnauthenticated(call(memberToken, "/nova/api/v1/probe/items"));
        // 与别的无效令牌同一回包：不另给一种说法
        assertEquals(me(disabledToken).getContentAsString(), closed.getContentAsString());
        assertEquals(200, me(adminToken).getStatus(), "管理员的令牌不受成员登录开关影响");
        assertEquals(200, call(adminToken, "/nova/api/v1/probe/items").getStatus());

        membersCanSignIn = true;
        assertEquals(200, me(memberToken).getStatus(), "开回来后下一个请求就该恢复，不用重签");
        assertEquals(200, call(memberToken, "/nova/api/v1/probe/items").getStatus());
    }

    // ———— 仅管理员的范围 ————

    // 抓的故障：被降成成员的人还能用旧令牌做管理员的操作。
    @Test
    void adminOnlyScopeFollowsTheAccountsCurrentRole() throws Exception {
        var admin = accounts.role(member("subject-1", "管理员甲").id(), ConfigUiAccount.Role.ADMIN);
        String token = issue(admin.id(), List.of("ops:restart", "probe:read"));
        assertEquals(200, send(token, "/nova/api/v1/ops/restart").getStatus());

        accounts.role(admin.id(), ConfigUiAccount.Role.MEMBER);
        MockHttpServletResponse demoted = send(token, "/nova/api/v1/ops/restart");
        assertEquals(403, demoted.getStatus(), demoted.getContentAsString());
        assertError(demoted, "forbidden");
        assertTrue(message(demoted).contains("管理员"), message(demoted));
        assertEquals(200, call(token, "/nova/api/v1/probe/items").getStatus(), "别的范围不受影响");
        assertEquals(200, me(token).getStatus());

        accounts.role(admin.id(), ConfigUiAccount.Role.ADMIN);
        assertEquals(200, send(token, "/nova/api/v1/ops/restart").getStatus(), "改回管理员后下一个请求就按管理员算");
    }

    // ———— 解码之后的路径段 ————

    // 抓的故障：编码过的 `..`、`.`，或解码出斜杠、反斜杠、控制字符的段进了处理器。
    @Test
    void decodedDotSegmentsSeparatorsAndControlCharactersAre400() throws Exception {
        String token = issue(ConfigUiAccount.LOCAL_ID, List.of("probe:read"));
        assertEquals(200, call(token, "/nova/api/v1/probe/streamers/bilibili/100").getStatus(), "对照：正常写法能到处理器");
        for (String path : List.of("/nova/api/v1/probe/%2e%2e/me", "/nova/api/v1/%2E/me", "/nova/api/v1/probe/.%2e/items",
                "/nova/api/v1/probe/streamers/%2e%2e/100", "/nova/api/v1/probe/streamers/%2E/100",
                "/nova/api/v1/probe/streamers/bili%2Fbili/100", "/nova/api/v1/probe/streamers/bili%5cbili/100",
                "/nova/api/v1/probe/streamers/bili%0Abili/100", "/nova/api/v1/probe/streamers/bili%00/100",
                "/nova/api/v1/probe/streamers/bili%7f/100", "/nova/api/v1/probe/streamers/bili%C2%85/100")) {
            MockHttpServletResponse signed = mvc.perform(get(java.net.URI.create(path))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn().getResponse();
            assertEquals(400, signed.getStatus(), path + " " + signed.getContentAsString());
            assertError(signed, "bad_request");
            MockHttpServletResponse anonymous = mvc.perform(get(java.net.URI.create(path))).andReturn().getResponse();
            assertEquals(400, anonymous.getStatus(), path);
        }
    }

    // ———— IPv6 来源 ————

    // 抓的故障：同一个 IPv6 /64 里换着地址交无效令牌，每个地址各算各的，限速形同虚设。
    @Test
    void addressesInOneIpv6Slash64ShareTheInvalidTokenCount() throws Exception {
        properties.getConfigUi().setAllowIps(List.of("2001:db8::/32"));
        rebuild(new NovaApiThrottle(3, Duration.ofMinutes(1), clock));
        String valid = issue(ConfigUiAccount.LOCAL_ID, null);
        for (int i = 1; i <= 3; i++) {
            assertEquals(401, from("2001:db8:1:2::" + i, "nvb_wrong-" + i).getStatus());
        }
        MockHttpServletResponse limited = from("2001:db8:1:2:ffff:ffff:ffff:fffe", "nvb_wrong-4");
        assertEquals(429, limited.getStatus(), limited.getContentAsString());
        assertError(limited, "too_many_requests");
        assertEquals(200, from("2001:db8:1:2::99", valid).getStatus(), "被限的来源出示有效令牌照常可用");
        assertEquals(401, from("2001:db8:1:3::1", "nvb_wrong-next-door").getStatus(), "隔壁的 /64 不受连累");
    }

    // ———— 只带控制台会话 Cookie / 地址栏参数 ————

    // 抓的故障：浏览器里登着控制台，任何网页都能借那个 Cookie 调这个前缀；或者令牌写在地址栏里也被认。
    @Test
    void sessionCookieOrQueryParameterIsNotACredential() throws Exception {
        String token = issue(ConfigUiAccount.LOCAL_ID, null);
        MockHttpServletResponse cookie = mvc.perform(get("/nova/api/v1/me")
                .cookie(new Cookie(ConfigUiSecurityFilter.SESSION_COOKIE, "some-session"),
                        new Cookie("starbot_config_token", token))).andReturn().getResponse();
        assertUnauthenticated(cookie);

        MockHttpServletResponse query = mvc.perform(get("/nova/api/v1/me").param("token", token)
                .param("access_token", token)).andReturn().getResponse();
        assertUnauthenticated(query);

        MockHttpServletResponse other = mvc.perform(get("/nova/api/v1/me").header("X-Access-Token", token))
                .andReturn().getResponse();
        assertUnauthenticated(other);
    }

    // ———— 同一来源连续出示无效令牌 ————

    // 抓的故障：脚本写错令牌狂刷时没有任何限制；或者一限速，同一台机器上拿着正确令牌的程序也一起被挡住。
    // 控制台登录不被这道限速锁住，由 ConfigUiApiTokenControllerTest 在同一条链上量。
    @Test
    void repeatedInvalidTokensAre429WithoutBlockingValidTokens() throws Exception {
        rebuild(new NovaApiThrottle(3, Duration.ofMinutes(1), clock));
        String valid = issue(ConfigUiAccount.LOCAL_ID, null);
        for (int i = 0; i < 3; i++) {
            assertEquals(401, me("nvb_wrong-" + i).getStatus());
        }
        MockHttpServletResponse limited = me("nvb_wrong-again");
        assertEquals(429, limited.getStatus());
        assertError(limited, "too_many_requests");
        assertNotNull(limited.getHeader(HttpHeaders.RETRY_AFTER));

        // 同一来源，有效令牌照常
        assertEquals(200, me(valid).getStatus());
        assertEquals(200, me(valid).getStatus());
        // 别的来源不受连累
        assertEquals(401, mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/nova/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer nvb_wrong-elsewhere")
                .with(request -> { request.setRemoteAddr("::1"); return request; })).andReturn().getResponse().getStatus());

        // 窗口过了自动恢复为 401
        clock.now = clock.now.plus(Duration.ofMinutes(1));
        assertEquals(401, me("nvb_wrong-later").getStatus());
    }

    // 抓的故障：真实部署里装配的那一份限速阈值是 0 或无穷大。
    @Test
    void defaultThrottleLimitsAfterTenInvalidTokens() throws Exception {
        rebuild(new NovaApiThrottle(NovaApiThrottle.DEFAULT_MAX_FAILURES, NovaApiThrottle.DEFAULT_WINDOW, clock));
        for (int i = 0; i < 10; i++) {
            assertEquals(401, me("nvb_wrong-" + i).getStatus());
        }
        assertEquals(429, me("nvb_wrong-10").getStatus());
    }

    // ———— 统一返回格式与安全头 ————

    // 抓的故障：成功和失败长成两种样子、列表没有 next、响应被浏览器或代理缓存下来、出现跨域放行头。
    @Test
    void envelopeShapeAndHeaders() throws Exception {
        String token = issue(ConfigUiAccount.LOCAL_ID, List.of("probe:read"));
        MockHttpServletResponse items = mvc.perform(get("/nova/api/v1/probe/items")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header(HttpHeaders.ORIGIN, "https://elsewhere.example"))
                .andReturn().getResponse();
        assertEquals(200, items.getStatus());
        assertTrue(items.getContentType().startsWith("application/json"));
        JSONObject json = body(items);
        assertEquals(true, json.get("ok"));
        assertEquals(2, json.getJSONObject("data").getJSONArray("items").size());
        assertTrue(json.getJSONObject("data").containsKey("next"), "没有下一页时 next 也要在");
        assertNull(json.getJSONObject("data").get("next"));
        assertEquals("no-store", items.getHeader(HttpHeaders.CACHE_CONTROL));
        assertEquals("nosniff", items.getHeader("X-Content-Type-Options"));
        assertNull(items.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));

        MockHttpServletResponse failed = me("nvb_nope");
        assertEquals("no-store", failed.getHeader(HttpHeaders.CACHE_CONTROL));
        assertEquals(false, body(failed).get("ok"));
        assertFalse(body(failed).containsKey("data"));

        MockHttpServletResponse preflight = mvc.perform(options("/nova/api/v1/me")
                .header(HttpHeaders.ORIGIN, "https://elsewhere.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")).andReturn().getResponse();
        assertEquals(401, preflight.getStatus());
        assertNull(preflight.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    // 抓的故障：插件出错时把异常原文回给调用方；地址写错时回一张 HTML 或空白。
    @Test
    void errorsAreWrappedWithStableCodes() throws Exception {
        String token = issue(ConfigUiAccount.LOCAL_ID, List.of("probe:read"));
        MockHttpServletResponse boom = call(token, "/nova/api/v1/probe/boom");
        assertEquals(500, boom.getStatus());
        assertError(boom, "internal_error");
        assertFalse(boom.getContentAsString().contains("should-not-leak"));

        MockHttpServletResponse conflict = call(token, "/nova/api/v1/probe/conflict");
        assertEquals(409, conflict.getStatus());
        assertError(conflict, "conflict");

        for (String path : List.of("/nova/api/v1/nothing", "/nova/api/v1/probe/missing", "/nova/api/v1/me/",
                "/nova/api/v2/me", "/nova/api", "/nova/api/v1")) {
            MockHttpServletResponse missing = call(token, path);
            assertEquals(404, missing.getStatus(), path);
            assertError(missing, "not_found");
        }

        MockHttpServletResponse method = mvc.perform(post("/nova/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse();
        assertEquals(405, method.getStatus());
        assertError(method, "method_not_allowed");

        MockHttpServletResponse badBody = mvc.perform(post("/nova/api/v1/probe/echo").content("[1,2")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + issue(ConfigUiAccount.LOCAL_ID, List.of("probe:write"))))
                .andReturn().getResponse();
        assertEquals(400, badBody.getStatus());
        assertError(badBody, "bad_request");
    }

    // 抓的故障：这个前缀下的请求漏到了后面的 MVC 处理器，绕过了鉴权。
    @Test
    void requestsNeverFallThroughToMvc() throws Exception {
        String token = issue(ConfigUiAccount.LOCAL_ID, null);
        MockHttpServletResponse anonymous = mvc.perform(get("/nova/api/v1/probe/leak")).andReturn().getResponse();
        assertEquals(401, anonymous.getStatus());
        MockHttpServletResponse authenticated = call(token, "/nova/api/v1/probe/leak");
        assertEquals(404, authenticated.getStatus());
        assertFalse(authenticated.getContentAsString().contains("reached-mvc"));
    }

    // 抓的故障：路径写法变体（分号、编码斜杠、点段）绕过了前缀判断。
    @Test
    void pathVariantsAreRejected() throws Exception {
        for (String path : List.of("/nova/api/v1/me;x=1", "/nova/api/v1/probe%2Fitems", "/nova/api/v1/./me",
                "/nova/api/v1/probe/../me")) {
            MockHttpServletResponse response = mvc.perform(get(java.net.URI.create(path))).andReturn().getResponse();
            assertEquals(400, response.getStatus(), path);
            assertError(response, "bad_request");
        }
    }

    // 抓的故障：/me 里带出了令牌明文或摘要。
    @Test
    void meNeverEchoesTheTokenOrItsDigest() throws Exception {
        String token = issue(ConfigUiAccount.LOCAL_ID, List.of("probe:read"));
        String text = me(token).getContentAsString();
        assertFalse(text.contains(token));
        assertFalse(text.contains(NovaApiTokenStore.digest(token)));
        JSONObject data = JSONObject.parseObject(text).getJSONObject("data");
        assertEquals("脚本", data.getJSONObject("token").getString("name"));
        assertEquals(List.of("probe:read"), data.getJSONObject("token").getList("scopes", String.class));
        assertEquals("本地管理员", data.getJSONObject("account").getString("displayName"));
    }

    // ———— 插件登记口 ————

    // 抓的故障：一家插件登记写错了，整个程序起不来；或者它抢走了别家的段名、底座自己的 me。
    @Test
    void badRegistrationsAreSkippedOthersStayUsable() {
        NovaApiProvider undeclaredScope = provider("loose", List.of(),
                List.of(NovaApiRoute.get("x", "loose:read", request -> null)));
        NovaApiProvider reserved = provider("me", List.of(new NovaApiScope("me:read", "我", "")), List.of());
        NovaApiProvider duplicate = new ProbeApi();
        NovaApiProvider throwing = new NovaApiProvider() {
            @Override
            public String segment() {
                return "broken";
            }

            @Override
            public List<NovaApiRoute> routes() {
                return List.of(NovaApiRoute.get("/bad/", "broken:read", request -> null));
            }
        };
        var registry = new NovaApiRegistry(List.of(new ProbeApi(), undeclaredScope, reserved, duplicate, throwing));
        assertEquals(List.of("probe:read", "probe:write"), registry.scopes().stream().map(NovaApiScope::id).toList());
        assertInstanceOf(NovaApiRegistry.Found.class, registry.match("GET", List.of("probe", "items")));
        assertInstanceOf(NovaApiRegistry.Missing.class, registry.match("GET", List.of("loose", "x")));
        assertInstanceOf(NovaApiRegistry.WrongMethod.class, registry.match("POST", List.of("probe", "items")));
    }

    private static NovaApiProvider provider(String segment, List<NovaApiScope> scopes, List<NovaApiRoute> routes) {
        return new NovaApiProvider() {
            @Override
            public String segment() {
                return segment;
            }

            @Override
            public List<NovaApiScope> scopes() {
                return scopes;
            }

            @Override
            public List<NovaApiRoute> routes() {
                return routes;
            }
        };
    }

    private ConfigUiAccount member(String subject, String name) {
        var account = accounts.register(new OidcClient.Identity(ISSUER, subject, name, null), Instant.now());
        return accounts.status(account.id(), ConfigUiAccount.Status.ENABLED);
    }

    private String issue(String accountId, List<String> scopes) {
        return tokens.issue(accountId, "脚本", scopes == null ? List.of() : scopes, null).plaintext();
    }

    private MockHttpServletResponse me(String token) throws Exception {
        return call(token, "/nova/api/v1/me");
    }

    private MockHttpServletResponse call(String token, String path) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn().getResponse();
    }

    private MockHttpServletResponse send(String token, String path) throws Exception {
        return mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn().getResponse();
    }

    private MockHttpServletResponse from(String address, String token) throws Exception {
        return mvc.perform(get("/nova/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .with(request -> { request.setRemoteAddr(address); return request; })).andReturn().getResponse();
    }

    private static List<Long> uids(JSONObject data) {
        return data.getJSONArray("streamers").stream().map(o -> ((JSONObject) o).getLong("uid")).toList();
    }

    private static JSONObject body(MockHttpServletResponse response) throws Exception {
        return JSONObject.parseObject(response.getContentAsString());
    }

    private static String message(MockHttpServletResponse response) throws Exception {
        return body(response).getJSONObject("error").getString("message");
    }

    private static void assertError(MockHttpServletResponse response, String code) throws Exception {
        JSONObject json = body(response);
        assertEquals(false, json.get("ok"), response.getContentAsString());
        assertEquals(code, json.getJSONObject("error").getString("code"), response.getContentAsString());
        assertFalse(json.getJSONObject("error").getString("message").isBlank());
    }

    private static void assertUnauthenticated(MockHttpServletResponse response) throws Exception {
        assertEquals(401, response.getStatus(), response.getContentAsString());
        assertError(response, "unauthenticated");
    }
}
