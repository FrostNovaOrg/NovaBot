package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.*;
import org.frostnova.nova.core.config.ui.auth.oidc.*;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.util.IpMatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OidcBackchannelLogoutTest extends OidcTestSupport {
    private static final String PATH = "/config/api/oidc/backchannel-logout";
    private static final String EVENT = "http://schemas.openid.net/event/backchannel-logout";
    @TempDir Path directory;
    private ConfigUiSessionStore sessions;
    private ConfigUiAuthService auth;
    private MockMvc mvc;
    private OidcClient.Login login;
    private ConfigUiSession first;
    private ConfigUiSession second;
    private ConfigUiSession local;

    @BeforeEach
    void console() {
        var properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(directory.resolve("live.json").toString());
        var settings = properties.getConfigUi().getAuth();
        settings.setPassword(LowIterationPasswordHash.of("example password"));
        settings.setTotp(false);
        sessions = new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(24));
        var accounts = new ConfigUiAccountStore(new NovaStateStore(properties));
        auth = new ConfigUiAuthService(settings, sessions, new LoginThrottle(5, Duration.ofMinutes(15)), null);
        auth.setAccounts(accounts);
        auth.setOidcClient(client);
        provider.claims = claims -> claims.put("sid", "sid-1");
        login = complete(begin());
        accounts.bind(login.identity());
        first = issue("sid-1");
        second = issue("sid-2");
        local = auth.issueForPassword("127.0.0.1");
        mvc = MockMvcBuilders.standaloneSetup(new ConfigUiOidcController(client, auth, accounts, properties))
                .addInterceptors(new ConfigUiApiInterceptor(auth))
                .addFilters(new ConfigUiSecurityFilter("example-token", new IpMatcher(List.of("127.0.0.1")), auth,
                        settings, properties.getConfigUi().getAgreement())).build();
    }

    private ConfigUiSession issue(String sid) {
        var identity = new OidcClient.Identity(ISSUER, "subject-17", "Example Member", "member@example.com", sid);
        return auth.issueForOidc(ConfigUiAccount.LOCAL_ID, "127.0.0.1", new OidcClient.Login(identity, login.tokens(), login.nonce()));
    }

    private void claims(JSONObject claims, String id) {
        claims.remove("nonce");
        claims.put("events", Map.of(EVENT, Map.of()));
        claims.put("jti", id);
    }

    private String logout(String id, String sid, String sub) {
        provider.headers = header -> header.put("typ", "logout+jwt");
        provider.claims = claims -> {
            claims(claims, id);
            if (sid != null) claims.put("sid", sid);
            if (sub == null) claims.remove("sub"); else claims.put("sub", sub);
        };
        return provider.signedToken();
    }

    private org.springframework.test.web.servlet.ResultActions send(String token) throws Exception {
        return mvc.perform(post(PATH).contentType(MediaType.APPLICATION_FORM_URLENCODED).param("logout_token", token));
    }

    private void present(ConfigUiSession session) { assertTrue(auth.validate(session.getId()).isPresent()); }
    private void absent(ConfigUiSession session) { assertTrue(auth.validate(session.getId()).isEmpty()); }

    // 账户中心退出某一设备时，只按 issuer/sid 撤销该设备；同一身份的其它设备与本地口令照常使用。
    @Test
    void sidLogoutOnlyRevokesMatchingOidcSession() throws Exception {
        var wrongIssuer = sessions.issueForAccount("127.0.0.1", clock.instant(), ConfigUiSession.Channel.OIDC, null,
                ConfigUiAccount.LOCAL_ID, new OidcClient.Login(new OidcClient.Identity("https://other.example.com", "subject-17", null, null, "sid-1"), login.tokens(), login.nonce()));
        send(logout("logout-1", "sid-1", null)).andExpect(status().isOk()).andExpect(content().string(""))
                .andExpect(header().string("Cache-Control", "no-store"));
        absent(first);
        present(second);
        present(local);
        present(wrongIssuer);
    }

    // 账户中心按身份退出时，绑定的本地管理员也须退出全部统一登录设备，但口令入口仍可用。
    @Test
    void subjectLogoutRevokesAllOidcSessionsButKeepsLocalPassword() throws Exception {
        send(logout("logout-sub", null, "subject-17")).andExpect(status().isOk());
        absent(first);
        absent(second);
        present(local);
    }

    // 同时指定 sub 和 sid 时，两者必须都匹配，不能凭借别人的 sid 注销会话。
    @Test
    void sidAndSubjectMustBothMatchAndNoMatchStillReturns200() throws Exception {
        send(logout("logout-mismatch", "sid-1", "other-subject")).andExpect(status().isOk());
        present(first);
        present(second);
        send(logout("logout-match", "sid-1", "subject-17")).andExpect(status().isOk());
        absent(first);
        present(second);
    }

    // 伪造或不完整的登出令牌必须统一回 400 且不能撤销任何会话。
    @ParameterizedTest
    @ValueSource(strings = {"signature", "none", "iss", "aud", "events", "wrong-event", "nonce", "identity", "expired",
            "iat", "exp", "future", "typ", "jti", "empty-jti", "string-time", "bad-events", "overflow"})
    void rejectsForgedOrInvalidLogoutWithoutRevokingAnyone(String invalid) throws Exception {
        logout("bad-logout", "sid-1", "subject-17");
        var standard = provider.claims;
        provider.claims = c -> {
            standard.accept(c);
            switch (invalid) {
                case "iss" -> c.put("iss", "https://other.example.com");
                case "aud" -> c.put("aud", "other-client");
                case "events", "iat", "exp", "jti" -> c.remove(invalid);
                case "empty-jti" -> c.put("jti", "");
                case "wrong-event" -> c.put("events", Map.of("other-event", Map.of()));
                case "bad-events" -> c.put("events", Map.of(EVENT, true));
                case "nonce" -> c.put("nonce", "unexpected");
                case "identity" -> { c.remove("sub"); c.remove("sid"); }
                case "expired" -> { c.put("iat", clock.instant().minusSeconds(600).getEpochSecond()); c.put("exp", clock.instant().minusSeconds(61).getEpochSecond()); }
                case "future" -> c.put("iat", clock.instant().plusSeconds(61).getEpochSecond());
                case "overflow" -> c.put("exp", Long.MAX_VALUE);
                case "string-time" -> c.put("exp", "12345");
            }
        };
        if (invalid.equals("signature")) provider.signingKey = key("EdDSA");
        if (invalid.equals("none")) provider.headerAlgorithm = "none";
        if (invalid.equals("typ")) provider.headers = h -> h.put("typ", "at+jwt");
        send(provider.signedToken()).andExpect(status().isBadRequest()).andExpect(content().string(""))
                .andExpect(header().string("Cache-Control", "no-store"));
        present(first);
        present(second);
        present(local);
    }

    // 已处理的 jti 在有效期内不能再次用于踢掉后来登录的设备。
    @Test
    void rejectsReplayWithoutRevokingNewSession() throws Exception {
        String token = logout("once-only", "sid-1", null);
        send(token).andExpect(status().isOk());
        absent(first);
        var replacement = issue("sid-1");
        send(token).andExpect(status().isBadRequest()).andExpect(content().string(""));
        present(replacement);
        present(second);
        present(local);
    }

    // 有效令牌不指定 typ 或使用 JWT、数组 aud 时也应按规范处理。
    @ParameterizedTest
    @ValueSource(strings = {"absent", "JWT", "logout+jwt"})
    void acceptsAllowedTypesAndAudienceList(String type) throws Exception {
        logout("allowed-type", "sid-1", null);
        var standard = provider.claims;
        provider.claims = c -> { standard.accept(c); c.put("aud", List.of("other-client", CLIENT)); };
        provider.headers = h -> { if (!type.equals("absent")) h.put("typ", type); };
        send(provider.signedToken()).andExpect(status().isOk());
        absent(first);
    }

    // 同一 IP 刷后台登出不能无限验签，且无效表单不能撤销会话。
    @Test
    void throttlesLogoutAndRejectsMissingOrWrongForm() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_FORM_URLENCODED)).andExpect(status().isBadRequest());
        for (int i = 0; i < 6; i++) send("bad").andExpect(status().isBadRequest());
        send("bad").andExpect(status().isTooManyRequests());
        present(first);
        present(second);
    }

    // 并发重放只能接受一份，避免两个请求同时通过检查后注销重新登录的会话。
    @Test
    void replayReservationIsAtomic() throws Exception {
        String token = logout("concurrent", "sid-1", null);
        var executor = Executors.newFixedThreadPool(4);
        try {
            var results = executor.invokeAll(java.util.stream.IntStream.range(0, 8)
                    .<java.util.concurrent.Callable<Boolean>>mapToObj(i -> () -> {
                        try { client.verifyLogout(token); return true; }
                        catch (OidcException error) { return false; }
                    }).toList(), 15, TimeUnit.SECONDS);
            int accepted = 0;
            for (var result : results) if (result.get()) accepted++;
            assertEquals(1, accepted);
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS)); }
    }

    // 防重放表满时不能淘汰仍有效的 jti，过期后释放名额而不需要重启。
    @Test
    void boundsReplayMemoryAndReclaimsExpiredIds() {
        for (int i = 0; i < 4096; i++) client.verifyLogout(logout("bounded-" + i, "sid-1", null));
        assertThrows(OidcException.class, () -> client.verifyLogout(logout("overflow", "sid-1", null)));
        assertThrows(OidcException.class, () -> client.verifyLogout(logout("bounded-0", "sid-1", null)));
        clock.now = clock.instant().plusSeconds(361);
        assertDoesNotThrow(() -> client.verifyLogout(logout("overflow", "sid-1", null)));
    }
}
