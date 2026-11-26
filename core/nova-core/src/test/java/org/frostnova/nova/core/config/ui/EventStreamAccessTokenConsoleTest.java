package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.http.Cookie;
import org.frostnova.nova.core.api.EventStreamProbe;
import org.frostnova.nova.core.api.NovaApiEventStreamTokens;
import org.frostnova.nova.core.api.NovaApiFilter;
import org.frostnova.nova.core.api.NovaApiOpenGroups;
import org.frostnova.nova.core.api.NovaApiProvider;
import org.frostnova.nova.core.api.NovaApiRegistry;
import org.frostnova.nova.core.api.NovaApiRoute;
import org.frostnova.nova.core.api.NovaApiScope;
import org.frostnova.nova.core.api.NovaApiThrottle;
import org.frostnova.nova.core.api.NovaApiTokenStore;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccountStore;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSession;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSessionStore;
import org.frostnova.nova.core.config.ui.auth.LoginThrottle;
import org.frostnova.nova.core.config.ui.auth.LowIterationPasswordHash;
import org.frostnova.nova.core.config.ui.auth.oidc.OidcClient;
import org.frostnova.nova.core.config.ui.auth.oidc.OidcTestSupport;
import org.frostnova.nova.core.protocol.EventStreamTokenService;
import org.frostnova.nova.core.protocol.NovaEventEndpoint;
import org.frostnova.nova.core.protocol.NovaEventStream;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.util.IpMatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.socket.CloseStatus;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 在控制台里签发、吊销「读取事件流」的令牌，改账号的角色与状态，事件流跟着认或断
 */
class EventStreamAccessTokenConsoleTest extends OidcTestSupport {
    @TempDir
    Path directory;

    private ConfigUiAccountStore accounts;

    private ConfigUiSessionStore sessions;

    private ConfigUiAuthService auth;

    private NovaApiTokenStore tokens;

    private EventStreamTokenService readonly;

    private NovaEventEndpoint endpoint;

    private MockMvc mvc;

    /** 一组普通的接口，量「从别的机器访问」里列出了哪些组 */
    static final class OutboxLike implements NovaApiProvider {
        @Override
        public String segment() {
            return "outbox";
        }

        @Override
        public List<NovaApiScope> scopes() {
            return List.of(new NovaApiScope("outbox:read", "读取外发箱", "测试用"));
        }

        @Override
        public List<NovaApiRoute> routes() {
            return List.of(NovaApiRoute.get("messages", "outbox:read", request -> JSONObject.of("ok", true)));
        }
    }

    @BeforeEach
    void console() throws Exception {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(directory.resolve("live.json").toString());
        properties.getConfigUi().getAuth().setPassword(LowIterationPasswordHash.of("example password"));
        properties.getConfigUi().getAuth().setTotp(false);
        properties.getConfigUi().getAgreement().setAcceptedVersion(ConfigUiAgreement.VERSION);
        properties.getConfigUi().getAgreement().setAcceptedBy("password");
        var platforms = org.mockito.Mockito.mock(org.frostnova.nova.core.datasource.DataSourceServiceRegistry.class);
        org.mockito.Mockito.when(platforms.platforms()).thenReturn(List.of("bilibili"));
        NovaStateStore state = new NovaStateStore(properties);
        accounts = new ConfigUiAccountStore(state);
        sessions = new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(24));
        auth = new ConfigUiAuthService(properties.getConfigUi().getAuth(), sessions,
                new LoginThrottle(5, Duration.ofMinutes(15)), null);
        auth.setAccounts(accounts);
        auth.setOidcClient(client);
        tokens = new NovaApiTokenStore(state);
        NovaApiRegistry registry = new NovaApiRegistry(List.of(NovaApiEventStreamTokens.SCOPE), List.of(new OutboxLike()));
        var files = new ConfigurationFileService(directory.resolve("application.yml"));
        RuntimeConfigurationApplier applier = RuntimeConfigurationApplier.bench(properties).configFile(files).build();
        var environment = new MockEnvironment().withProperty("server.address", "127.0.0.1").withProperty("server.port", "7827");
        var interceptor = new ConfigUiApiInterceptor(auth, properties, platforms);
        var security = new ConfigUiSecurityFilter("test-operator-token", new IpMatcher(List.of("127.0.0.0/8")), auth,
                properties.getConfigUi().getAuth(), properties.getConfigUi().getAgreement(), () -> { });
        var api = new NovaApiFilter(new ConsoleAllowList(properties.getConfigUi()),
                new NovaApiOpenGroups(properties.getConfigUi()::getApiAnySourceGroups, registry), tokens, accounts,
                auth::memberSignInOpen, registry, new NovaApiThrottle(), platforms::platforms, List::of);
        mvc = MockMvcBuilders.standaloneSetup(
                        new ConfigUiApiTokenController(tokens, registry, accounts, auth::memberSignInOpen),
                        new ConfigUiAccountController(accounts, auth, client, platforms, tokens),
                        new ConfigUiApiRemoteAccessController(registry, properties, files, applier, environment,
                                () -> List.of("192.168.1.20")))
                .addInterceptors(interceptor)
                .addFilter(new ConfigUiOperationFilter(new ConfigUiOperationStore(properties)), "/config/*")
                .addFilter(security, "/config", "/config/*")
                .addFilter(api, "/nova/api", "/nova/api/*")
                .build();
        readonly = new EventStreamTokenService(properties.getLive());
        endpoint = new NovaEventEndpoint(new NovaEventStream(64), readonly, new NovaApiEventStreamTokens(tokens, accounts));
    }

    @AfterEach
    void stop() {
        endpoint.shutdown();
    }

    // 抓的故障：签发页上没有「读取事件流」可勾，或者没标仅管理员，成员以为插件没装好或者以为自己也能勾。
    @Test
    void signingPageListsTheScopeAsAdminOnlyForEveryone() throws Exception {
        for (ConfigUiSession viewer : List.of(auth.issueForPassword("127.0.0.1"), oidc(account("subject-m", "成员甲")))) {
            JSONObject scope = scope(body(console(get("/config/api/api-tokens"), viewer)), "events:read");
            assertEquals("读取事件流", scope.getString("name"));
            assertTrue(scope.getBooleanValue("adminOnly"));
            assertFalse(scope.getString("description").isBlank());
        }
    }

    // 抓的故障：「从别的机器访问」里多出一个事件流的开关，管理员以为打开它就能从外面连事件流（事件流只接受本机）。
    @Test
    void remoteAccessGroupsDoNotListTheEventStream() throws Exception {
        JSONObject state = body(console(get("/config/api/api-tokens/remote-access"), auth.issueForPassword("127.0.0.1")));
        JSONArray groups = state.getJSONArray("groups");
        assertEquals(List.of("outbox"), groups.stream().map(group -> ((JSONObject) group).getString("segment")).toList());
    }

    // 抓的故障：成员勾上「读取事件流」（或者伪造签发请求）也签出来了，拿去连事件流看到别人主播的全部弹幕。
    @Test
    void membersCannotIssueItAndTheirTokensCannotConnect() throws Exception {
        ConfigUiSession member = oidc(account("subject-m", "成员甲"));
        MockHttpServletResponse refused = console(issueRequest("越权的面板", List.of("events:read"), null), member);
        assertEquals(403, refused.getStatus(), refused.getContentAsString());
        assertTrue(body(refused).getString("message").contains("读取事件流"), refused.getContentAsString());
        assertTrue(body(console(get("/config/api/api-tokens"), member)).getJSONArray("tokens").isEmpty());

        String own = issue(member, "成员自己的", List.of("outbox:read"), null).getString("token");
        EventStreamProbe probe = new EventStreamProbe("member").connect(endpoint, own);
        JSONObject failed = probe.next();
        assertEquals("auth_failed", failed.getString("kind"));
        assertEquals("bad_token", failed.getString("reason"));
        assertFalse(probe.isOpen());
    }

    // 抓的故障：在控制台吊销了面板用的那把令牌，外部面板还一直连着收数据。
    @Test
    void revokingInTheConsoleClosesThePanelAtOnce() throws Exception {
        ConfigUiSession admin = auth.issueForPassword("127.0.0.1");
        JSONObject issued = issue(admin, "直播间大屏", List.of("events:read"), 30);
        EventStreamProbe panel = new EventStreamProbe("panel").connect(endpoint, issued.getString("token"));
        assertEquals("hello", panel.next().getString("kind"));
        EventStreamProbe old = new EventStreamProbe("old").connect(endpoint, readonly.issue("老面板"));
        assertEquals("hello", old.next().getString("kind"));

        String id = issued.getJSONObject("item").getString("id");
        assertEquals(200, console(post("/config/api/api-tokens/" + id + "/revoke"), admin).getStatus());

        assertEquals(CloseStatus.NORMAL.withReason("口令已吊销"), panel.awaitClose());
        assertTrue(old.isOpen(), "只读口令的面板不受影响");
        EventStreamProbe again = new EventStreamProbe("again").connect(endpoint, issued.getString("token"));
        assertEquals("revoked", again.next().getString("reason"));
    }

    // 抓的故障：管理员被降为成员之后，他以前签的事件流令牌还连着、还能新连。
    @Test
    void demotingTheOwnerClosesThePanelAndRefusesNewConnections() throws Exception {
        ConfigUiAccount owner = accounts.role(account("subject-a", "管理员乙").id(), ConfigUiAccount.Role.ADMIN);
        String token = issue(oidc(owner), "乙的面板", List.of("events:read"), null).getString("token");
        EventStreamProbe panel = new EventStreamProbe("panel").connect(endpoint, token);
        assertEquals("hello", panel.next().getString("kind"));

        MockHttpServletResponse demoted = console(post("/config/api/accounts/" + owner.id() + "/role")
                .content("{\"role\":\"MEMBER\"}"), auth.issueForPassword("127.0.0.1"));
        assertEquals(200, demoted.getStatus(), demoted.getContentAsString());

        assertEquals(CloseStatus.NORMAL.withReason("口令已失效"), panel.awaitClose());
        EventStreamProbe again = new EventStreamProbe("again").connect(endpoint, token);
        assertEquals("bad_token", again.next().getString("reason"));
    }

    // 抓的故障：账号停用之后，名下那把事件流令牌的面板还连着。
    @Test
    void disablingTheOwnerClosesThePanel() throws Exception {
        ConfigUiAccount owner = accounts.role(account("subject-b", "管理员丙").id(), ConfigUiAccount.Role.ADMIN);
        String token = issue(oidc(owner), "丙的面板", List.of("events:read"), null).getString("token");
        EventStreamProbe panel = new EventStreamProbe("panel").connect(endpoint, token);
        assertEquals("hello", panel.next().getString("kind"));

        MockHttpServletResponse disabled = console(post("/config/api/accounts/" + owner.id() + "/disable"),
                auth.issueForPassword("127.0.0.1"));
        assertEquals(200, disabled.getStatus(), disabled.getContentAsString());

        CloseStatus closed = panel.awaitClose();
        assertNotNull(closed, "停用之后应当当场断开");
        assertEquals(CloseStatus.NORMAL.getCode(), closed.getCode());
        EventStreamProbe again = new EventStreamProbe("again").connect(endpoint, token);
        assertEquals("revoked", again.next().getString("reason"), "停用时名下令牌一并吊销");
    }

    private ConfigUiAccount account(String subject, String name) {
        var account = accounts.register(new OidcClient.Identity(ISSUER, subject, name, null), Instant.now());
        return accounts.status(account.id(), ConfigUiAccount.Status.ENABLED);
    }

    private ConfigUiSession oidc(ConfigUiAccount account) {
        return sessions.issueForAccount("127.0.0.1", Instant.now(), ConfigUiSession.Channel.OIDC, null, account.id(), null);
    }

    private JSONObject issue(ConfigUiSession session, String name, List<String> scopes, Integer days) throws Exception {
        MockHttpServletResponse response = console(issueRequest(name, scopes, days), session);
        assertEquals(200, response.getStatus(), response.getContentAsString());
        return body(response);
    }

    private static MockHttpServletRequestBuilder issueRequest(String name, List<String> scopes, Integer days) {
        JSONObject body = new JSONObject();
        body.put("name", name);
        body.put("scopes", scopes);
        body.put("expiresInDays", days);
        return post("/config/api/api-tokens").content(body.toJSONString());
    }

    private MockHttpServletResponse console(MockHttpServletRequestBuilder builder, ConfigUiSession session) throws Exception {
        return mvc.perform(builder.contentType(MediaType.APPLICATION_JSON)
                .header("Host", "console.example.com").header("Origin", "https://console.example.com")
                .secure(true).with(request -> { request.setScheme("https"); request.setServerPort(443); return request; })
                .cookie(new Cookie(ConfigUiSecurityFilter.SESSION_COOKIE, session.getId()))
                .header(ConfigUiSecurityFilter.CSRF_HEADER, session.getCsrfToken())).andReturn().getResponse();
    }

    private static JSONObject scope(JSONObject list, String id) {
        return list.getJSONArray("scopes").stream().map(JSONObject.class::cast)
                .filter(scope -> id.equals(scope.getString("id"))).findFirst()
                .orElseThrow(() -> new AssertionError("签发页上没有 " + id + "：" + list.getJSONArray("scopes")));
    }

    private static JSONObject body(MockHttpServletResponse response) throws Exception {
        return JSONObject.parseObject(response.getContentAsString());
    }
}
