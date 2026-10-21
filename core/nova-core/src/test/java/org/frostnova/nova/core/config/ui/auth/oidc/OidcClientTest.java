package org.frostnova.nova.core.config.ui.auth.oidc;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.RestTemplateConfig;
import org.frostnova.nova.core.config.ui.SensitiveFields;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class OidcClientTest extends OidcTestSupport {
    @Test
    void completesCodeFlowWithPkceAndStableIdentity() {
        var authorization = begin();
        var query = query(authorization.uri().getRawQuery());
        assertEquals("code", query.get("response_type"));
        assertEquals(CLIENT, query.get("client_id"));
        assertEquals("openid profile email offline_access", query.get("scope"));
        assertEquals("S256", query.get("code_challenge_method"));
        assertEquals("https://console.example.com/app/config/api/oidc/callback", query.get("redirect_uri"));
        assertFalse(query.containsKey("code_verifier"));
        assertFalse(query.containsKey("client_secret"));
        var login = complete(authorization);
        assertEquals(new OidcClient.Identity(ISSUER, "subject-17", "Example Member", "member@example.com"), login.identity());
        assertEquals("refresh-1", login.tokens().refreshToken());
        assertTrue(provider.pkceChecked);
        assertEquals(1, provider.tokenRequests);
        assertFalse(login.toString().contains("refresh-1"));
        assertFalse(login.tokens().toString().contains("access-1"));
    }

    @Test
    void rejectsForgedSignature() throws Exception {
        provider.signingKey = key("EdDSA");
        assertRejected("signature", () -> complete(begin()));
    }

    @Test
    void rejectsWrongAudience() {
        provider.claims = claims -> claims.put("aud", "other-client");
        assertRejected("aud", () -> complete(begin()));
    }

    @Test
    void rejectsExpiredToken() {
        // 过期令牌不能因其他时间字段不合法而掩盖到期检查的缺失。
        provider.claims = claims -> {
            claims.put("iat", clock.instant().minusSeconds(600).getEpochSecond());
            claims.put("exp", clock.instant().minusSeconds(61).getEpochSecond());
        };
        assertRejected("exp", () -> complete(begin()));
    }

    @Test
    void rejectsWrongNonce() {
        provider.claims = claims -> claims.put("nonce", "different-nonce");
        assertRejected("nonce", () -> complete(begin()));
    }

    @Test
    void rejectsStateReplay() {
        var authorization = begin();
        complete(authorization);
        assertRejected("state", () -> complete(authorization));
        assertEquals(1, provider.tokenRequests);
    }

    @Test
    void rejectsCallbackIssuerMismatch() {
        var authorization = begin();
        assertRejected("iss", () -> client.completeLogin(authorization.state(), "code-1", "https://other.example.com"));
        assertEquals(0, provider.tokenRequests);
        assertRejected("state", () -> complete(authorization));
    }

    @ParameterizedTest
    @ValueSource(strings = {"EdDSA", "RS256", "ES256"})
    void acceptsEachSupportedSignatureAlgorithm(String algorithm) throws Exception {
        provider.useKey(algorithm, "key-1");
        assertEquals("subject-17", complete(begin()).identity().subject());
    }

    @Test
    void refetchesKeysOnRotation() throws Exception {
        complete(begin());
        provider.useKey("EdDSA", "key-2");
        assertEquals("subject-17", complete(begin()).identity().subject());
        assertEquals(2, provider.keyRequests);
        assertEquals(1, provider.discoveryRequests);
    }

    @Test
    void rateLimitsUnknownKeyRefreshesIncludingFailedFetches() {
        complete(begin());
        provider.kid = "unknown-1";
        provider.keysUnavailable = true;
        assertThrows(OidcException.class, () -> complete(begin()));
        provider.kid = "unknown-2";
        assertThrows(OidcException.class, () -> complete(begin()));
        assertEquals(2, provider.keyRequests);
        clock.advance(Duration.ofSeconds(31));
        assertThrows(OidcException.class, () -> complete(begin()));
        assertEquals(3, provider.keyRequests);
    }

    @Test
    void cachesAndExpiresDiscoveryAndKeys() {
        complete(begin());
        complete(begin());
        assertEquals(1, provider.discoveryRequests);
        assertEquals(1, provider.keyRequests);
        clock.advance(Duration.ofHours(1).plusSeconds(1));
        complete(begin());
        assertEquals(2, provider.discoveryRequests);
        assertEquals(2, provider.keyRequests);
    }

    @Test
    void rejectsDiscoveryIssuerEvenWhenOnlyTrailingSlashDiffers() {
        provider.discoveryIssuer = ISSUER + "/";
        assertRejected("issuer", this::begin);
    }

    @Test
    void reportsDiscoveryConnectionFailureWithoutLeakingCredentials() {
        provider.discoveryUnavailable = true;
        var error = assertThrows(OidcException.class, this::begin);
        assertEquals(OidcException.Kind.PROVIDER_UNAVAILABLE, error.kind());
        assertTrue(error.getMessage().contains("discovery"));
        assertFalse(error.toString().contains(config.getClientSecret()));
        assertNull(error.getCause());
    }

    @Test
    void rejectsTokenIssuerMismatch() {
        provider.claims = claims -> claims.put("iss", ISSUER + "/");
        assertRejected("iss", () -> complete(begin()));
    }

    @Test
    void rejectsMultipleAudiencesWithoutAuthorizedParty() {
        provider.claims = claims -> claims.put("aud", List.of(CLIENT, "other-client"));
        assertRejected("azp", () -> complete(begin()));
    }

    @Test
    void rejectsWrongAuthorizedParty() {
        provider.claims = claims -> claims.put("azp", "other-client");
        assertRejected("azp", () -> complete(begin()));
    }

    @Test
    void acceptsMultipleAudiencesWithMatchingAuthorizedParty() {
        provider.claims = claims -> {
            claims.put("aud", List.of(CLIENT, "other-client"));
            claims.put("azp", CLIENT);
        };
        assertEquals("subject-17", complete(begin()).identity().subject());
    }

    @Test
    void rejectsFutureIssuedAt() {
        provider.claims = claims -> claims.put("iat", clock.instant().plusSeconds(61).getEpochSecond());
        assertRejected("iat", () -> complete(begin()));
    }

    @Test
    void permitsSmallClockSkew() {
        provider.claims = claims -> {
            claims.put("iat", clock.instant().plusSeconds(20).getEpochSecond());
            claims.put("exp", clock.instant().plusSeconds(40).getEpochSecond());
        };
        assertEquals("subject-17", complete(begin()).identity().subject());
    }

    @ParameterizedTest
    @ValueSource(strings = {"iss", "sub", "aud", "exp", "iat", "nonce"})
    void rejectsMissingRequiredClaim(String claim) {
        provider.claims = claims -> claims.remove(claim);
        assertThrows(OidcException.class, () -> complete(begin()));
    }

    @Test
    void rejectsNumericDatesEncodedAsStrings() {
        provider.claims = claims -> claims.put("exp", Long.toString(clock.instant().plusSeconds(300).getEpochSecond()));
        assertRejected("exp", () -> complete(begin()));
    }

    @Test
    void rejectsUnsignedAndSymmetricAlgorithms() {
        provider.headerAlgorithm = "none";
        assertRejected("alg", () -> complete(begin()));
        provider.headerAlgorithm = "HS256";
        assertRejected("alg", () -> complete(begin()));
    }

    @Test
    void rejectsCriticalHeaderExtensions() {
        provider.headers = header -> header.put("crit", List.of("custom"));
        assertRejected("header", () -> complete(begin()));
    }

    @Test
    void rejectsKeyAlgorithmMismatch() {
        provider.keys.get(0).put("alg", "RS256");
        assertThrows(OidcException.class, () -> complete(begin()));
    }

    @Test
    void rejectsEncryptionOnlyKey() {
        provider.keys.get(0).put("use", "enc");
        assertThrows(OidcException.class, () -> complete(begin()));
    }

    @Test
    void rejectsExpiredStateBeforeCallingTokenEndpoint() {
        var authorization = begin();
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        assertRejected("state", () -> complete(authorization));
        assertEquals(0, provider.tokenRequests);
    }

    @Test
    void consumesStateEvenWhenTokenExchangeFails() {
        var authorization = begin();
        provider.tokenError = "invalid_grant";
        assertThrows(OidcException.class, () -> complete(authorization));
        assertRejected("state", () -> complete(authorization));
        assertEquals(1, provider.tokenRequests);
    }

    @Test
    void acceptsAbsentCallbackIssuer() {
        var authorization = begin();
        assertEquals("subject-17", client.completeLogin(authorization.state(), "code-1", null).identity().subject());
    }

    @Test
    void generatesIndependentRandomTransactionsWithoutSessionCookie() {
        var first = begin();
        String firstNonce = provider.nonce;
        String firstChallenge = provider.challenge;
        var second = begin();
        assertNotEquals(first.state(), second.state());
        assertNotEquals(firstNonce, provider.nonce);
        assertNotEquals(firstChallenge, provider.challenge);
        assertTrue(first.state().matches("[A-Za-z0-9_-]{43}"));
        assertNotEquals(first.state(), firstNonce);
        complete(second);
    }

    @Test
    void rejectsUnknownStateWithoutContactingProvider() {
        assertRejected("state", () -> client.completeLogin("unknown-state", "code-1", ISSUER));
        assertEquals(0, provider.discoveryRequests);
        assertEquals(0, provider.tokenRequests);
    }

    @Test
    void boundsPendingTransactionsAndReclaimsExpiredEntries() {
        for (int i = 0; i < 1024; i++) begin();
        assertRejected("pending", this::begin);
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        assertEquals("subject-17", complete(begin()).identity().subject());
    }

    @Test
    void doesNotUseForwardedHeadersAsCallbackConfiguration() {
        var request = request();
        request.addHeader("X-Forwarded-Host", "other.example.com");
        request.addHeader("X-Forwarded-Proto", "http");
        request.addHeader("X-Forwarded-Prefix", "/other");
        var authorization = client.startLogin(request);
        assertEquals("https://console.example.com/app/config/api/oidc/callback",
                query(authorization.uri().getRawQuery()).get("redirect_uri"));
    }

    @Test
    void acceptsAbsentOrEmptyDisplayAttributes() {
        provider.claims = claims -> {
            claims.remove("name");
            claims.put("email", "");
        };
        var identity = complete(begin()).identity();
        assertNull(identity.name());
        assertEquals("", identity.email());
    }

    @Test
    void requiresIdTokenForInitialLogin() {
        provider.omitIdToken = true;
        assertThrows(OidcException.class, () -> complete(begin()));
    }

    @Test
    void rejectsMalformedJwt() {
        provider.tokenOverride = "invalid.token";
        assertRejected("ID token", () -> complete(begin()));
    }

    @Test
    void rejectsInvalidBase64urlSignature() {
        String token = provider.signedToken();
        provider.tokenOverride = token.substring(0, token.lastIndexOf('.') + 1) + "!!!!";
        assertRejected("base64url", () -> complete(begin()));
    }

    @Test
    void honorsExplicitCallbackUri() {
        config.setRedirectUri("https://console.example.com/custom-callback");
        client = new OidcClient(config, http, clock);
        var authorization = begin();
        assertEquals(config.getRedirectUri(), query(authorization.uri().getRawQuery()).get("redirect_uri"));
        complete(authorization);
    }

    @Test
    void providesOptionalManagementDestinationWithoutFetchingDiscovery() {
        assertTrue(client.managementUri().isEmpty());
        config.setManagementUri("https://accounts.example.com/members");
        client = new OidcClient(config, http, clock);
        assertEquals(URI.create(config.getManagementUri()), client.managementUri().orElseThrow());
        assertEquals(0, provider.discoveryRequests);
    }

    @Test
    void disabledOrEmptyIssuerDoesNotContactProvider() {
        config.setEnabled(false);
        client = new OidcClient(config, http, clock);
        assertFalse(client.isEnabled());
        assertThrows(OidcException.class, this::begin);
        config.setEnabled(true);
        config.setIssuer("");
        client = new OidcClient(config, http, clock);
        assertFalse(client.isEnabled());
        assertThrows(OidcException.class, this::begin);
        assertEquals(0, provider.discoveryRequests);
    }

    @Test
    void requiresHttpsEndpoints() {
        provider.tokenEndpoint = "http://accounts.example.com/token";
        assertThrows(OidcException.class, this::begin);
    }

    @Test
    void usesDefaultsAndMasksClientSecretOnReadAndSave() {
        var defaults = new NovaCoreProperties().getConfigUi().getOidc();
        assertFalse(defaults.isEnabled());
        assertEquals("", defaults.getIssuer());
        assertEquals("openid profile email offline_access", defaults.getScopes());
        assertEquals("", defaults.getRedirectUri());
        assertEquals("", defaults.getManagementUri());
        String key = "novabot.core.config-ui.oidc.client-secret";
        var values = new LinkedHashMap<>(Map.of(key, config.getClientSecret()));
        SensitiveFields.mask(values, ignored -> "java.lang.String");
        assertEquals(SensitiveFields.MASK, values.get(key));
        SensitiveFields.dropUnchanged(values, ignored -> "java.lang.String");
        assertTrue(values.isEmpty());
    }

    @Test
    void refreshesAndRetainsRefreshTokenWhenNotRotated() {
        var login = complete(begin());
        provider.refreshToken = null;
        provider.omitIdToken = true;
        var renewed = client.refresh(login);
        assertEquals(login.identity(), renewed.identity());
        assertEquals("refresh-1", renewed.tokens().refreshToken());
        assertEquals("refresh_token", provider.lastGrant);
    }

    @Test
    void refreshAcceptsIdTokenWithoutNonceAndRotatesRefreshToken() {
        var login = complete(begin());
        provider.refreshToken = "refresh-2";
        provider.claims = claims -> claims.remove("nonce");
        assertEquals("refresh-2", client.refresh(login).tokens().refreshToken());
    }

    @Test
    void refreshRejectsChangedSubject() {
        var login = complete(begin());
        provider.claims = claims -> claims.put("sub", "other-subject");
        assertRejected("sub", () -> client.refresh(login));
    }

    @Test
    void refreshRejectsChangedNonceWhenPresent() {
        var login = complete(begin());
        provider.claims = claims -> claims.put("nonce", "other-nonce");
        assertRejected("nonce", () -> client.refresh(login));
    }

    @Test
    void distinguishesRejectedRefreshFromConnectionFailure() {
        var login = complete(begin());
        provider.tokenError = "invalid_grant";
        var rejected = assertThrows(OidcException.class, () -> client.refresh(login));
        assertEquals(OidcException.Kind.TOKEN_REJECTED, rejected.kind());
        assertEquals("invalid_grant", rejected.oauthError());
        provider.tokenError = null;
        provider.tokenUnavailable = true;
        var unavailable = assertThrows(OidcException.class, () -> client.refresh(login));
        assertEquals(OidcException.Kind.PROVIDER_UNAVAILABLE, unavailable.kind());
        assertNull(unavailable.oauthError());
    }

    @Test
    void serverFailureAndMalformedResponseAreNotCredentialRejection() {
        var login = complete(begin());
        provider.tokenStatus = 503;
        var unavailable = assertThrows(OidcException.class, () -> client.refresh(login));
        assertEquals(OidcException.Kind.PROVIDER_UNAVAILABLE, unavailable.kind());
        provider.tokenStatus = 200;
        provider.malformedTokenResponse = true;
        var malformed = assertThrows(OidcException.class, () -> client.refresh(login));
        assertEquals(OidcException.Kind.PROVIDER_ERROR, malformed.kind());
    }

    // 账户中心声明回跳必须带 iss 时，缺失它不能继续换令牌。
    @Test
    void requiresCallbackIssuerWhenAdvertised() {
        provider.responseIssuerSupported = true;
        var authorization = begin();
        assertRejected("iss", () -> client.completeLogin(authorization.state(), "code-1", null));
        assertEquals(0, provider.tokenRequests);
    }

    // 一把不合格的钥匙不能使其他正常成员都无法登录。
    @Test
    void skipsMalformedAndDuplicateKeysWithoutDiscardingHealthyKeys() throws Exception {
        var valid = provider.keys.get(0);
        var missingKid = new JSONObject(valid);
        missingKid.remove("kid");
        var weakRsa = new JSONObject(Map.of("kid", "weak", "kty", "RSA", "n", "Aw", "e", "Aw"));
        var duplicate = new JSONObject(valid);
        duplicate.put("kid", "duplicate");
        provider.keys.addAll(List.of(missingKid, weakRsa, duplicate, new JSONObject(duplicate)));
        assertEquals("subject-17", complete(begin()).identity().subject());
        provider.kid = "duplicate";
        assertRejected("kid", () -> complete(begin()));
    }

    // 同一个 kid 即使有一把弱钥匙也不能被另一把正常钥匙掩盖，其他 kid 仍可登录。
    @Test
    void duplicateKidIsAmbiguousEvenWhenOneKeyIsWeak() {
        var healthy = new JSONObject(provider.keys.get(0));
        healthy.put("kid", "mixed");
        provider.keys.add(healthy);
        provider.keys.add(new JSONObject(Map.of("kid", "mixed", "kty", "RSA", "n", "Aw", "e", "Aw")));
        assertEquals("subject-17", complete(begin()).identity().subject());
        provider.kid = "mixed";
        assertRejected("kid", () -> complete(begin()));
    }

    // 公钥过期且重取被限流是暂时故障，不能被当作撤销成员凭据。
    @Test
    void expiredKeysAndThrottledRefreshRemainProviderUnavailable() {
        var login = complete(begin());
        clock.advance(Duration.ofHours(1).plusSeconds(1));
        provider.keysUnavailable = true;
        assertEquals(OidcException.Kind.PROVIDER_UNAVAILABLE,
                assertThrows(OidcException.class, () -> client.refresh(login)).kind());
        assertEquals(OidcException.Kind.PROVIDER_UNAVAILABLE,
                assertThrows(OidcException.class, () -> client.refresh(login)).kind());
    }

    // 客户端配置错误不能被解释为账户中心拒绝了这个成员。
    @ParameterizedTest
    @ValueSource(strings = {"invalid_client", "unauthorized_client"})
    void clientErrorsAreConfigurationFailures(String error) {
        var login = complete(begin());
        provider.tokenError = error;
        assertEquals(OidcException.Kind.CONFIGURATION,
                assertThrows(OidcException.class, () -> client.refresh(login)).kind());
    }

    // 本机自用及 SSH 隧道允许明文回环回调，公网地址仍须 HTTPS。
    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "[::1]", "localhost"})
    void permitsHttpLoopbackCallbacks(String host) {
        config.setRedirectUri("http://" + host + ":8080/config/api/oidc/callback");
        client = new OidcClient(config, http, clock);
        assertEquals(config.getRedirectUri(), query(begin().uri().getRawQuery()).get("redirect_uri"));
    }

    // 外网 HTTP 回调会让授权码明文过网，即使其它登录设置正确也必须拒绝。
    @Test
    void rejectsHttpCallbackOutsideLoopback() {
        config.setRedirectUri("http://console.example.com/config/api/oidc/callback");
        var error = assertThrows(OidcException.class, () -> {
            client = new OidcClient(config, http, clock);
            begin();
        });
        assertEquals(OidcException.Kind.CONFIGURATION, error.kind());
    }

}
