package org.frostnova.nova.core.config.ui;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.http.Cookie;
import org.frostnova.nova.core.api.NovaApiFilter;
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
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.util.IpMatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 控制台里的「访问令牌」，连同它签出来的令牌在统一接口面上的表现
 */
class ConfigUiApiTokenControllerTest extends OidcTestSupport {
    @TempDir
    Path directory;

    private NovaCoreProperties properties;

    private ConfigUiAccountStore accounts;

    private ConfigUiSessionStore sessions;

    private ConfigUiAuthService auth;

    private MockMvc mvc;

    private final Logger root = (Logger) LoggerFactory.getLogger("org.frostnova.nova.core");

    private ch.qos.logback.classic.Level previousLevel;

    private NovaApiTokenStore tokens;

    private NovaApiRegistry registry;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    /** 有一项范围可勾，好量「勾了什么、出示时认不认」 */
    static final class ReportApi implements NovaApiProvider {
        @Override
        public String segment() {
            return "report";
        }

        @Override
        public List<NovaApiScope> scopes() {
            return List.of(new NovaApiScope("report:read", "读取报告", "读取直播报告"),
                    NovaApiScope.adminOnly("report:write", "写入报告", "改写全部直播报告"));
        }

        @Override
        public List<NovaApiRoute> routes() {
            return List.of(NovaApiRoute.get("latest", "report:read", request -> JSONObject.of("ok", true)),
                    NovaApiRoute.post("rebuild", "report:write", request -> JSONObject.of("rebuilt", true)));
        }
    }

    @BeforeEach
    void console() throws Exception {
        properties = new NovaCoreProperties();
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
        registry = new NovaApiRegistry(List.of(new ReportApi()));
        var files = new ConfigurationFileService(directory.resolve("application.yml"));
        var interceptor = new ConfigUiApiInterceptor(auth, properties, platforms);
        var security = new ConfigUiSecurityFilter("test-operator-token", new IpMatcher(List.of("127.0.0.0/8")), auth,
                properties.getConfigUi().getAuth(), properties.getConfigUi().getAgreement(), () -> { });
        var api = new NovaApiFilter(new ConsoleAllowList(properties.getConfigUi()), tokens, accounts,
                auth::memberSignInOpen, registry, new NovaApiThrottle(), platforms::platforms, List::of);
        mvc = MockMvcBuilders.standaloneSetup(
                        new ConfigUiApiTokenController(tokens, registry, accounts, auth::memberSignInOpen),
                        new ConfigUiAuthController(auth, files, properties),
                        new ConfigUiAccountController(accounts, auth, client, platforms, tokens))
                .addInterceptors(interceptor)
                .addFilter(new ConfigUiOperationFilter(new ConfigUiOperationStore(properties)), "/config/*")
                .addFilter(security, "/config", "/config/*")
                .addFilter(api, "/nova/api", "/nova/api/*")
                .build();
        logs.list.clear();
        logs.start();
        previousLevel = root.getLevel();
        root.setLevel(ch.qos.logback.classic.Level.INFO);
        root.addAppender(logs);
    }

    @org.junit.jupiter.api.AfterEach
    void detach() {
        root.detachAppender(logs);
        root.setLevel(previousLevel);
        logs.stop();
    }

    // 抓的故障：成员能看到、能吊销别人的令牌；或者成员吊销了自己的令牌，那把令牌还能接着用。
    @Test
    void memberSeesAndRevokesOnlyOwnTokens() throws Exception {
        ConfigUiSession alice = oidc(member("subject-a", "成员甲"));
        ConfigUiSession bob = oidc(member("subject-b", "成员乙"));
        JSONObject aliceIssued = issue(alice, "甲的脚本", List.of(), null);
        JSONObject bobIssued = issue(bob, "乙的脚本", List.of(), null);

        JSONArray aliceList = body(console(get("/config/api/api-tokens"), alice)).getJSONArray("tokens");
        assertEquals(List.of("甲的脚本"), names(aliceList));
        assertFalse(body(console(get("/config/api/api-tokens"), alice)).getBooleanValue("admin"));

        String bobId = bobIssued.getJSONObject("item").getString("id");
        MockHttpServletResponse foreign = console(post("/config/api/api-tokens/" + bobId + "/revoke"), alice);
        assertEquals(404, foreign.getStatus());
        assertEquals(ConfigUiApiTokenController.NOT_FOUND, body(foreign).getString("message"));
        assertEquals(200, me(bobIssued.getString("token")).getStatus(), "成员甲的吊销不该碰到成员乙的令牌");

        String aliceToken = aliceIssued.getString("token");
        assertEquals(200, me(aliceToken).getStatus());
        MockHttpServletResponse own = console(post("/config/api/api-tokens/" + aliceIssued.getJSONObject("item").getString("id")
                + "/revoke"), alice);
        assertEquals(200, own.getStatus());
        assertEquals("revoked", body(own).getJSONObject("item").getString("state"));
        assertEquals(401, me(aliceToken).getStatus(), "吊销后下一个请求就不该再认");
    }

    // 抓的故障：管理员看不到成员签了哪些令牌，发现可疑的也撤不掉。
    @Test
    void adminSeesEveryAccountsTokensAndCanRevokeThem() throws Exception {
        ConfigUiSession member = oidc(member("subject-a", "成员甲"));
        JSONObject issued = issue(member, "成员的脚本", List.of(), null);
        ConfigUiSession admin = auth.issueForPassword("127.0.0.1");
        issue(admin, "管理员自己的", List.of(), null);

        JSONObject list = body(console(get("/config/api/api-tokens"), admin));
        assertTrue(list.getBooleanValue("admin"));
        JSONArray rows = list.getJSONArray("tokens");
        assertEquals(2, rows.size());
        JSONObject memberRow = rows.stream().map(JSONObject.class::cast)
                .filter(row -> row.getString("name").equals("成员的脚本")).findFirst().orElseThrow();
        assertEquals("成员甲", memberRow.getJSONObject("owner").getString("displayName"));
        assertFalse(memberRow.getBooleanValue("mine"));

        assertEquals(200, console(post("/config/api/api-tokens/" + memberRow.getString("id") + "/revoke"), admin).getStatus());
        assertEquals(401, me(issued.getString("token")).getStatus());
    }

    // 抓的故障：签第 21 把时没有任何说明就失败，或者干脆签出来了。
    @Test
    void twentyFirstTokenIsRefusedWithAnExplanation() throws Exception {
        ConfigUiSession member = oidc(member("subject-a", "成员甲"));
        for (int i = 0; i < 20; i++) {
            assertEquals(200, console(issueRequest("同名", List.of(), null), member).getStatus());
        }
        MockHttpServletResponse refused = console(issueRequest("第二十一把", List.of(), null), member);
        assertEquals(409, refused.getStatus());
        String message = body(refused).getString("message");
        assertTrue(message.contains("20") && message.contains("吊销"), message);
        assertEquals(20, body(console(get("/config/api/api-tokens"), member)).getJSONArray("tokens").size());
        assertEquals(20, body(console(get("/config/api/api-tokens"), member)).getIntValue("active"));
    }

    // 抓的故障：勾了一项不存在的范围、或者有效期随手填了个数，也照样签出来了。
    @Test
    void unknownScopeOrValidityIsRefused() throws Exception {
        ConfigUiSession admin = auth.issueForPassword("127.0.0.1");
        assertEquals(400, console(issueRequest("x", List.of("nope:read"), null), admin).getStatus());
        assertEquals(400, console(issueRequest("x", List.of(), 7), admin).getStatus());
        assertEquals(400, console(issueRequest("  ", List.of(), null), admin).getStatus());
        JSONObject ok = issue(admin, "带范围", List.of("report:read"), 90);
        assertEquals(List.of("report:read"), ok.getJSONObject("item").getList("scopes", String.class));
        assertNotNull(ok.getJSONObject("item").get("expiresAt"));
        assertEquals(200, mvc.perform(get("/nova/api/v1/report/latest")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ok.getString("token"))).andReturn().getResponse().getStatus());

        JSONObject list = body(console(get("/config/api/api-tokens"), admin));
        assertEquals("report:read", list.getJSONArray("scopes").getJSONObject(0).getString("id"));
        assertEquals("读取报告", list.getJSONArray("scopes").getJSONObject(0).getString("name"));
    }

    // 抓的故障：签发、吊销没有留下操作记录；或者操作记录、日志、列表回包里出现了令牌明文或摘要。
    @Test
    void issueAndRevokeAreAuditedWithoutSecrets() throws Exception {
        ConfigUiSession member = oidc(member("subject-a", "成员甲"));
        JSONObject issued = issue(member, "记录用", List.of("report:read"), null);
        String token = issued.getString("token");
        String id = issued.getJSONObject("item").getString("id");
        assertEquals(200, me(token).getStatus());
        assertEquals(200, console(post("/config/api/api-tokens/" + id + "/revoke"), member).getStatus());
        String list = console(get("/config/api/api-tokens"), member).getContentAsString();

        String audit;
        try (Stream<Path> files = Files.list(directory.resolve("console-audit"))) {
            StringBuilder all = new StringBuilder();
            for (Path file : files.toList()) all.append(Files.readString(file, StandardCharsets.UTF_8));
            audit = all.toString();
        }
        List<JSONObject> rows = audit.lines().map(JSONObject::parseObject).toList();
        JSONObject issueRow = rows.stream().filter(r -> "API_TOKEN_ISSUE".equals(r.getString("action"))).findFirst().orElseThrow();
        JSONObject revokeRow = rows.stream().filter(r -> "API_TOKEN_REVOKE".equals(r.getString("action"))).findFirst().orElseThrow();
        assertEquals("SUCCESS", issueRow.getString("result"));
        assertEquals(id, issueRow.getJSONObject("target").getString("tokenId"));
        assertEquals(id, revokeRow.getJSONObject("target").getString("tokenId"));
        assertEquals("MEMBER", revokeRow.getJSONObject("actor").getString("role"));

        String digest = sha256(token);
        String logText = logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(logText.contains(id), "签发与吊销应在日志里留下编号");
        for (String surface : List.of(audit, logText, list)) {
            assertFalse(surface.contains(token), "出现了令牌明文");
            assertFalse(surface.contains(token.substring(NovaApiTokenStore.PREFIX.length())), "出现了令牌明文");
            assertFalse(surface.contains(digest), "出现了令牌摘要");
            assertFalse(surface.contains(digest.substring(0, 16)), "出现了摘要的一截");
        }
    }

    // 抓的故障：拿访问令牌也能进控制台的接口（控制台只认会话和启动令牌）。
    @Test
    void accessTokenIsNotAConsoleCredential() throws Exception {
        ConfigUiSession admin = auth.issueForPassword("127.0.0.1");
        String token = issue(admin, "进不了控制台", List.of(), null).getString("token");

        MockHttpServletResponse bearer = mvc.perform(get("/config/api/api-tokens")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn().getResponse();
        assertEquals(401, bearer.getStatus());
        assertNull(bearer.getHeader(HttpHeaders.SET_COOKIE), "不该凭访问令牌换出控制台会话");
        assertEquals(401, mvc.perform(get("/config/api/api-tokens").param("token", token)).andReturn().getResponse().getStatus());

        // 没设口令的形态：控制台只认启动令牌，访问令牌照样不认
        var open = new NovaCoreProperties.ConfigUi.Auth();
        var tokenMode = new ConfigUiAuthService(open, sessions, new LoginThrottle(5, Duration.ofMinutes(15)), null);
        assertFalse(tokenMode.isEnabled());
        var bare = MockMvcBuilders.standaloneSetup(new ConfigUiApiTokenController(tokens, registry, accounts,
                        tokenMode::memberSignInOpen))
                .addInterceptors(new ConfigUiApiInterceptor(tokenMode))
                .addFilter(new ConfigUiSecurityFilter("test-operator-token", new IpMatcher(List.of("127.0.0.0/8")), tokenMode,
                        open, properties.getConfigUi().getAgreement(), () -> { }), "/config", "/config/*")
                .build();
        assertEquals(401, bare.perform(get("/config/api/api-tokens")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn().getResponse().getStatus());
        assertEquals(401, bare.perform(get("/config/api/api-tokens").param("token", token))
                .andReturn().getResponse().getStatus());
        assertEquals(200, bare.perform(get("/config/api/api-tokens")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-operator-token")).andReturn().getResponse().getStatus(),
                "启动令牌那一套不变");
    }

    // 抓的故障：某个脚本写错令牌狂刷之后，人在浏览器里输对口令也登不进控制台；
    // 反过来，有人在控制台输错几次口令被锁，拿着正确令牌的程序也被挡住。
    @Test
    void apiThrottleAndConsoleLockoutAreSeparate() throws Exception {
        ConfigUiSession admin = auth.issueForPassword("127.0.0.1");
        String valid = issue(admin, "正常的程序", List.of(), null).getString("token");
        for (int i = 0; i < 11; i++) me("nvb_wrong-" + i);
        assertEquals(429, me("nvb_wrong-final").getStatus());

        MockHttpServletResponse login = mvc.perform(post("/config/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"example password\"}")).andReturn().getResponse();
        assertEquals(200, login.getStatus(), login.getContentAsString());
        assertTrue(body(login).getBooleanValue("success"));

        for (int i = 0; i < 6; i++) {
            mvc.perform(post("/config/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"password\":\"wrong password\"}"));
        }
        MockHttpServletResponse locked = mvc.perform(post("/config/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"example password\"}")).andReturn().getResponse();
        assertNotEquals(200, locked.getStatus(), "控制台的锁定应当生效，否则这一格量不到东西");
        assertEquals(200, me(valid).getStatus());
    }

    // 抓的故障：因为泄露停用的账号，重新启用后泄露的令牌又能用了。
    @Test
    void disablingAMemberRevokesEveryTokenForGood() throws Exception {
        ConfigUiAccount account = member("subject-a", "成员甲");
        ConfigUiSession member = oidc(account);
        List<String> leaked = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            leaked.add(issue(member, "脚本" + i, List.of(), null).getString("token"));
        }
        String bystander = issue(oidc(member("subject-b", "成员乙")), "乙的脚本", List.of(), null).getString("token");
        for (String token : leaked) {
            assertEquals(200, me(token).getStatus());
        }

        ConfigUiSession admin = auth.issueForPassword("127.0.0.1");
        MockHttpServletResponse disabled = console(post("/config/api/accounts/" + account.id() + "/disable"), admin);
        assertEquals(200, disabled.getStatus(), disabled.getContentAsString());
        assertEquals(List.of("revoked", "revoked", "revoked"), states(admin, account.id()));

        assertEquals(200, console(post("/config/api/accounts/" + account.id() + "/enable"), admin).getStatus());
        assertEquals(List.of("revoked", "revoked", "revoked"), states(admin, account.id()), "重新启用不该让吊销的令牌复活");
        for (String token : leaked) {
            assertEquals(401, me(token).getStatus(), "重新启用后旧令牌仍不该认");
        }
        assertEquals(200, me(bystander).getStatus(), "别的账号的令牌不受连累");

        JSONObject row = auditRows().stream().filter(r -> "ACCOUNT_DISABLE".equals(r.getString("action")))
                .findFirst().orElseThrow();
        assertEquals("SUCCESS", row.getString("result"));
        assertEquals(3, row.getJSONObject("target").getIntValue("revokedTokens"), row.toJSONString());
        String audit = auditText();
        for (String token : leaked) {
            assertFalse(audit.contains(token), "操作记录里出现了令牌明文");
            assertFalse(audit.contains(sha256(token)), "操作记录里出现了令牌摘要");
        }
    }

    // 抓的故障：删掉账号之后，它名下没吊销的令牌在列表里还显示「有效」。
    @Test
    void deletingAnAccountRevokesWhatIsLeft() throws Exception {
        ConfigUiAccount account = member("subject-a", "成员甲");
        String token = issue(oidc(account), "脚本", List.of(), null).getString("token");
        // 停用直接落在账号表上，不经控制台：删除那一步自己也得把令牌收拾干净
        accounts.status(account.id(), ConfigUiAccount.Status.DISABLED);

        ConfigUiSession admin = auth.issueForPassword("127.0.0.1");
        MockHttpServletResponse deleted = console(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/config/api/accounts/" + account.id()), admin);
        assertEquals(200, deleted.getStatus(), deleted.getContentAsString());
        assertEquals(401, me(token).getStatus());
        assertEquals(List.of("revoked"), states(admin, account.id()));

        JSONObject row = auditRows().stream().filter(r -> "ACCOUNT_DELETE".equals(r.getString("action")))
                .findFirst().orElseThrow();
        assertEquals(1, row.getJSONObject("target").getIntValue("revokedTokens"), row.toJSONString());
    }

    // 抓的故障：成员签发时勾上「仅管理员」的范围也照样签出来了，签发页上也看不出那一项成员勾不了。
    @Test
    void membersCannotIssueAdminOnlyScopes() throws Exception {
        ConfigUiSession member = oidc(member("subject-a", "成员甲"));
        JSONObject list = body(console(get("/config/api/api-tokens"), member));
        assertTrue(scope(list, "report:write").getBooleanValue("adminOnly"));
        assertFalse(scope(list, "report:read").getBooleanValue("adminOnly"));

        MockHttpServletResponse refused = console(issueRequest("越权", List.of("report:read", "report:write"), null), member);
        assertEquals(403, refused.getStatus(), refused.getContentAsString());
        String message = body(refused).getString("message");
        assertTrue(message.contains("写入报告") && message.contains("管理员"), message);
        assertTrue(body(console(get("/config/api/api-tokens"), member)).getJSONArray("tokens").isEmpty(),
                "被拒的那一把不该签出来");

        ConfigUiSession admin = auth.issueForPassword("127.0.0.1");
        JSONObject issued = issue(admin, "管理员的", List.of("report:write"), null);
        assertEquals(List.of("report:write"), issued.getJSONObject("item").getList("scopes", String.class));
        assertEquals(200, mvc.perform(post("/nova/api/v1/report/rebuild")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + issued.getString("token"))).andReturn().getResponse().getStatus());
    }

    private List<String> states(ConfigUiSession viewer, String accountId) throws Exception {
        return body(console(get("/config/api/api-tokens"), viewer)).getJSONArray("tokens").stream()
                .map(JSONObject.class::cast)
                .filter(row -> accountId.equals(row.getJSONObject("owner").getString("id")))
                .map(row -> row.getString("state")).toList();
    }

    private static JSONObject scope(JSONObject list, String id) {
        return list.getJSONArray("scopes").stream().map(JSONObject.class::cast)
                .filter(scope -> id.equals(scope.getString("id"))).findFirst().orElseThrow();
    }

    private String auditText() throws Exception {
        try (Stream<Path> files = Files.list(directory.resolve("console-audit"))) {
            StringBuilder all = new StringBuilder();
            for (Path file : files.toList()) all.append(Files.readString(file, StandardCharsets.UTF_8));
            return all.toString();
        }
    }

    private List<JSONObject> auditRows() throws Exception {
        return auditText().lines().map(JSONObject::parseObject).toList();
    }

    private ConfigUiAccount member(String subject, String name) {
        var account = accounts.register(new OidcClient.Identity(ISSUER, subject, name, null), Instant.now());
        return accounts.status(account.id(), ConfigUiAccount.Status.ENABLED);
    }

    private ConfigUiSession oidc(ConfigUiAccount account) {
        return sessions.issueForAccount("127.0.0.1", Instant.now(), ConfigUiSession.Channel.OIDC, null, account.id(), null);
    }

    private JSONObject issue(ConfigUiSession session, String name, List<String> scopes, Integer days) throws Exception {
        MockHttpServletResponse response = console(issueRequest(name, scopes, days), session);
        assertEquals(200, response.getStatus(), response.getContentAsString());
        JSONObject json = body(response);
        assertTrue(json.getString("token").startsWith(NovaApiTokenStore.PREFIX));
        return json;
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

    private MockHttpServletResponse me(String token) throws Exception {
        return mvc.perform(get("/nova/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse();
    }

    private static List<String> names(JSONArray rows) {
        return rows.stream().map(o -> ((JSONObject) o).getString("name")).toList();
    }

    private static JSONObject body(MockHttpServletResponse response) throws Exception {
        return JSONObject.parseObject(response.getContentAsString());
    }

    private static String sha256(String text) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
