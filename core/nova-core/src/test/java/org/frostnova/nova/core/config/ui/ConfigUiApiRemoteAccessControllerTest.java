package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.http.Cookie;
import org.frostnova.nova.core.api.NovaApiFilter;
import org.frostnova.nova.core.api.NovaApiOpenGroups;
import org.frostnova.nova.core.api.NovaApiPage;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
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
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 「访问令牌」页上「从别的机器访问」那一块：开关只给管理员，保存即生效
 */
class ConfigUiApiRemoteAccessControllerTest extends OidcTestSupport {
    private static final String OUTSIDER = "203.0.113.9";

    private static final String KEY = RuntimeConfigurationApplier.API_ANY_SOURCE_GROUPS;

    @TempDir
    Path directory;

    private NovaCoreProperties properties;

    private ConfigUiAccountStore accounts;

    private ConfigUiSessionStore sessions;

    private ConfigUiAuthService auth;

    private NovaApiTokenStore tokens;

    private MockEnvironment environment;

    private MockMvc mvc;

    static final class ParcelApi implements NovaApiProvider {
        @Override
        public String segment() {
            return "parcel";
        }

        @Override
        public String name() {
            return "取件";
        }

        @Override
        public String description() {
            return "取件程序用";
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
        NovaApiRegistry registry = new NovaApiRegistry(List.of(new ParcelApi()));
        var files = new ConfigurationFileService(directory.resolve("application.yml"));
        RuntimeConfigurationApplier applier = RuntimeConfigurationApplier.bench(properties).configFile(files).build();
        environment = new MockEnvironment().withProperty("server.address", "127.0.0.1").withProperty("server.port", "7827");
        var interceptor = new ConfigUiApiInterceptor(auth, properties, platforms);
        var security = new ConfigUiSecurityFilter("test-operator-token", new IpMatcher(List.of("127.0.0.0/8")), auth,
                properties.getConfigUi().getAuth(), properties.getConfigUi().getAgreement(), () -> { });
        var api = new NovaApiFilter(
                new NovaApiOpenGroups(properties.getConfigUi()::getApiAnySourceGroups, registry), tokens, accounts,
                auth::memberSignInOpen, registry, new NovaApiThrottle(), platforms::platforms, List::of);
        mvc = MockMvcBuilders.standaloneSetup(
                        new ConfigUiApiTokenController(tokens, registry, accounts, auth::memberSignInOpen),
                        new ConfigUiApiRemoteAccessController(registry, properties, files, applier, environment,
                                () -> List.of("192.168.1.20")))
                .addInterceptors(interceptor)
                .addFilter(new ConfigUiOperationFilter(new ConfigUiOperationStore(properties)), "/config/*")
                .addFilter(security, "/config", "/config/*")
                .addFilter(api, "/nova/api", "/nova/api/*")
                .build();
    }

    // 抓的故障：成员账号也能看到「从别的机器访问」、能把接口组对外打开；或者为了挡住它，成员连自己的令牌也看不了。
    @Test
    void membersCanNeitherSeeNorChangeIt() throws Exception {
        ConfigUiSession member = oidc(member("subject-a", "成员甲"));
        assertEquals(403, console(get("/config/api/api-tokens/remote-access"), member).getStatus());
        MockHttpServletResponse change = console(toggle("parcel", true), member);
        assertEquals(403, change.getStatus(), change.getContentAsString());
        assertTrue(properties.getConfigUi().getApiAnySourceGroups().isEmpty());
        assertFalse(Files.exists(directory.resolve("application.yml"))
                && Files.readString(directory.resolve("application.yml")).contains("api-any-source-groups"));

        MockHttpServletResponse own = console(get("/config/api/api-tokens"), member);
        assertEquals(200, own.getStatus(), "自己的令牌照常看得到");
        assertFalse(body(own).getBooleanValue("admin"));
    }

    // 抓的故障：管理员打开开关后要重启才生效；或者关掉之后外网还能继续调；或者开关没写进配置文件，重启就丢了。
    @Test
    void adminTogglesTakeEffectOnTheNextRequestAndAreSaved() throws Exception {
        ConfigUiSession admin = auth.issueForPassword("127.0.0.1");
        String token = tokens.issue(ConfigUiAccount.LOCAL_ID, "取件机", List.of("parcel:read"), null).plaintext();
        assertEquals(403, outsider(token).getStatus(), "什么都没改时本机以外的来访者被拒");

        JSONObject state = body(console(get("/config/api/api-tokens/remote-access"), admin));
        JSONObject parcel = state.getJSONArray("groups").getJSONObject(0);
        assertEquals("parcel", parcel.getString("segment"));
        assertEquals("取件", parcel.getString("name"));
        assertEquals("取件程序用", parcel.getString("description"));
        assertFalse(parcel.getBooleanValue("open"));

        MockHttpServletResponse opened = console(toggle("parcel", true), admin);
        assertEquals(200, opened.getStatus(), opened.getContentAsString());
        assertTrue(body(opened).getJSONArray("groups").getJSONObject(0).getBooleanValue("open"), "回包里看得出已生效");
        assertTrue(body(opened).getString("message").startsWith("已打开：本机以外的地址"), body(opened).getString("message"));
        assertEquals(List.of("parcel"), properties.getConfigUi().getApiAnySourceGroups());
        assertEquals(200, outsider(token).getStatus(), "不用重启，下一个请求就放行");
        String file = Files.readString(directory.resolve("application.yml"));
        assertTrue(file.contains("api-any-source-groups") && file.contains("parcel"), file);
        assertTrue(auditText().contains("API_REMOTE_ACCESS_SAVE"), "记进控制台操作记录");
        assertFalse(auditText().contains(token), "令牌不进操作记录");

        MockHttpServletResponse closed = console(toggle("parcel", false), admin);
        assertEquals(200, closed.getStatus(), closed.getContentAsString());
        assertFalse(body(closed).getJSONArray("groups").getJSONObject(0).getBooleanValue("open"));
        assertTrue(body(closed).getString("message").startsWith("已关上：本机以外的地址"), body(closed).getString("message"));
        String again = body(console(toggle("parcel", false), admin)).getString("message");
        assertEquals("这组接口本来就没有对本机以外的地址开放", again);
        assertEquals(403, outsider(token).getStatus(), "关掉后本机以外的来访者立即回到 403");
        assertTrue(properties.getConfigUi().getApiAnySourceGroups().isEmpty());
        assertFalse(Files.readString(directory.resolve("application.yml")).contains("parcel"));
    }

    // 抓的故障：打开了一个不存在的组名（插件卸了），或者乱写的名字被写进配置文件；插件卸了以后旧名字也去不掉。
    @Test
    void onlyRegisteredGroupsCanBeOpenedButStaleNamesCanBeRemoved() throws Exception {
        ConfigUiSession admin = auth.issueForPassword("127.0.0.1");
        assertEquals(400, console(toggle("nothing", true), admin).getStatus());
        assertEquals(400, console(toggle("../parcel", true), admin).getStatus());
        assertEquals(400, console(post("/config/api/api-tokens/remote-access")
                .content(JSONObject.of("segment", "parcel").toJSONString()), admin).getStatus());
        assertTrue(properties.getConfigUi().getApiAnySourceGroups().isEmpty());

        properties.getConfigUi().setApiAnySourceGroups(List.of("gone", "parcel"));
        JSONObject state = body(console(get("/config/api/api-tokens/remote-access"), admin));
        assertEquals(1, state.getIntValue("ignored"), "清单里有、却没登记的名字只报条数");
        assertEquals(200, console(toggle("gone", false), admin).getStatus());
        assertEquals(List.of("parcel"), properties.getConfigUi().getApiAnySourceGroups());
    }

    // 抓的故障：配置文件里手写的组名带了首尾空白，运行中算作开放；页面上把开关关了，实际还对外开着。
    @Test
    void paddedGroupNameClosesFromThePage() throws Exception {
        ConfigUiSession admin = auth.issueForPassword("127.0.0.1");
        String token = tokens.issue(ConfigUiAccount.LOCAL_ID, "取件机", List.of("parcel:read"), null).plaintext();
        properties.getConfigUi().setApiAnySourceGroups(List.of(" parcel "));
        assertEquals(200, outsider(token).getStatus(), "夹具起点：运行中算作开放");
        assertTrue(body(console(get("/config/api/api-tokens/remote-access"), admin))
                .getJSONArray("groups").getJSONObject(0).getBooleanValue("open"), "页面上显示的就是开着");

        MockHttpServletResponse closed = console(toggle("parcel", false), admin);
        assertEquals(200, closed.getStatus(), closed.getContentAsString());
        assertFalse(body(closed).getJSONArray("groups").getJSONObject(0).getBooleanValue("open"));
        assertEquals(403, outsider(token).getStatus(), "关了就真的关了");
        assertTrue(properties.getConfigUi().getApiAnySourceGroups().isEmpty());
    }

    // 抓的故障：清单里有一条写法不合规的旧值时点了打开，页面报错，重启后却发现已经对外开了；那条旧值还进了回包或日志。
    @Test
    void malformedOldValueIsDroppedAndTheSaveIsWhole() throws Exception {
        ConfigUiSession admin = auth.issueForPassword("127.0.0.1");
        String token = tokens.issue(ConfigUiAccount.LOCAL_ID, "取件机", List.of("parcel:read"), null).plaintext();
        String odd = "Odd Value 7Q";
        properties.getConfigUi().setApiAnySourceGroups(List.of(odd));
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.frostnova.nova.core");
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start();
        var level = root.getLevel();
        root.setLevel(ch.qos.logback.classic.Level.DEBUG);
        root.addAppender(logs);
        try {
            MockHttpServletResponse state = console(get("/config/api/api-tokens/remote-access"), admin);
            assertFalse(state.getContentAsString().contains("Odd Value"), "不合规的旧值不进回包");
            assertEquals(1, body(state).getIntValue("ignored"));

            MockHttpServletResponse opened = console(toggle("parcel", true), admin);
            assertEquals(200, opened.getStatus(), opened.getContentAsString());
            assertFalse(opened.getContentAsString().contains("Odd Value"));
            assertTrue(body(opened).getString("message").contains("1 条"), body(opened).getString("message"));
            assertEquals(List.of("parcel"), properties.getConfigUi().getApiAnySourceGroups(), "运行中与落盘一致");
            String file = Files.readString(directory.resolve("application.yml"));
            assertTrue(file.contains("parcel") && !file.contains("Odd Value"), file);
            assertEquals(200, outsider(token).getStatus(), "当场生效");
            assertTrue(logs.list.stream().noneMatch(event -> event.getFormattedMessage().contains("Odd Value")),
                    "不合规的旧值不进日志");
        } finally {
            root.detachAppender(logs);
            root.setLevel(level);
        }
    }

    // 抓的故障：页面写的连法与实际不符——监听地址只限本机时说内网连得上，或者端口、只听的那个地址写错。
    @Test
    void connectionFactsComeFromTheRunningValues() throws Exception {
        ConfigUiSession admin = auth.issueForPassword("127.0.0.1");
        JSONObject state = body(console(get("/config/api/api-tokens/remote-access"), admin));
        assertEquals(7827, state.getIntValue("port"));
        assertEquals("/nova/api/v1/", state.getString("apiPath"));
        assertEquals("loopback", state.getJSONObject("listen").getString("scope"));
        assertEquals(List.of("192.168.1.20"), state.getList("lan", String.class));
        assertTrue(state.getString("consequence").contains("全靠访问令牌把关"));

        environment.setProperty("server.address", "10.1.2.3");
        environment.setProperty("local.server.port", "8899");
        state = body(console(get("/config/api/api-tokens/remote-access"), admin));
        assertEquals(8899, state.getIntValue("port"), "端口取实际在听的那个");
        assertEquals("specific", state.getJSONObject("listen").getString("scope"));
        assertEquals(List.of("10.1.2.3"), state.getList("lan", String.class), "只听一个地址时只列它");

        assertEquals("all", ConfigUiApiRemoteAccessController.listenScope("0.0.0.0"));
        assertEquals("all", ConfigUiApiRemoteAccessController.listenScope("::"));
        assertEquals("loopback", ConfigUiApiRemoteAccessController.listenScope("::1"));
        assertEquals("loopback", ConfigUiApiRemoteAccessController.listenScope("localhost"));
    }

    // 抓的故障：控制台放在公网上、「允许访问的来源 IP」只好放行所有地址的人，接口各组跟着被动全开，
    // 外面的人凭令牌就调得通；或者回包还在报那份名单，页面据此叫他去收窄名单。
    @Test
    void consoleAllowListDoesNotOpenTheApi() throws Exception {
        ConfigUiSession admin = auth.issueForPassword("127.0.0.1");
        String token = tokens.issue(ConfigUiAccount.LOCAL_ID, "取件机", List.of("parcel:read"), null).plaintext();
        for (List<String> list : List.<List<String>>of(List.of("127.0.0.1/32", "0.0.0.0/0"), List.of("::1/128", "::/0"),
                List.of("0.0.0.0/1", "128.0.0.0/1"), List.of("127.0.0.1/32", OUTSIDER))) {
            properties.getConfigUi().setAllowIps(list);
            assertEquals(403, outsider(token).getStatus(), list + "：开关全关着，本机以外的来访者被拒");
            JSONObject state = body(console(get("/config/api/api-tokens/remote-access"), admin));
            assertFalse(state.containsKey("allowListAdmitsAll") || state.containsKey("allowListBeyondLocal")
                    || state.getJSONObject("listen").containsKey("allowListed"), "回包不再报控制台名单: " + state);
        }
        assertEquals(200, console(toggle("parcel", true), admin).getStatus());
        assertEquals(200, outsider(token).getStatus(), "打开这一组才过");
    }

    // 抓的故障：在设置页上把接口组加进清单，一句提醒也没有就对外开了；或者写错的组名被存下来，让人以为开了。
    @Test
    void settingsPageAsksBeforeOpeningAndRejectsMalformedNames() {
        RuntimeConfigurationApplier applier = RuntimeConfigurationApplier.bench(properties).build();
        Map<String, String> questions = applier.confirmationsFor(Map.of(KEY, "outbox"));
        assertTrue(questions.get(KEY).contains("outbox") && questions.get(KEY).contains("全靠访问令牌把关"), questions.toString());

        properties.getConfigUi().setApiAnySourceGroups(List.of("outbox", "report"));
        assertTrue(applier.confirmationsFor(Map.of(KEY, "outbox")).isEmpty(), "只收紧不问");
        assertTrue(applier.confirmationsFor(Map.of(KEY, "")).isEmpty());

        List<String> problems = applier.validateValues(Map.of(KEY, "outbox\nOut Box"));
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("第 2 行") && !problems.get(0).contains("Out Box"), problems.toString());
        assertTrue(applier.validateValues(Map.of(KEY, "not-installed-yet")).isEmpty(), "写法对、只是还没装的照收");
        assertTrue(applier.supportedKeys().contains(KEY), "保存即生效");
    }

    private MockHttpServletResponse outsider(String token) throws Exception {
        return mvc.perform(get("/nova/api/v1/parcel/items").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .with(request -> { request.setRemoteAddr(OUTSIDER); return request; })).andReturn().getResponse();
    }

    private static MockHttpServletRequestBuilder toggle(String segment, boolean open) {
        return post("/config/api/api-tokens/remote-access").content(JSONObject.of("segment", segment, "open", open).toJSONString());
    }

    private ConfigUiAccount member(String subject, String name) {
        var account = accounts.register(new OidcClient.Identity(ISSUER, subject, name, null), Instant.now());
        return accounts.status(account.id(), ConfigUiAccount.Status.ENABLED);
    }

    private ConfigUiSession oidc(ConfigUiAccount account) {
        return sessions.issueForAccount("127.0.0.1", Instant.now(), ConfigUiSession.Channel.OIDC, null, account.id(), null);
    }

    private MockHttpServletResponse console(MockHttpServletRequestBuilder builder, ConfigUiSession session) throws Exception {
        return mvc.perform(builder.contentType(MediaType.APPLICATION_JSON)
                .header("Host", "console.example.com").header("Origin", "https://console.example.com")
                .secure(true).with(request -> { request.setScheme("https"); request.setServerPort(443); return request; })
                .cookie(new Cookie(ConfigUiSecurityFilter.SESSION_COOKIE, session.getId()))
                .header(ConfigUiSecurityFilter.CSRF_HEADER, session.getCsrfToken())).andReturn().getResponse();
    }

    private String auditText() throws Exception {
        try (Stream<Path> files = Files.list(directory.resolve("console-audit"))) {
            StringBuilder all = new StringBuilder();
            for (Path file : files.toList()) all.append(Files.readString(file, StandardCharsets.UTF_8));
            return all.toString();
        }
    }

    private static JSONObject body(MockHttpServletResponse response) throws Exception {
        return JSONObject.parseObject(response.getContentAsString());
    }
}
