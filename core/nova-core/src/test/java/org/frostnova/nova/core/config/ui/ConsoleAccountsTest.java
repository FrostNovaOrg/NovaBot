package org.frostnova.nova.core.config.ui;

import org.frostnova.nova.core.config.ui.auth.oidc.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.http.Cookie;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.*;
import org.frostnova.nova.core.config.ui.auth.*;
import org.frostnova.nova.core.config.ui.auth.passkey.PasskeyService;
import org.frostnova.nova.core.config.ui.auth.passkey.PasskeyStore;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.frostnova.nova.core.util.IpMatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.frostnova.nova.core.config.ui.auth.ConfigUiAccount.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ConsoleAccountsTest extends OidcTestSupport {
    @TempDir Path directory;
    private NovaCoreProperties properties;
    private NovaStateStore state;
    private ConfigUiAccountStore accounts;
    private ConfigUiSessionStore sessions;
    private ConfigUiAuthService auth;
    private MockMvc mvc;
    private Path configFile;

    @BeforeEach
    void console() throws Exception {
        properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(directory.resolve("live.json").toString());
        properties.getConfigUi().getAuth().setPassword(LowIterationPasswordHash.of("example password"));
        properties.getConfigUi().getAuth().setTotp(false);
        properties.getConfigUi().getAgreement().setAcceptedVersion(ConfigUiAgreement.VERSION);
        properties.getConfigUi().getAgreement().setAcceptedBy("password");
        state = new NovaStateStore(properties);
        accounts = new ConfigUiAccountStore(state);
        sessions = new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(24));
        var throttle = new LoginThrottle(5, Duration.ofMinutes(15));
        auth = new ConfigUiAuthService(properties.getConfigUi().getAuth(), sessions, throttle, null);
        auth.setAccounts(accounts);
        auth.setOidcClient(client);
        configFile = directory.resolve("application.yml");
        Files.writeString(configFile, "novabot:\n  core:\n    config-ui:\n      oidc:\n        issuer: https://accounts.example.com/tenant\n");
        var files = new ConfigurationFileService(configFile);
        var filter = new ConfigUiSecurityFilter("test-operator", new IpMatcher(List.of("127.0.0.0/8")), auth,
                properties.getConfigUi().getAuth(), properties.getConfigUi().getAgreement());
        mvc = MockMvcBuilders.standaloneSetup(new ConfigUiAuthController(auth, files, properties),
                        new ConfigUiPasskeyController(new PasskeyService(new PasskeyStore(state), auth), properties, auth),
                        new ConfigUiAccountController(accounts, auth, client),
                        new ConfigUiOidcController(client, auth, accounts, properties), values(files))
                .addInterceptors(new ConfigUiApiInterceptor(auth))
                .addFilters(filter).build();
    }

    @SuppressWarnings("unchecked")
    private ConfigUiController values(ConfigurationFileService files) {
        var metadata = mock(ConfigurationMetadataService.class);
        when(metadata.getKnownTypes()).thenReturn(Map.of("novabot.core.config-ui.oidc.issuer", "java.lang.String",
                "novabot.core.config-ui.oidc.enabled", "java.lang.Boolean",
                "novabot.core.push.enabled", "java.lang.Boolean"));
        return new ConfigUiController(metadata, files, properties,
                mock(org.frostnova.nova.core.datasource.AbstractDataSource.class), mock(ObjectProvider.class),
                mock(ConfigurationValidator.class), mock(org.frostnova.nova.core.service.NovaSenderService.class),
                mock(org.frostnova.nova.core.sender.NovaMessageSender.class), mock(ObjectProvider.class),
                mock(org.frostnova.nova.core.health.PushActivityRecorder.class),
                mock(org.frostnova.nova.core.service.NovaEventHandlerService.class),
                mock(org.frostnova.nova.core.datasource.DataSourceServiceRegistry.class), mock(ConfigurationLevelResolver.class),
                new ConfigurationLabelResolver(mock(ApplicationContext.class)),
                new ConfigurationEffectResolver(mock(ApplicationContext.class)),
                new ConfigurationDangerResolver(mock(ApplicationContext.class)),
                RuntimeConfigurationApplier.bench(properties).build(), mock(ObjectProvider.class), mock(ObjectProvider.class),
                mock(org.frostnova.nova.core.protocol.EventStreamTokenService.class), mock(ObjectProvider.class),
                mock(org.frostnova.nova.core.sender.PushGate.class), mock(org.frostnova.nova.core.service.LiveDataService.class),
                mock(org.frostnova.nova.core.timeline.TimelineStore.class), auth, new PushTemplateDefaults(properties),
                mock(UpdateCheckService.class));
    }

    // 在账户中心自行注册的人必须先登记，管理员启用后才能拿到控制台会话。
    @Test
    void unknownIdentityWaitsForApprovalThenCanLogin() throws Exception {
        var pending = callback(start(null), null);
        assertEquals("/config?oidc=pending", pending.getResponse().getRedirectedUrl());
        assertNull(pending.getResponse().getCookie(ConfigUiSecurityFilter.SESSION_COOKIE));
        var member = member();
        assertEquals(Status.PENDING, member.status());
        var local = auth.issueForPassword("127.0.0.1");
        perform(post("/config/api/accounts/" + member.id() + "/enable"), local).andExpect(status().isOk());
        var accepted = callback(start(null), null);
        assertEquals(200, accepted.getResponse().getStatus());
        var cookie = accepted.getResponse().getCookie(ConfigUiSecurityFilter.SESSION_COOKIE);
        assertNotNull(cookie);
        var session = auth.validate(cookie.getValue()).orElseThrow();
        assertEquals(member.id(), session.getAccountId());
        assertEquals("refresh-1", session.oidcLogin().tokens().refreshToken());
        assertTrue(accepted.getResponse().getContentAsString().contains("http-equiv=\"refresh\""));
        assertNull(accepted.getResponse().getRedirectedUrl());
        assertEquals("Strict", cookie.getAttribute("SameSite"));
        assertFalse(accepted.getResponse().getContentAsString().contains("refresh-1"));
    }

    // 停用必须立即撤销已签会话，下个请求不能继续使用它。
    @Test
    void disablingImmediatelyRevokesSessionsAndNextRequestIsUnauthorized() throws Exception {
        var member = enabledMember();
        var session = oidcSession(member);
        perform(post("/config/api/accounts/" + member.id() + "/disable"), auth.issueForPassword("127.0.0.1"))
                .andExpect(status().isOk());
        assertTrue(sessions.validate(session.getId(), Instant.now()).isEmpty(), "停用必须注销服务端会话");
        perform(get("/config/api/values"), session).andExpect(status().isUnauthorized());
        var denied = callback(start(null), null);
        assertEquals("/config?oidc=disabled", denied.getResponse().getRedirectedUrl());
        assertNull(denied.getResponse().getCookie(ConfigUiSecurityFilter.SESSION_COOKIE));
    }

    // 成员不能读取或修改整份配置及数据源，也不能同意使用协议。
    @Test
    void membersAreDeniedByDefault() throws Exception {
        var session = oidcSession(enabledMember());
        for (var builder : List.of(get("/config/api/values"), post("/config/api/values").content("{}"),
                get("/config/api/datasource"), post("/config/api/auth/agreement/accept"),
                post("/config/api/auth/agreement/revoke"), get("/config/api/accounts"))) {
            perform(builder, session).andExpect(status().isForbidden())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("成员不能使用这一项")));
        }
        perform(get("/config/api/auth/state"), session).andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(session.getAccountId()))
                .andExpect(jsonPath("$.role").value("MEMBER"))
                .andExpect(jsonPath("$.displayName").value("Example Member"))
                .andExpect(jsonPath("$.loginMethod").value("oidc"));
    }

    // 成员把地址编码或加路径参数，也不能读写配置或撤回协议；部署前缀不能改变这道限制。
    @ParameterizedTest
    @ValueSource(strings = {"GET /config/%61pi/values", "POST /config/%61pi/values",
            "GET /config;x=1/api/values", "GET /config/api;x=1/values", "GET /config/api/values;x=1",
            "GET /config//api/values", "GET /config/./api/values", "GET /config/x/../api/values",
            "GET /config%2Fapi%2Fvalues", "GET /config/%61pi/event-tokens", "GET /config/%61pi/datasource",
            "GET /config/%61pi/datasource/users", "GET /config/%61pi/accounts",
            "POST /config/%61pi/auth/agreement/revoke"})
    void memberCannotBypassApiGuardWithAlternatePaths(String target) throws Exception {
        var session = oidcSession(enabledMember());
        for (String prefix : List.of("", "/app")) {
            perform(alternatePath(target, prefix), session).andExpect(result ->
                    assertTrue(List.of(400, 403).contains(result.getResponse().getStatus()),
                            target + " prefix=" + prefix + " status=" + result.getResponse().getStatus()));
        }
        assertEquals(ConfigUiAgreement.VERSION, properties.getConfigUi().getAgreement().getAcceptedVersion());
        assertFalse(Files.readString(configFile).contains("enabled: false"));
    }

    // 未登录的人改写地址也不能碰到配置处理器，避免路径修复意外放开匿名访问。
    @ParameterizedTest
    @ValueSource(strings = {"GET /config/%61pi/values", "POST /config/%61pi/values",
            "GET /config;x=1/api/values", "GET /config/api;x=1/values", "GET /config/api/values;x=1",
            "GET /config//api/values", "GET /config/./api/values", "GET /config/x/../api/values",
            "GET /config%2Fapi%2Fvalues", "GET /config/%61pi/event-tokens", "GET /config/%61pi/datasource",
            "GET /config/%61pi/datasource/users", "GET /config/%61pi/accounts",
            "POST /config/%61pi/auth/agreement/revoke"})
    void anonymousCannotBypassApiGuardWithAlternatePaths(String target) throws Exception {
        for (String prefix : List.of("", "/app")) {
            mvc.perform(alternatePath(target, prefix)).andExpect(result ->
                    assertTrue(List.of(400, 401, 403).contains(result.getResponse().getStatus())));
        }
    }

    // 单个 IP 刷统一登录不能挡住本地管理员从相同或其它 IP 输入正确口令。
    @Test
    void oidcStartsNeverConsumePasswordLoginBudget() throws Exception {
        for (int i = 0; i < 25; i++) mvc.perform(fromIp(get("/config/api/oidc/login"), "127.0.0.9"));
        passwordLogin("127.0.0.8");
        passwordLogin("127.0.0.9");
    }

    // 统一登录回调错误不能锁住这个 IP 的口令登录，包括用户在账户中心取消登录。
    @Test
    void oidcCallbackErrorsNeverLockPasswordLogin() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(get("/config/api/oidc/callback").param("error", "access_denied"));
        }
        passwordLogin("127.0.0.1");
    }

    // 单个 IP 的统一登录发起次数必须先受限，给其它 IP 留下全局额度。
    @Test
    void oidcStartLimitsOneIpBeforeGlobalBudgetIsExhausted() throws Exception {
        int started = 0;
        int busy = 0;
        for (int i = 0; i < 25; i++) {
            var result = mvc.perform(fromIp(get("/config/api/oidc/login"), "127.0.0.9")).andReturn();
            String location = result.getResponse().getRedirectedUrl();
            if ("/config?oidc=busy".equals(location)) busy++;
            else {
                assertTrue(location.startsWith(ISSUER + "/authorize?"));
                started++;
            }
        }
        assertTrue(started > 0 && started < 20, "一个 IP 必须先于全局 20 次额度被限制");
        assertTrue(busy > 0);
        mvc.perform(fromIp(get("/config/api/oidc/login"), "127.0.0.8"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith(ISSUER + "/authorize?")));
    }

    // 令牌脚本反复调用不该新增会话、写运行状态或制造登录日志，状态仍应识别本地管理员。
    @Test
    void tokenModeRequestsDoNotIssueSessionsOrWriteStateOrLogLogin() throws Exception {
        auth.applyPasswordHash(null);
        Logger logger = (Logger) LoggerFactory.getLogger(ConfigUiAuthService.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            for (int i = 0; i < 5; i++) {
                mvc.perform(get("/config/api/auth/state").header("Authorization", "Bearer test-operator"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(true))
                        .andExpect(jsonPath("$.role").value("ADMIN"))
                        .andExpect(jsonPath("$.accountId").value(LOCAL_ID))
                        .andExpect(jsonPath("$.displayName").value("本地管理员"))
                        .andExpect(jsonPath("$.loginMethod").value("operator-token"))
                        .andExpect(result -> assertNull(result.getResponse().getCookie(ConfigUiSecurityFilter.SESSION_COOKIE)));
            }
            assertEquals(0, sessions.revokeAll(), "令牌请求不能新增会话");
            assertFalse(Files.exists(directory.resolve("state.json")), "令牌请求不能写状态文件");
            assertTrue(appender.list.stream().noneMatch(event -> event.getFormattedMessage().contains("配置界面登录")));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    // 已登录成员也能切换登录方式，公开登录口应与匿名请求有相同结果。
    @Test
    void memberCanUsePublicLoginEndpointsAndLoadPages() throws Exception {
        var session = oidcSession(enabledMember());
        perform(post("/config/api/auth/login").content("{\"password\":\"example password\"}"), session)
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        perform(get("/config/api/oidc/login"), session).andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith(ISSUER + "/authorize?")));
        perform(get("/config/api/oidc/callback"), session).andExpect(redirectedUrl("/config?oidc=failed"));
        for (String path : List.of("/config/api/auth/passkey/login/options", "/config/api/auth/passkey/login/verify")) {
            int anonymousStatus = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}")
                            .header("Host", "console.example.com").header("Origin", "https://console.example.com").secure(true))
                    .andReturn().getResponse().getStatus();
            perform(post(path).content("{}"), session).andExpect(status().is(anonymousStatus));
            assertNotEquals(403, anonymousStatus);
        }
        for (String path : List.of("/config", "/config/assets/core.js")) {
            perform(get(path), session).andExpect(status().isOk());
        }
    }

    // 协议确认方式保留旧版可读的通道名，确认人单独保存，统一登录管理员显示自己的名字。
    @Test
    void agreementStoresChannelSeparatelyFromActor() throws Exception {
        var local = auth.issueForPassword("127.0.0.1");
        perform(post("/config/api/auth/agreement/accept"), local).andExpect(status().isOk());
        var saved = new ConfigurationFileService(configFile).read();
        assertEquals("password", saved.get("novabot.core.config-ui.agreement.accepted-by"));
        assertEquals("本地管理员", saved.get("novabot.core.config-ui.agreement.accepted-actor"));
        var member = enabledMember();
        accounts.role(member.id(), Role.ADMIN);
        perform(post("/config/api/auth/agreement/accept"), oidcSession(accounts.require(member.id())))
                .andExpect(status().isOk());
        saved = new ConfigurationFileService(configFile).read();
        assertEquals("oidc", saved.get("novabot.core.config-ui.agreement.accepted-by"));
        assertEquals("Example Member", saved.get("novabot.core.config-ui.agreement.accepted-actor"));
        perform(post("/config/api/auth/agreement/revoke"), local).andExpect(status().isOk());
        assertNull(new ConfigurationFileService(configFile).read()
                .get("novabot.core.config-ui.agreement.accepted-actor"));
    }

    // 成员反复登录不能挤掉管理员，退出全部设备也只能影响自己。
    @Test
    void sessionLimitAndLogoutAllAreScopedToAccount() throws Exception {
        var local = auth.issueForPassword("127.0.0.1");
        var member = enabledMember();
        var first = oidcSession(member);
        ConfigUiSession last = first;
        for (int i = 0; i < 40; i++) last = oidcSession(member);
        assertTrue(auth.validate(first.getId()).isEmpty());
        assertTrue(auth.validate(local.getId()).isPresent());
        perform(post("/config/api/auth/logout").param("all", "true"), last).andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(32));
        assertTrue(auth.validate(last.getId()).isEmpty());
        assertTrue(auth.validate(local.getId()).isPresent());
    }

    // 改口令或二次验证时，换标识并注销其余会话也不能影响别人的账号。
    @Test
    void rotatingLocalSessionPreservesMemberSessions() {
        var local = auth.issueForPassword("127.0.0.1");
        var otherLocal = auth.issueForPassword("127.0.0.1");
        var memberSession = oidcSession(enabledMember());
        var rotated = auth.rotateSession(local.getId());
        assertEquals(1, rotated.revoked());
        assertTrue(auth.validate(otherLocal.getId()).isEmpty());
        assertTrue(auth.validate(local.getId()).isEmpty());
        assertTrue(auth.validate(memberSession.getId()).isPresent());
        assertTrue(auth.validate(rotated.session().getId()).isPresent());
    }

    // 已启用或停用的身份不能直接并入本地管理员，避免误吞已有账号。
    @Test
    void bindingRejectsIdentityOwnedByEnabledOrDisabledAccount() throws Exception {
        var member = enabledMember();
        var local = auth.issueForPassword("127.0.0.1");
        assertEquals("/config?oidc=identity_in_use", callback(start(local), null).getResponse().getRedirectedUrl());
        accounts.status(member.id(), Status.DISABLED);
        assertEquals("/config?oidc=identity_in_use", callback(start(local), null).getResponse().getRedirectedUrl());
        assertNull(accounts.require(LOCAL_ID).issuer());
    }

    // 待授权身份绑定时并入同一个本地管理员，跨站回跳没有管理员 Cookie 也能辨认发起人。
    @Test
    void bindingMergesOnlyNeverEnabledPendingIdentityAndSurvivesMissingSessionCookie() throws Exception {
        callback(start(null), null);
        var pending = member();
        var local = auth.issueForPassword("127.0.0.1");
        var result = callback(start(local), null);
        assertEquals("/config?oidc=bound", result.getResponse().getRedirectedUrl());
        assertTrue(accounts.find(pending.id()).isEmpty());
        assertEquals(ISSUER, accounts.require(LOCAL_ID).issuer());
        var loggedIn = callback(start(null), null);
        var session = auth.validate(loggedIn.getResponse().getCookie(ConfigUiSecurityFilter.SESSION_COOKIE).getValue()).orElseThrow();
        assertEquals(LOCAL_ID, session.getAccountId());
        perform(delete("/config/api/accounts/local/binding"), local).andExpect(status().isOk());
        assertTrue(auth.validate(session.getId()).isEmpty());
        assertTrue(auth.validate(local.getId()).isPresent());
    }

    // 统一登录或十分钟前的本地会话不能修改身份绑定、管理员名单或账户中心地址。
    @Test
    void sensitiveChangesRequireFreshLocalSession() throws Exception {
        var member = enabledMember();
        accounts.role(member.id(), Role.ADMIN);
        var federated = oidcSession(accounts.require(member.id()));
        var stale = sessions.issue("127.0.0.1", Instant.now().minus(Duration.ofMinutes(11)), ConfigUiSession.Channel.PASSWORD);
        for (var session : List.of(federated, stale)) {
            perform(post("/config/api/accounts/local/bind"), session).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(ConfigUiAccess.LOCAL_REQUIRED));
            perform(delete("/config/api/accounts/local/binding"), session).andExpect(status().isForbidden());
            perform(post("/config/api/accounts/" + member.id() + "/role").content("{\"role\":\"MEMBER\"}"), session)
                    .andExpect(status().isForbidden());
            perform(post("/config/api/values").content("{\"novabot.core.config-ui.oidc.issuer\":\"https://other.example.com\"}"), session)
                    .andExpect(status().isForbidden());
        }
        var local = auth.issueForPassword("127.0.0.1");
        perform(post("/config/api/accounts/" + member.id() + "/role").content("{\"role\":\"MEMBER\"}"), local)
                .andExpect(status().isOk());
        perform(post("/config/api/values").content("{\"novabot.core.config-ui.oidc.issuer\":\"https://other.example.com\"}"), local)
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        assertTrue(Files.readString(configFile).contains("https://other.example.com"));
        assertEquals(200, start(local).response().getResponse().getStatus());
    }

    // 绑定发起后退出或过期，回跳不能继续借用那次管理员身份。
    @Test
    void bindingRechecksInitiatingSessionAtCallback() throws Exception {
        var local = auth.issueForPassword("127.0.0.1");
        var flow = start(local);
        auth.logout(local.getId());
        assertEquals("/config?oidc=local_required", callback(flow, null).getResponse().getRedirectedUrl());
        assertNull(accounts.require(LOCAL_ID).issuer());
        assertEquals(0, provider.tokenRequests);
    }

    // 本地管理员永远不能被停用、删除或降级，防止把恢复入口锁死。
    @Test
    void localAdministratorCannotBeDisabledDeletedOrDemoted() throws Exception {
        var local = auth.issueForPassword("127.0.0.1");
        perform(post("/config/api/accounts/" + LOCAL_ID + "/disable"), local).andExpect(status().isBadRequest());
        perform(delete("/config/api/accounts/" + LOCAL_ID), local).andExpect(status().isBadRequest());
        perform(post("/config/api/accounts/" + LOCAL_ID + "/role").content("{\"role\":\"MEMBER\"}"), local)
                .andExpect(status().isBadRequest());
        assertEquals(Role.ADMIN, accounts.require(LOCAL_ID).role());
        assertEquals(Status.ENABLED, accounts.require(LOCAL_ID).status());
    }

    // 角色变更下一个请求立即生效，不能沿用会话签发时的管理员资格。
    @Test
    void requestsReadCurrentRole() throws Exception {
        var member = enabledMember();
        accounts.role(member.id(), Role.ADMIN);
        var session = oidcSession(accounts.require(member.id()));
        perform(get("/config/api/accounts"), session).andExpect(status().isOk());
        accounts.role(member.id(), Role.MEMBER);
        perform(get("/config/api/accounts"), session).andExpect(status().isForbidden());
    }

    // 管理员填错指定主播时整份名单不动，避免一次误填丢掉已有授权。
    @Test
    void invalidStreamerAssignmentsLeaveTheListUnchanged() throws Exception {
        var member = enabledMember();
        accounts.streamers(member.id(), List.of("bilibili:1"));
        var local = auth.issueForPassword("127.0.0.1");
        for (String invalid : List.of("streamer-old", ":1", "bilibili:", "bilibili:0", "bilibili:-1",
                "bilibili:1.5", "bilibili:9223372036854775808", "bilibili:1:2", " :1")) {
            perform(post("/config/api/accounts/" + member.id() + "/streamers")
                    .content(JSONObject.of("streamers", List.of("bilibili:2", invalid)).toJSONString()), local)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("指定主播格式不正确"));
            assertEquals(List.of("bilibili:1"), accounts.require(member.id()).streamers());
        }
    }

    // 老名单照常启动、原样显示，但旧字符串不能给成员任何主播权限。
    @Test
    void legacyStreamerAssignmentsAreListedButGrantNoAccess() throws Exception {
        var member = enabledMember();
        var record = ConfigUiAccountStore.json(member);
        record.put("streamers", List.of("streamer-old", "bilibili:bad", "bilibili:0"));
        state.write(ConfigUiAccountStore.NAMESPACE, data -> data.put(member.id(), record));
        assertTrue(state.trySave());
        var restored = new NovaStateStore(properties);
        restored.onApplicationReadyEvent();
        assertEquals(record.getList("streamers", String.class), new ConfigUiAccountStore(restored)
                .require(member.id()).streamers());
        perform(get("/config/api/accounts"), auth.issueForPassword("127.0.0.1"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("streamer-old")));
        perform(get("/config/api/datasource/users/bilibili/1"), oidcSession(accounts.require(member.id())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("没有这位主播"));
    }

    // 管理员收回主播后，成员原会话的下一次请求马上失去权限。
    @Test
    void streamerAssignmentChangesApplyWithoutLoggingInAgain() throws Exception {
        var member = enabledMember();
        accounts.streamers(member.id(), List.of("bilibili:1"));
        var session = oidcSession(accounts.require(member.id()));
        properties.getDatasource().setJsonPath(directory.resolve("datasource.json").toString());
        Files.writeString(directory.resolve("datasource.json"), "[{\"platform\":\"bilibili\",\"uid\":1,\"enabled\":false}]");
        perform(get("/config/api/datasource/users/bilibili/1"), session).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        perform(post("/config/api/accounts/" + member.id() + "/streamers").content("{\"streamers\":[]}"),
                auth.issueForPassword("127.0.0.1")).andExpect(status().isOk());
        perform(get("/config/api/datasource/users/bilibili/1"), session).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("没有这位主播"));
    }

    // 没有管理员同意协议时，启用成员也不能获发会话。
    @Test
    void memberLoginRequiresAdministratorAgreement() throws Exception {
        enabledMember();
        properties.getConfigUi().getAgreement().setAcceptedVersion(0);
        var result = callback(start(null), null);
        assertEquals("/config?oidc=agreement", result.getResponse().getRedirectedUrl());
        assertNull(result.getResponse().getCookie(ConfigUiSecurityFilter.SESSION_COOKIE));
    }

    // 关掉统一登录或处于启动令牌形态时，公开入口必须表现为不存在。
    @Test
    void disabledAndTokenModesHideBothEntrypoints() throws Exception {
        for (boolean tokenMode : List.of(false, true)) {
            if (tokenMode) {
                config.setEnabled(true);
                rebuildClient();
                auth.setOidcClient(client);
                auth.applyPasswordHash(null);
            }
            else {
                config.setEnabled(false);
                rebuildClient();
                auth.setOidcClient(client);
            }
            mvc.perform(get("/config/api/oidc/login")).andExpect(status().isNotFound());
            mvc.perform(get("/config/api/oidc/callback")).andExpect(status().isNotFound());
            var request = new MockHttpServletRequest();
            assertFalse(new ConfigUiAuthController(auth, null, properties).state(request).getBooleanValue("oidcEnabled"));
        }
    }

    // 没有关联 Cookie 或 Cookie 不匹配的浏览器不能消费另一浏览器的授权码。
    @Test
    void callbackRequiresBrowserCorrelationBeforeTokenExchange() throws Exception {
        var flow = start(null);
        mvc.perform(get("/config/api/oidc/callback").param("state", flow.state()).param("code", "code-1"))
                .andExpect(redirectedUrl("/config?oidc=failed"));
        assertEquals(0, provider.tokenRequests);
        callback(flow, null);
        assertEquals(1, provider.tokenRequests);
        assertTrue(flow.cookie().isHttpOnly());
        assertEquals("Lax", flow.cookie().getAttribute("SameSite"));
    }

    // 账户中心带回的脚本和任意错误原文只能映射为固定提示。
    @Test
    void providerErrorTextNeverReachesLoginPage() throws Exception {
        var flow = start(null);
        var result = mvc.perform(get("/config/api/oidc/callback").cookie(flow.cookie())
                .param("state", flow.state()).param("error", "access_denied")
                .param("error_description", "<script>alert('provider-text')</script>"))
                .andExpect(redirectedUrl("/config?oidc=denied")).andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("provider-text"));
        var page = mvc.perform(get("/config?oidc=denied")).andExpect(status().isOk()).andReturn();
        assertFalse(page.getResponse().getContentAsString().contains("provider-text"));
        assertTrue(page.getResponse().getContentAsString().contains("统一登录已取消，请重试"));
        var clientError = start(null);
        mvc.perform(get("/config/api/oidc/callback").cookie(clientError.cookie())
                        .param("state", clientError.state()).param("error", "unauthorized_client")
                        .param("error_description", "<script>alert('provider-text')</script>"))
                .andExpect(redirectedUrl("/config?oidc=configuration"));
        provider.tokenError = "invalid_client";
        assertEquals("/config?oidc=configuration", callback(start(null), null).getResponse().getRedirectedUrl());
    }

    // 主播分配、身份和状态必须原子落盘，删除仅允许待授权或停用的成员。
    @Test
    void persistsAccountsAndRestrictsDeletion() throws Exception {
        var member = enabledMember();
        var local = auth.issueForPassword("127.0.0.1");
        perform(post("/config/api/accounts/" + member.id() + "/streamers").content("{\"streamers\":[\"bilibili:1\"]}"), local)
                .andExpect(status().isOk());
        perform(delete("/config/api/accounts/" + member.id()), local).andExpect(status().isBadRequest());
        var restored = new NovaStateStore(properties);
        try {
            restored.onApplicationReadyEvent();
            var restoredMember = new ConfigUiAccountStore(restored).require(member.id());
            assertEquals(List.of("bilibili:1"), restoredMember.streamers());
            assertEquals(ISSUER, restoredMember.issuer());
            assertEquals("subject-17", restoredMember.subject());
            assertEquals(Status.ENABLED, restoredMember.status());
        } finally {
            restored.onContextClosedEvent();
        }
        String stored = Files.readString(directory.resolve("state.json"));
        assertTrue(stored.contains("config-ui-accounts"));
        assertFalse(stored.contains("refresh-1"));
        assertFalse(stored.contains("access-1"));
        perform(post("/config/api/accounts/" + member.id() + "/disable"), local).andExpect(status().isOk());
        perform(delete("/config/api/accounts/" + member.id()), local).andExpect(status().isOk());
        assertTrue(accounts.find(member.id()).isEmpty());
    }

    // 相同 email 的不同 subject 必须是两个账号，不能靠邮箱冒认管理员。
    @Test
    void emailDoesNotIdentifyAccount() {
        var first = enabledMember();
        var second = accounts.register(new OidcClient.Identity(ISSUER, "other-sub", first.displayName(), first.email()), Instant.now());
        assertNotEquals(first.id(), second.id());
        assertEquals(Status.PENDING, second.status());
    }

    // 通用保存日志必须能回答是谁改了这份配置，且名字不能注入新日志行。
    @Test
    void configurationLogIncludesActor() throws Exception {
        var member = enabledMember();
        accounts.role(member.id(), Role.ADMIN);
        var session = oidcSession(accounts.require(member.id()));
        Logger logger = (Logger) LoggerFactory.getLogger(ConfigurationFileService.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var file = new ConfigurationFileService(configFile);
            var request = new MockHttpServletRequest();
            request.setAttribute(ConfigUiAccess.ACCOUNT, accounts.require(member.id()));
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                    new org.springframework.web.context.request.ServletRequestAttributes(request));
            file.write(Map.of("novabot.core.config-ui.oidc.issuer", "https://new.example.com"));
            assertTrue(appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(line -> line.contains("配置界面已更新") && line.contains(member.id()) && line.contains("Example Member")));
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
            logger.detachAppender(appender);
            appender.stop();
        }
        assertNotNull(session);
    }

    // 十分钟前的本地会话不能提升管理员，必须重新完成本地登录。
    @Test
    void staleLocalSessionCannotGrantAdministrator() throws Exception {
        var member = enabledMember();
        var stale = sessions.issue("127.0.0.1", Instant.now().minus(Duration.ofMinutes(10)), ConfigUiSession.Channel.PASSWORD);
        perform(post("/config/api/accounts/" + member.id() + "/role").content("{\"role\":\"ADMIN\"}"), stale)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value(ConfigUiAccess.LOCAL_REQUIRED));
        assertEquals(Role.MEMBER, accounts.require(member.id()).role());
    }

    // 状态文件暂时写不了时，本地口令仍须能作为恢复入口使用。
    @Test
    void localLoginSurvivesStateWriteFailure() {
        var unavailable = mock(NovaStateStore.class);
        when(unavailable.namespace(ConfigUiAccountStore.NAMESPACE)).thenReturn(new JSONObject());
        when(unavailable.trySave()).thenReturn(false);
        auth.setAccounts(new ConfigUiAccountStore(unavailable));
        var local = auth.issueForPassword("127.0.0.1");
        assertTrue(auth.validate(local.getId()).isPresent());
    }

    // 成员在授权码兑换途中被停用，回跳不能重新给他签出会话。
    @Test
    void disablingDuringProviderExchangeCannotResurrectSession() throws Exception {
        var member = enabledMember();
        provider.claims = claims -> accounts.status(member.id(), Status.DISABLED);
        var result = callback(start(null), null);
        assertEquals("/config?oidc=disabled", result.getResponse().getRedirectedUrl());
        assertNull(result.getResponse().getCookie(ConfigUiSecurityFilter.SESSION_COOKIE));
    }

    // 多个成员挤满全局会话容量，也必须给本地管理员保留登录空间。
    @Test
    void globalSessionLimitReservesLocalAdministratorCapacity() {
        Instant now = Instant.now();
        for (int i = 0; i < 992; i++) {
            sessions.issueForAccount("127.0.0.1", now, ConfigUiSession.Channel.OIDC, null, "member-" + i, null);
        }
        assertThrows(IllegalStateException.class, () -> sessions.issueForAccount("127.0.0.1", now,
                ConfigUiSession.Channel.OIDC, null, "extra-member", null));
        var local = auth.issueForPassword("127.0.0.1");
        assertTrue(auth.validate(local.getId()).isPresent());
    }

    // 启动令牌 Cookie 不能把一次很早的登录伪装成刚刚完成的本地验证。
    @Test
    void tokenCookieAloneIsNotFreshLocalLogin() throws Exception {
        auth.applyPasswordHash(null);
        mvc.perform(post("/config/api/values").cookie(new Cookie("starbot_config_token", "test-operator"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"novabot.core.config-ui.oidc.issuer\":\"https://other.example.com\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("统一登录要先设登录口令才能用：请先设口令，用口令登录后再改这几项"));
        mvc.perform(post("/config/api/values").param("token", "")
                        .cookie(new Cookie("starbot_config_token", "test-operator"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"novabot.core.config-ui.oidc.issuer\":\"https://other.example.com\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("统一登录要先设登录口令才能用：请先设口令，用口令登录后再改这几项"));
        mvc.perform(post("/config/api/values").param("token", "test-operator")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"novabot.core.config-ui.oidc.issuer\":\"https://other.example.com\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
    }

    // 口令形态的脚本重复使用启动令牌，不应写最近登录时间或制造登录日志，但每次保留审计。
    @Test
    void operatorRedemptionDoesNotWriteLoginState() throws Exception {
        properties.getConfigUi().getAuth().setOperatorToken(true);
        var authLog = (Logger) LoggerFactory.getLogger(ConfigUiAuthService.class);
        var filterLog = (Logger) LoggerFactory.getLogger(ConfigUiSecurityFilter.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        authLog.addAppender(appender);
        filterLog.addAppender(appender);
        try {
            assertFalse(Files.exists(directory.resolve("state.json")));
            for (int i = 0; i < 3; i++) {
                mvc.perform(get("/config/api/values").header("Authorization", "Bearer test-operator"))
                        .andExpect(status().isOk());
            }
            assertFalse(Files.exists(directory.resolve("state.json")), "Bearer 兑换不能写 state.json");
            assertNull(accounts.require(LOCAL_ID).lastLoginAt());
            assertEquals(0, appender.list.stream().filter(e -> e.getFormattedMessage().contains("配置界面登录:")).count());
            assertEquals(3, appender.list.stream().filter(e -> e.getFormattedMessage().contains("以启动令牌换取了会话")).count());
        } finally {
            authLog.detachAppender(appender);
            filterLog.detachAppender(appender);
            appender.stop();
        }
    }

    // 降级后撤回又同意，遗留的确认人不能冒充新一次同意的签名者。
    @Test
    void agreementActorMustBelongToCurrentAcceptance() throws Exception {
        var local = auth.issueForPassword("127.0.0.1");
        perform(post("/config/api/auth/agreement/accept"), local).andExpect(status().isOk());
        perform(get("/config/api/values"), local)
                .andExpect(jsonPath("$.values['novabot.core.config-ui.agreement.accepted-actor']").value("本地管理员"));
        new ConfigurationFileService(configFile).write(Map.of(
                "novabot.core.config-ui.agreement.accepted-at", "2026-10-20T12:00:00+08:00",
                "novabot.core.config-ui.agreement.accepted-by", "operator-token"));
        perform(get("/config/api/values"), local)
                .andExpect(jsonPath("$.values['novabot.core.config-ui.agreement.accepted-actor']").doesNotExist());
    }

    // 未开统一登录的机器不能暴露后台登出口，IP 白名单仍须先挡住账户中心的来源。
    @Test
    void backchannelEntranceRespectsModeAndAllowlist() throws Exception {
        mvc.perform(post("/config/api/oidc/backchannel-logout").with(r -> { r.setRemoteAddr("192.0.2.1"); return r; }))
                .andExpect(status().isForbidden());
        auth.applyPasswordHash(null);
        mvc.perform(post("/config/api/oidc/backchannel-logout")).andExpect(status().isNotFound());
        auth.applyPasswordHash(LowIterationPasswordHash.of("example password"));
        config.setEnabled(false);
        rebuildClient();
        auth.setOidcClient(client);
        mvc.perform(post("/config/api/oidc/backchannel-logout")).andExpect(status().isNotFound());
    }

    // 令牌形态的显式 Bearer 仍可修改统一登录配置。
    @Test
    void explicitBearerCanSaveOidcSettingsInTokenMode() throws Exception {
        auth.applyPasswordHash(null);
        mvc.perform(post("/config/api/values").header("Authorization", "Bearer test-operator")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"novabot.core.config-ui.oidc.issuer\":\"https://other.example.com\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
    }

    // HTTPS 回跳必须保留安全 Cookie，且路径跟随部署前缀。
    @Test
    void browserCookiesFollowSecureConnectionAndDeploymentPrefix() throws Exception {
        var start = mvc.perform(get("/app/config/api/oidc/login").contextPath("/app").secure(true)
                .with(request -> {
                    ((org.springframework.mock.web.MockServletContext) request.getServletContext()).setContextPath("/app");
                    request.setScheme("https");
                    request.setServerName("console.example.com");
                    request.setServerPort(443);
                    return request;
                })).andExpect(status().isFound()).andReturn();
        var browserCookie = start.getResponse().getCookie(ConfigUiOidcController.COOKIE);
        assertTrue(browserCookie.getSecure());
        assertEquals("/app/config", browserCookie.getPath());
        var query = query(URI.create(start.getResponse().getRedirectedUrl()).getRawQuery());
        provider.nonce = query.get("nonce");
        provider.challenge = query.get("code_challenge");
        provider.redirect = query.get("redirect_uri");
        assertEquals("https://console.example.com/app/config/api/oidc/callback", provider.redirect);
        enabledMember();
        var result = mvc.perform(get("/app/config/api/oidc/callback").contextPath("/app").secure(true)
                        .param("state", query.get("state")).param("code", "code-1").param("iss", ISSUER).cookie(browserCookie))
                .andExpect(status().isOk()).andReturn();
        var sessionCookie = result.getResponse().getCookie(ConfigUiSecurityFilter.SESSION_COOKIE);
        assertTrue(sessionCookie.getSecure());
        assertEquals("/app/config", sessionCookie.getPath());
        assertTrue(result.getResponse().getContentAsString().contains("url=/app/config"));
    }

    // 绑定发起要让页面脚本读到跳转地址，不能让携带 CSRF 头的请求直接跨站跟随重定向。
    @Test
    void bindingStartReturnsNavigationUrlForBrowser() throws Exception {
        perform(post("/config/api/accounts/local/bind"), auth.issueForPassword("127.0.0.1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.authorizationUrl").value(org.hamcrest.Matchers.startsWith(ISSUER + "/authorize?")));
    }

    private ConfigUiAccount member() {
        return accounts.list().stream().filter(a -> !LOCAL_ID.equals(a.id())).findFirst().orElseThrow();
    }

    private ConfigUiAccount enabledMember() {
        var account = accounts.register(new OidcClient.Identity(ISSUER, "subject-17", "Example Member", "member@example.com"), Instant.now());
        return accounts.status(account.id(), Status.ENABLED);
    }

    private ConfigUiSession oidcSession(ConfigUiAccount account) {
        return auth.issueForOidc(account.id(), "127.0.0.1", new OidcClient.Login(
                new OidcClient.Identity(ISSUER, "subject-17", "Example Member", "member@example.com", "sid-1"),
                new OidcClient.Tokens("access-1", "id-1", "refresh-1", 300L), "nonce-1"));
    }

    private org.springframework.test.web.servlet.ResultActions perform(MockHttpServletRequestBuilder builder, ConfigUiSession session) throws Exception {
        return mvc.perform(builder.contentType(MediaType.APPLICATION_JSON)
                .header("Host", "console.example.com").header("Origin", "https://console.example.com")
                .secure(true).with(request -> { request.setScheme("https"); request.setServerPort(443); return request; })
                .cookie(new Cookie(ConfigUiSecurityFilter.SESSION_COOKIE, session.getId()))
                .header(ConfigUiSecurityFilter.CSRF_HEADER, session.getCsrfToken()));
    }

    private MockHttpServletRequestBuilder alternatePath(String target, String prefix) {
        String[] parts = target.split(" ", 2);
        URI uri = URI.create(prefix + parts[1]);
        return (parts[0].equals("POST") ? post(uri) : get(uri)).contextPath(prefix)
                .header("Host", "console.example.com").header("Origin", "https://console.example.com")
                .contentType(MediaType.APPLICATION_JSON).content("{\"novabot.core.push.enabled\":\"false\"}");
    }

    private MockHttpServletRequestBuilder fromIp(MockHttpServletRequestBuilder builder, String ip) {
        return builder.with(request -> { request.setRemoteAddr(ip); return request; });
    }

    private void passwordLogin(String ip) throws Exception {
        mvc.perform(fromIp(post("/config/api/auth/login"), ip).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"example password\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
    }

    private Flow start(ConfigUiSession local) throws Exception {
        var response = local == null ? mvc.perform(get("/config/api/oidc/login")).andReturn()
                : perform(post("/config/api/accounts/local/bind"), local).andReturn();
        assertEquals(local == null ? 302 : 200, response.getResponse().getStatus());
        String url = local == null ? response.getResponse().getRedirectedUrl()
                : JSONObject.parseObject(response.getResponse().getContentAsString()).getString("authorizationUrl");
        var query = query(URI.create(url).getRawQuery());
        provider.nonce = query.get("nonce");
        provider.challenge = query.get("code_challenge");
        provider.redirect = query.get("redirect_uri");
        return new Flow(query.get("state"), response.getResponse().getCookie(ConfigUiOidcController.COOKIE), response);
    }

    private MvcResult callback(Flow flow, Cookie session) throws Exception {
        var builder = get("/config/api/oidc/callback").param("state", flow.state()).param("code", "code-1").param("iss", ISSUER)
                .cookie(flow.cookie());
        if (session != null) builder.cookie(session);
        return mvc.perform(builder).andReturn();
    }

    private record Flow(String state, Cookie cookie, MvcResult response) { }
}
