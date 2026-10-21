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
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.frostnova.nova.core.util.IpMatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
        properties.getConfigUi().getAgreement().setAcceptedBy("本地管理员 (password)");
        state = new NovaStateStore(properties);
        accounts = new ConfigUiAccountStore(state);
        sessions = new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(24));
        var throttle = new LoginThrottle(100, Duration.ofMinutes(15));
        auth = new ConfigUiAuthService(properties.getConfigUi().getAuth(), sessions, throttle, null);
        auth.setAccounts(accounts);
        auth.setOidcClient(client);
        configFile = directory.resolve("application.yml");
        Files.writeString(configFile, "novabot:\n  core:\n    config-ui:\n      oidc:\n        issuer: https://accounts.example.com/tenant\n");
        var files = new ConfigurationFileService(configFile);
        var filter = new ConfigUiSecurityFilter("test-operator", new IpMatcher(List.of("127.0.0.1")), auth,
                properties.getConfigUi().getAuth(), properties.getConfigUi().getAgreement());
        mvc = MockMvcBuilders.standaloneSetup(new ConfigUiAuthController(auth, files, properties),
                        new ConfigUiAccountController(accounts, auth, client),
                        new ConfigUiOidcController(client, auth, accounts, throttle, properties), values(files))
                .addFilters(filter).build();
    }

    @SuppressWarnings("unchecked")
    private ConfigUiController values(ConfigurationFileService files) {
        var metadata = mock(ConfigurationMetadataService.class);
        when(metadata.getKnownTypes()).thenReturn(Map.of("novabot.core.config-ui.oidc.issuer", "java.lang.String",
                "novabot.core.config-ui.oidc.enabled", "java.lang.Boolean"));
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

    // 权限层开放前，成员不能读取或修改配置及数据源，也不能同意使用协议。
    @Test
    void membersAreDeniedByDefault() throws Exception {
        var session = oidcSession(enabledMember());
        for (var builder : List.of(get("/config/api/values"), post("/config/api/values").content("{}"),
                get("/config/api/datasource"), post("/config/api/auth/agreement/accept"),
                post("/config/api/auth/agreement/revoke"), get("/config/api/accounts"))) {
            perform(builder, session).andExpect(status().isForbidden())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("成员暂时不能使用这一项")));
        }
        perform(get("/config/api/auth/state"), session).andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(session.getAccountId()))
                .andExpect(jsonPath("$.role").value("MEMBER"))
                .andExpect(jsonPath("$.displayName").value("Example Member"))
                .andExpect(jsonPath("$.loginMethod").value("oidc"));
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
        perform(post("/config/api/accounts/" + member.id() + "/streamers").content("{\"streamers\":[\"streamer-1\"]}"), local)
                .andExpect(status().isOk());
        perform(delete("/config/api/accounts/" + member.id()), local).andExpect(status().isBadRequest());
        var restored = new NovaStateStore(properties);
        try {
            restored.onApplicationReadyEvent();
            var restoredMember = new ConfigUiAccountStore(restored).require(member.id());
            assertEquals(List.of("streamer-1"), restoredMember.streamers());
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
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value(ConfigUiAccess.LOCAL_REQUIRED));
        mvc.perform(post("/config/api/values").param("token", "test-operator")
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
                .cookie(new Cookie(ConfigUiSecurityFilter.SESSION_COOKIE, session.getId()))
                .header(ConfigUiSecurityFilter.CSRF_HEADER, session.getCsrfToken()));
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
