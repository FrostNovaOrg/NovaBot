package org.frostnova.nova.core.config.ui.auth;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.Cookie;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.*;
import org.frostnova.nova.core.config.ui.auth.oidc.*;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.util.IpMatcher;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OidcSessionRefreshServiceTest extends OidcTestSupport {
    @TempDir Path directory;
    private ConfigUiSessionStore sessions;
    private ConfigUiAccountStore accounts;
    private ConfigUiAuthService auth;
    private OidcSessionRefreshService service;
    private MockMvc mvc;
    private ConfigUiSession session;

    @BeforeEach
    void console() {
        var properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(directory.resolve("live.json").toString());
        var settings = properties.getConfigUi().getAuth();
        settings.setPassword(LowIterationPasswordHash.of("example password"));
        settings.setTotp(false);
        sessions = new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(24));
        accounts = new ConfigUiAccountStore(new NovaStateStore(properties));
        auth = new ConfigUiAuthService(settings, sessions, new LoginThrottle(5, Duration.ofMinutes(15)), null, clock::instant);
        auth.setAccounts(accounts);
        auth.setOidcClient(client);
        service = new OidcSessionRefreshService(sessions, auth, client, clock);
        var agreement = properties.getConfigUi().getAgreement();
        agreement.setAcceptedVersion(ConfigUiAgreement.VERSION);
        agreement.setAcceptedBy("password");
        mvc = MockMvcBuilders.standaloneSetup(new ConfigUiAuthController(auth, null, properties))
                .addFilters(new ConfigUiSecurityFilter("example-token", new IpMatcher(List.of("127.0.0.1")), auth, settings, agreement))
                .build();
        var login = complete(begin());
        var account = accounts.register(login.identity(), clock.instant());
        accounts.status(account.id(), ConfigUiAccount.Status.ENABLED);
        session = auth.issueForOidc(account.id(), "127.0.0.1", login);
    }

    @AfterEach
    void close() { service.close(); }

    private void round() throws Exception {
        clock.now = clock.instant().plus(Duration.ofMinutes(5));
        service.checkNow();
        await(() -> !session.oidcState().refreshing.get());
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "等待会话复核完成");
    }

    // 账户中心停人后下一轮必须撤销服务端会话，下一个控制台请求不能继续使用。
    @Test
    void rejectedRefreshRevokesSessionAndNextRequestIs401() throws Exception {
        provider.tokenError = "invalid_grant";
        round();
        assertTrue(sessions.validate(session.getId(), clock.instant()).isEmpty());
        mvc.perform(get("/config/api/auth/state").cookie(new Cookie(ConfigUiSecurityFilter.SESSION_COOKIE, session.getId())))
                .andExpect(status().isOk()); // auth/state is public; a protected request must reject the same cookie.
        mvc.perform(get("/config/api/values").cookie(new Cookie(ConfigUiSecurityFilter.SESSION_COOKIE, session.getId())))
                .andExpect(status().isUnauthorized());
    }

    // 网络、超时、限流、服务端故障、坏签名和其它响应错误不能把正常成员一起踢下线。
    @ParameterizedTest
    @ValueSource(strings = {"connection", "timeout", "429", "503", "temporarily_unavailable", "signature", "aud", "malformed", "other"})
    void transientFailuresKeepSessionAndRetry(String failure) throws Exception {
        switch (failure) {
            case "connection" -> provider.tokenUnavailable = true;
            case "timeout" -> provider.tokenTimeout = true;
            case "429", "503" -> provider.tokenStatus = Integer.parseInt(failure);
            case "temporarily_unavailable" -> provider.tokenError = failure;
            case "signature" -> provider.signingKey = key("EdDSA");
            case "aud" -> provider.claims = c -> c.put("aud", "other-client");
            case "malformed" -> provider.malformedTokenResponse = true;
            case "other" -> provider.tokenError = "unexpected_error";
        }
        int requests = provider.tokenRequests;
        round();
        assertTrue(auth.validate(session.getId()).isPresent());
        round();
        assertTrue(auth.validate(session.getId()).isPresent());
        assertEquals(requests + 2, provider.tokenRequests, "下轮必须重试");
    }

    // 管理员填错客户端密钥时保留会话，连续同类错误只写第一行，恢复时写一行。
    @ParameterizedTest
    @ValueSource(strings = {"invalid_client", "unauthorized_client"})
    void clientConfigurationFailureKeepsSessionAndLogsOnce(String failure) throws Exception {
        var logger = (Logger) LoggerFactory.getLogger(OidcSessionRefreshService.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            provider.tokenError = failure;
            round();
            round();
            assertTrue(auth.validate(session.getId()).isPresent());
            assertEquals(1, appender.list.size());
            String line = appender.list.get(0).getFormattedMessage();
            assertTrue(line.contains("CONFIGURATION"));
            assertTrue(line.contains("Example Member"));
            assertTrue(line.contains(session.getAccountId()));
            assertFalse(line.contains("refresh-1"));
            assertFalse(line.contains("access-1"));
            provider.tokenError = null;
            round();
            assertEquals(2, appender.list.size());
            assertTrue(appender.list.get(1).getFormattedMessage().contains("恢复"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    // 账户中心续期时换了身份，不能继续把浏览器当作原来的成员。
    @ParameterizedTest
    @ValueSource(strings = {"iss", "sub"})
    void identityChangeRevokesSession(String claim) throws Exception {
        provider.claims = c -> c.put(claim, claim.equals("iss") ? "https://other.example.com" : "other-subject");
        round();
        assertTrue(auth.validate(session.getId()).isEmpty());
    }

    // 账户中心轮换续期令牌后下一次必须使用新值，省略新值时必须沿用已保存的值。
    @Test
    void refreshTokenRotationIsSavedAndReused() throws Exception {
        provider.refreshToken = "refresh-2";
        round();
        assertEquals("refresh-1", provider.lastRefreshToken);
        assertEquals("refresh-2", session.oidcLogin().tokens().refreshToken());
        provider.expectedRefreshToken = "refresh-2";
        provider.refreshToken = null;
        round();
        round();
        assertEquals("refresh-2", provider.lastRefreshToken);
        assertEquals("refresh-2", session.oidcLogin().tokens().refreshToken());
    }

    // 不给 refresh_token 的账户中心不能留下无法复核的长期会话，缺省 access_token 寿命为一小时。
    @ParameterizedTest
    @ValueSource(longs = {120, 3600})
    void missingRefreshTokenExpiresWithAccessToken(long seconds) {
        var previous = session.oidcLogin();
        var login = new OidcClient.Login(previous.identity(), new OidcClient.Tokens("access", null, null,
                seconds == 3600 ? null : seconds), previous.nonce());
        var limited = sessions.issueForAccount("127.0.0.1", clock.instant(), ConfigUiSession.Channel.OIDC,
                null, session.getAccountId(), login);
        assertTrue(sessions.validate(limited.getId(), clock.instant().plusSeconds(seconds - 1)).isPresent());
        assertTrue(sessions.validate(limited.getId(), clock.instant().plusSeconds(seconds)).isEmpty());
    }

    // 绑定身份被停用只撤销本地管理员的统一登录会话，口令仍是恢复入口。
    @Test
    void boundLocalAdministratorKeepsPasswordSession() throws Exception {
        accounts.status(session.getAccountId(), ConfigUiAccount.Status.DISABLED);
        accounts.delete(session.getAccountId());
        sessions.revoke(session.getId());
        accounts.bind(session.oidcLogin().identity());
        session = auth.issueForOidc(ConfigUiAccount.LOCAL_ID, "127.0.0.1", session.oidcLogin());
        var local = auth.issueForPassword("127.0.0.1");
        provider.tokenError = "invalid_grant";
        round();
        assertTrue(auth.validate(session.getId()).isEmpty());
        assertTrue(auth.validate(local.getId()).isPresent());
    }

    // 统一登录关闭后已有会话必须失效，本地登录不能连带注销。
    @Test
    void disablingOidcRevokesOnlyOidcSessions() {
        var local = auth.issueForPassword("127.0.0.1");
        auth.applyPasswordHash(null);
        service.checkNow();
        assertTrue(sessions.validate(session.getId(), clock.instant()).isEmpty());
        assertTrue(sessions.validate(local.getId(), clock.instant()).isPresent());
    }

    // 没有统一登录会话及尚未到期时都不应访问账户中心。
    @Test
    void noWorkBeforeDeadlineOrWithoutOidcSessions() {
        int requests = provider.tokenRequests;
        service.checkNow();
        assertEquals(requests, provider.tokenRequests);
        sessions.revoke(session.getId());
        clock.now = clock.instant().plus(Duration.ofMinutes(5));
        service.checkNow();
        assertEquals(requests, provider.tokenRequests);
    }

    // 同一设备在复核期间反复请求及更换 Cookie，不能重复兑换同一 refresh_token 或漏撤销新 Cookie。
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oneRefreshSurvivesCookieRotation(boolean rejected) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var requests = new AtomicInteger();
        provider.tokenHook = () -> {
            requests.incrementAndGet();
            entered.countDown();
            try { assertTrue(release.await(15, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
        };
        if (rejected) provider.tokenError = "invalid_grant";
        else provider.refreshToken = "refresh-rotated";
        clock.now = clock.instant().plus(Duration.ofMinutes(5));
        service.checkNow();
        try {
            assertTrue(entered.await(15, TimeUnit.SECONDS));
            var rotated = sessions.rotate(session.getId(), clock.instant()).orElseThrow();
            for (int i = 0; i < 10; i++) service.checkNow();
            assertEquals(1, requests.get());
            release.countDown();
            await(() -> !session.oidcState().refreshing.get());
            if (rejected) assertTrue(auth.validate(rotated.getId()).isEmpty());
            else {
                assertTrue(auth.validate(rotated.getId()).isPresent());
                assertEquals("refresh-rotated", rotated.oidcLogin().tokens().refreshToken());
            }
        } finally { release.countDown(); }
    }

    // 并发和换 Cookie 不能重复兑换同一令牌，正在执行时退出也不能重新复活会话。
    @Test
    void boundsConcurrencyAndSharesRefreshAcrossRotation() throws Exception {
        for (int i = 0; i < 8; i++) sessions.issueForAccount("127.0.0.1", clock.instant(), ConfigUiSession.Channel.OIDC,
                null, session.getAccountId(), session.oidcLogin());
        var entered = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        var active = new AtomicInteger();
        var maximum = new AtomicInteger();
        provider.tokenHook = () -> {
            int count = active.incrementAndGet();
            maximum.accumulateAndGet(count, Math::max);
            entered.countDown();
            try { assertTrue(release.await(15, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            finally { active.decrementAndGet(); }
        };
        clock.now = clock.instant().plus(Duration.ofMinutes(5));
        service.checkNow();
        try {
            assertTrue(entered.await(15, TimeUnit.SECONDS));
            var running = sessions.oidcSessions(clock.instant()).stream().filter(s -> s.oidcState().refreshing.get()).toList();
            assertEquals(4, running.size());
            var original = running.get(0);
            var rotated = sessions.rotate(original.getId(), clock.instant()).orElseThrow();
            assertSame(original.oidcState(), rotated.oidcState());
            service.checkNow();
            assertEquals(4, maximum.get());
            sessions.revoke(rotated.getId());
            release.countDown();
            await(() -> running.stream().noneMatch(s -> s.oidcState().refreshing.get()));
            assertTrue(sessions.validate(rotated.getId(), clock.instant()).isEmpty());
        } finally { release.countDown(); }
    }
}
