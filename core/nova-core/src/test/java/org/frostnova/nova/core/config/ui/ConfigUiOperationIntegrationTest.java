package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.*;
import jakarta.servlet.http.Cookie;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.*;
import org.frostnova.nova.core.config.ui.auth.oidc.OidcClient;
import org.frostnova.nova.core.config.ui.auth.passkey.PasskeyService;
import org.frostnova.nova.core.datasource.DataSourceServiceRegistry;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.frostnova.nova.core.util.IpMatcher;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ConfigUiOperationIntegrationTest {
    @TempDir Path directory;
    NovaCoreProperties properties;
    ConfigurationFileService fileService;
    ConfigUiAuthService authService;
    ConfigUiOperationFilter filter;
    ConfigUiAccountStore accounts;
    ConfigUiAccount admin = ConfigUiAccount.local(Instant.EPOCH);

    @BeforeEach void setup() throws Exception {
        properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(directory.resolve("live.json").toString());
        properties.getDatasource().setJsonPath(directory.resolve("datasource.json").toString());
        Files.writeString(directory.resolve("application.yml"), """
                novabot:
                  core:
                    name: unchanged
                  adapter:
                    onebot:
                      token: old-secret
                """);
        fileService = new ConfigurationFileService(directory.resolve("application.yml"));
        authService = mock(ConfigUiAuthService.class);
        accounts = mock(ConfigUiAccountStore.class);
        filter = new ConfigUiOperationFilter(new ConfigUiOperationStore(properties));
    }

    private MockMvc mvc(Object... controllers) {
        return MockMvcBuilders.standaloneSetup(controllers).setControllerAdvice(new ConfigUiOperationAdvice())
                .addFilters(filter).build();
    }

    private List<JSONObject> rows() throws Exception {
        try (var files = Files.list(directory.resolve("console-audit"))) {
            List<JSONObject> result = new ArrayList<>();
            for (Path path : files.toList()) for (String line : Files.readAllLines(path)) result.add(JSONObject.parseObject(line));
            return result;
        }
    }

    @Test @DisplayName("把密钥值与未改动项写进记录：真实保存后只记录实际变更键")
    void realSettingSaveRecordsOnlyChangedKeys() throws Exception {
        var mvc = mvc(controller(properties));
        String body = JSONObject.of("novabot.adapter.onebot.token", "new-secret-value",
                "novabot.core.name", "unchanged").toJSONString();
        mvc.perform(post("/config/api/values").requestAttr(ConfigUiAccess.ACCOUNT, admin)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        assertTrue(Files.readString(directory.resolve("application.yml")).contains("new-secret-value"));
        var row = rows().get(0);
        assertEquals(List.of("novabot.adapter.onebot.token"), row.getJSONObject("target").getList("keys", String.class));
        assertFalse(row.toJSONString().contains("new-secret-value"));
        assertFalse(row.toJSONString().contains("old-secret"));
        mvc.perform(post("/config/api/values").requestAttr(ConfigUiAccess.ACCOUNT, admin)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.success").value(true));
        assertTrue(rows().get(1).getJSONObject("target").getJSONArray("keys").isEmpty());
    }

    @Test @DisplayName("记录目录损坏导致设置保存失败：真实设置仍写入并返回成功")
    void realSettingSaveSurvivesAuditFailure() throws Exception {
        Files.writeString(directory.resolve("console-audit"), "blocked");
        mvc(controller(properties)).perform(post("/config/api/values").requestAttr(ConfigUiAccess.ACCOUNT, admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"novabot.adapter.onebot.token\":\"saved-secret\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        assertTrue(Files.readString(directory.resolve("application.yml")).contains("saved-secret"));
    }

    @Test @DisplayName("分配主播后记录缺失对象：支持成员管理的结构化请求且不记录邮箱")
    void memberAssignmentsAndRoleChangesHaveAccountAndStreamerTargets() throws Exception {
        var account = ConfigUiOperationAuditTest.MEMBER;
        var registry = mock(DataSourceServiceRegistry.class);
        when(registry.platforms()).thenReturn(List.of("bilibili"));
        when(accounts.streamers(eq(account.id()), anyList())).thenReturn(account);
        when(accounts.role(eq(account.id()), any())).thenReturn(account);
        when(accounts.require(account.id())).thenReturn(account);
        var mvc = mvc(new ConfigUiAccountController(accounts, authService, mock(OidcClient.class), registry));
        mvc.perform(post("/config/api/accounts/member-7/streamers").requestAttr(ConfigUiAccess.ACCOUNT, admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"streamers\":[\"bilibili:123\"]}"))
                .andExpect(jsonPath("$.success").value(true));
        var row = rows().get(0);
        assertEquals("member-7", row.getJSONObject("target").getString("accountId"));
        assertEquals(JSONObject.of("platform", "bilibili", "uid", "123"),
                row.getJSONObject("target").getJSONArray("streamers").getJSONObject(0));
        assertFalse(row.toJSONString().contains("member@example.invalid"));
        mvc.perform(post("/config/api/accounts/member-7/role").requestAttr(ConfigUiAccess.ACCOUNT, admin)
                .requestAttr(ConfigUiAccess.FRESH_LOCAL, true).contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"MEMBER\"}"))
                .andExpect(jsonPath("$.success").value(true));
        assertEquals("ACCOUNT_ROLE", rows().get(1).getString("action"));
        mvc.perform(delete("/config/api/accounts/member-7").requestAttr(ConfigUiAccess.ACCOUNT, admin))
                .andExpect(status().isOk());
        assertEquals("ACCOUNT_DELETE", rows().get(2).getString("action"));
    }

    @Test @DisplayName("登录记录认不出本人或泄露会话：口令与通行密钥登录记录账号且不记凭据")
    void successfulLoginsIdentifyTheAccount() throws Exception {
        var session = mock(ConfigUiSession.class);
        when(session.getId()).thenReturn("private-session");
        when(session.getCsrfToken()).thenReturn("private-csrf");
        when(authService.account(session)).thenReturn(admin);
        when(authService.isEnabled()).thenReturn(true);
        when(authService.login(any(char[].class), any(), any())).thenReturn(
                new ConfigUiAuthService.LoginResult(session, null, Duration.ZERO, false));
        var passkeys = mock(PasskeyService.class);
        when(passkeys.loginVerify(any(), any(), any())).thenReturn(new PasskeyService.PasskeyLogin(session, null));
        var mvc = mvc(new ConfigUiAuthController(authService, fileService, properties),
                new ConfigUiPasskeyController(passkeys, properties, authService));
        for (String route : List.of("auth/login", "auth/passkey/login/verify")) {
            mvc.perform(post("/config/api/" + route).header("Host", "localhost").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"password\":\"private-password\",\"code\":\"123456\"}"))
                    .andExpect(jsonPath("$.success").value(true));
        }
        mvc.perform(post("/config/api/auth/logout").requestAttr(ConfigUiAccess.ACCOUNT, admin)
                .cookie(new Cookie(ConfigUiSecurityFilter.SESSION_COOKIE, "private-session")))
                .andExpect(jsonPath("$.success").value(true));
        var rows = rows();
        assertEquals(3, rows.size());
        for (var row : rows) {
            assertEquals("local-admin", row.getJSONObject("actor").getString("id"));
            for (String secret : List.of("private-session", "private-csrf", "private-password", "123456"))
                assertFalse(row.toJSONString().contains(secret));
        }
    }

    @Test @DisplayName("权限过滤器提前拒绝导致没有记录：未登录请求仍记录拒绝且不假冒管理员")
    void recordsDenialBeforeController() throws Exception {
        var security = new ConfigUiSecurityFilter("real-token", new IpMatcher(List.of("127.0.0.1")), authService,
                properties.getConfigUi().getAuth(), properties.getConfigUi().getAgreement());
        var mvc = MockMvcBuilders.standaloneSetup(controller(properties)).addFilters(filter, security).build();
        mvc.perform(post("/config/api/values").param("token", "wrong-private-token").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        var row = rows().get(0);
        assertEquals("REJECTED", row.getString("result"));
        assertNull(row.getJSONObject("actor").getString("id"));
        assertTrue(row.getJSONObject("actor").containsKey("id"));
        assertFalse(row.toJSONString().contains("wrong-private-token"));
        assertTrue(row.getString("reason").contains("访问令牌"));
    }
    @Test @DisplayName("改推送通道后无法定位对象：整份保存记录被删除主播与旧通道且不含模板值")
    void datasourceSaveRetainsRemovedObjects() throws Exception {
        Files.writeString(directory.resolve("datasource.json"), """
                [{"platform":"bilibili","uid":123,"targets":[{"platform":"qq","type":1,"num":456,
                  "params":{"message":"private-template-value"}}]}]
                """);
        mvc(controller(properties)).perform(post("/config/api/datasource").requestAttr(ConfigUiAccess.ACCOUNT, admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"[]\"}"))
                .andExpect(jsonPath("$.success").value(true));
        var target = rows().get(0).getJSONObject("target");
        assertEquals(JSONObject.of("platform", "bilibili", "uid", "123"), target.getJSONArray("streamers").getJSONObject(0));
        assertEquals(JSONObject.of("platform", "qq", "type", "1", "num", "456"), target.getJSONArray("channels").getJSONObject(0));
        assertFalse(target.toJSONString().contains("private-template-value"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ConfigUiAccount.Status.class)
    @DisplayName("统一登录跳转把拒绝记为成功或丢失账号：记录回调账号及启用、待批准、停用结果")
    void oidcCallbackRecordsIdentityAndRedirectDenials(ConfigUiAccount.Status state) throws Exception {
        var client = mock(OidcClient.class);
        when(authService.oidcEnabled()).thenReturn(true);
        properties.getConfigUi().getAgreement().setAcceptedVersion(ConfigUiAgreement.VERSION);
        properties.getConfigUi().getAgreement().setAcceptedBy("test");
        when(client.startLogin(any())).thenReturn(new OidcClient.Authorization(
                java.net.URI.create("https://login.example/authorize"), "private-state", Instant.now().plusSeconds(120)));
        var login = new OidcClient.Login(new OidcClient.Identity("private-issuer", "private-subject", "小明", "member@example.invalid"),
                new OidcClient.Tokens("private-access", "private-id", "private-refresh", 3600L), "private-nonce");
        when(client.completeLogin(any(), any(), any())).thenReturn(login);
        var account = new ConfigUiAccount("member-7", ConfigUiAccount.Role.MEMBER, state, "小明", "member@example.invalid",
                "private-issuer", "private-subject", List.of(), Instant.EPOCH, null, true);
        when(accounts.register(any(), any())).thenReturn(account);
        var session = mock(ConfigUiSession.class);
        when(session.getId()).thenReturn("private-session");
        when(authService.issueForOidc(any(), any(), any())).thenReturn(session);
        var mvc = mvc(new ConfigUiOidcController(client, authService, accounts, properties));
        var start = mvc.perform(get("/config/api/oidc/login")).andExpect(status().isFound()).andReturn();
        Cookie correlation = start.getResponse().getCookie(ConfigUiOidcController.COOKIE);
        mvc.perform(get("/config/api/oidc/callback").cookie(correlation).param("state", "private-state")
                .param("code", "private-code").param("iss", "private-issuer"))
                .andExpect(status().is(state == ConfigUiAccount.Status.ENABLED ? 200 : 302));
        var row = rows().get(1);
        assertEquals("OIDC_LOGIN", row.getString("action"));
        assertEquals("member-7", row.getJSONObject("actor").getString("id"));
        assertEquals(state == ConfigUiAccount.Status.ENABLED ? "SUCCESS" : "REJECTED", row.getString("result"));
        if (state != ConfigUiAccount.Status.ENABLED) assertFalse(row.getString("reason").isBlank());
        for (String secret : List.of("private-issuer", "private-subject", "member@example.invalid", "private-access",
                "private-id", "private-refresh", "private-nonce", "private-session", "private-code", "private-state"))
            assertFalse(row.toJSONString().contains(secret));
    }

    @Test @DisplayName("页面轮询刷满操作记录：成功读取平台登录状态不算一次操作")
    void successfulLoginStatePollingIsNotAnOperation() throws Exception {
        mvc(controller(properties)).perform(get("/config/api/login").requestAttr(ConfigUiAccess.ACCOUNT, admin))
                .andExpect(jsonPath("$.success").value(true));
        assertFalse(Files.exists(directory.resolve("console-audit")), "普通状态轮询不能生成操作记录");
    }

    @SuppressWarnings("unchecked")
    private ConfigUiController controller(NovaCoreProperties properties) {
        ConfigurationMetadataService metadata = mock(ConfigurationMetadataService.class);
        when(metadata.getKnownTypes()).thenReturn(Map.of(
                "novabot.adapter.onebot.token", "java.lang.String",
                "novabot.core.name", "java.lang.String"));

        RuntimeConfigurationApplier applier = RuntimeConfigurationApplier.bench(properties).build();

        return new ConfigUiController(
                metadata,
                fileService,
                properties,
                mock(org.frostnova.nova.core.datasource.AbstractDataSource.class),
                mock(ObjectProvider.class),
                mock(ConfigurationValidator.class),
                mock(org.frostnova.nova.core.service.NovaSenderService.class),
                mock(org.frostnova.nova.core.sender.NovaMessageSender.class),
                mock(ObjectProvider.class),
                mock(org.frostnova.nova.core.health.PushActivityRecorder.class),
                mock(org.frostnova.nova.core.service.NovaEventHandlerService.class),
                mock(org.frostnova.nova.core.datasource.DataSourceServiceRegistry.class),
                mock(ConfigurationLevelResolver.class),
                new ConfigurationLabelResolver(mock(org.springframework.context.ApplicationContext.class)),
                new ConfigurationEffectResolver(mock(ApplicationContext.class)),
                new ConfigurationDangerResolver(mock(ApplicationContext.class)),
                applier,
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(org.frostnova.nova.core.protocol.EventStreamTokenService.class),
                mock(ObjectProvider.class),
                mock(org.frostnova.nova.core.sender.PushGate.class),
                mock(org.frostnova.nova.core.service.LiveDataService.class),
                mock(org.frostnova.nova.core.timeline.TimelineStore.class),
                authService,
                new PushTemplateDefaults(properties),
                mock(UpdateCheckService.class));
    }

}
