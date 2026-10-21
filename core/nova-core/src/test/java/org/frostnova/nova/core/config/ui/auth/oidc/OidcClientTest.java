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

class OidcClientTest {
    private static final String ISSUER = "https://accounts.example.com/tenant";
    private static final String CLIENT = "console-client";
    private final MutableClock clock = new MutableClock();
    private NovaCoreProperties.ConfigUi.Oidc config;
    private FakeProvider provider;
    private RestTemplate http;
    private OidcClient client;

    @BeforeEach
    void setUp() throws Exception {
        config = new NovaCoreProperties().getConfigUi().getOidc();
        config.setEnabled(true);
        config.setIssuer(ISSUER);
        config.setClientId(CLIENT);
        config.setClientSecret("test:secret with spaces");
        provider = new FakeProvider();
        http = RestTemplateConfig.buildTemplate(Duration.ofSeconds(1), Duration.ofSeconds(1));
        // All requests terminate here, including discovery and key rotation. No socket is opened.
        http.setRequestFactory((uri, method) -> new MockClientHttpRequest(method, uri) {
            @Override
            protected ClientHttpResponse executeInternal() throws IOException {
                return provider.respond(this);
            }
        });
        client = new OidcClient(config, http, clock);
    }

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
        provider.claims = claims -> claims.put("exp", clock.instant().minusSeconds(61).getEpochSecond());
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

    private OidcClient.Authorization begin() {
        var request = request();
        var authorization = client.startLogin(request);
        assertNull(request.getSession(false));
        var query = query(authorization.uri().getRawQuery());
        provider.nonce = query.get("nonce");
        provider.challenge = query.get("code_challenge");
        provider.redirect = query.get("redirect_uri");
        return authorization;
    }

    private OidcClient.Login complete(OidcClient.Authorization authorization) {
        return client.completeLogin(authorization.state(), "code-1", ISSUER);
    }

    private static MockHttpServletRequest request() {
        MockServletContext context = new MockServletContext();
        context.setContextPath("/app");
        var request = new MockHttpServletRequest(context);
        request.setScheme("https");
        request.setServerName("console.example.com");
        request.setServerPort(443);
        return request;
    }

    private static void assertRejected(String field, Runnable action) {
        var error = assertThrows(OidcException.class, action::run);
        assertTrue(error.getMessage().contains(field), error.getMessage());
    }

    private static Map<String, String> query(String encoded) {
        var result = new LinkedHashMap<String, String>();
        for (String entry : encoded.split("&")) {
            var pair = entry.split("=", 2);
            result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return result;
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static KeyPair key(String algorithm) throws Exception {
        var generator = KeyPairGenerator.getInstance(switch (algorithm) {
            case "EdDSA" -> "Ed25519";
            case "RS256" -> "RSA";
            case "ES256" -> "EC";
            default -> throw new IllegalArgumentException(algorithm);
        });
        if (algorithm.equals("RS256")) generator.initialize(2048);
        if (algorithm.equals("ES256")) generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static byte[] unsigned(BigInteger integer, int length) {
        byte[] encoded = integer.toByteArray();
        if (length == 0) return encoded[0] == 0 ? Arrays.copyOfRange(encoded, 1, encoded.length) : encoded;
        byte[] result = new byte[length];
        int count = Math.min(length, encoded.length);
        System.arraycopy(encoded, encoded.length - count, result, length - count, count);
        return result;
    }

    private final class FakeProvider {
        private String algorithm = "EdDSA";
        private String headerAlgorithm;
        private String kid = "key-1";
        private KeyPair signingKey;
        private final List<JSONObject> keys = new ArrayList<>();
        private String discoveryIssuer = ISSUER;
        private String tokenEndpoint = ISSUER + "/token";
        private String nonce;
        private String challenge;
        private String redirect;
        private String refreshToken = "refresh-1";
        private String tokenOverride;
        private String tokenError;
        private String lastGrant;
        private int tokenStatus = 200;
        private int discoveryRequests;
        private int keyRequests;
        private int tokenRequests;
        private boolean discoveryUnavailable;
        private boolean keysUnavailable;
        private boolean tokenUnavailable;
        private boolean omitIdToken;
        private boolean malformedTokenResponse;
        private boolean pkceChecked;
        private Consumer<JSONObject> claims = ignored -> {};
        private Consumer<JSONObject> headers = ignored -> {};

        FakeProvider() throws Exception {
            useKey("EdDSA", "key-1");
        }

        void useKey(String nextAlgorithm, String nextKid) throws Exception {
            algorithm = nextAlgorithm;
            kid = nextKid;
            signingKey = key(algorithm);
            var jwk = new JSONObject();
            jwk.put("kid", kid);
            jwk.put("alg", algorithm);
            jwk.put("use", "sig");
            jwk.put("key_ops", List.of("verify"));
            switch (algorithm) {
                case "EdDSA" -> {
                    jwk.put("kty", "OKP");
                    jwk.put("crv", "Ed25519");
                    byte[] encoded = signingKey.getPublic().getEncoded();
                    jwk.put("x", b64(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length)));
                }
                case "RS256" -> {
                    var rsa = (RSAPublicKey) signingKey.getPublic();
                    jwk.put("kty", "RSA");
                    jwk.put("n", b64(unsigned(rsa.getModulus(), 0)));
                    jwk.put("e", b64(unsigned(rsa.getPublicExponent(), 0)));
                }
                case "ES256" -> {
                    var ec = (ECPublicKey) signingKey.getPublic();
                    jwk.put("kty", "EC");
                    jwk.put("crv", "P-256");
                    jwk.put("x", b64(unsigned(ec.getW().getAffineX(), 32)));
                    jwk.put("y", b64(unsigned(ec.getW().getAffineY(), 32)));
                }
            }
            keys.clear();
            keys.add(jwk);
        }

        ClientHttpResponse respond(MockClientHttpRequest request) throws IOException {
            String url = request.getURI().toString();
            if (url.equals(ISSUER + "/.well-known/openid-configuration")) {
                discoveryRequests++;
                assertEquals(HttpMethod.GET, request.getMethod());
                if (discoveryUnavailable) throw new IOException("discovery unavailable");
                return response(Map.of("issuer", discoveryIssuer, "authorization_endpoint", ISSUER + "/authorize",
                        "token_endpoint", tokenEndpoint, "jwks_uri", ISSUER + "/jwks"), 200);
            }
            if (url.equals(ISSUER + "/jwks")) {
                keyRequests++;
                if (keysUnavailable) throw new IOException("keys unavailable");
                return response(Map.of("keys", keys), 200);
            }
            assertEquals(tokenEndpoint, url);
            assertEquals(HttpMethod.POST, request.getMethod());
            tokenRequests++;
            if (tokenUnavailable) throw new IOException("token unavailable");
            assertTrue(MediaType.APPLICATION_FORM_URLENCODED.isCompatibleWith(request.getHeaders().getContentType()));
            String basic = new String(Base64.getDecoder().decode(request.getHeaders().getFirst("Authorization").substring(6)),
                    StandardCharsets.UTF_8);
            var pair = basic.split(":", 2);
            assertEquals(CLIENT, URLDecoder.decode(pair[0], StandardCharsets.UTF_8));
            assertEquals(config.getClientSecret(), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            var form = query(request.getBodyAsString());
            assertFalse(form.containsKey("client_secret"));
            lastGrant = form.get("grant_type");
            if (lastGrant.equals("authorization_code")) {
                assertEquals("code-1", form.get("code"));
                assertEquals(redirect, form.get("redirect_uri"));
                String verifier = form.get("code_verifier");
                assertTrue(verifier.matches("[A-Za-z0-9_-]{43,128}"));
                try {
                    assertEquals(challenge, b64(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))));
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
                pkceChecked = true;
            } else {
                assertEquals("refresh_token", lastGrant);
                assertEquals("refresh-1", form.get("refresh_token"));
            }
            if (tokenError != null) return response(Map.of("error", tokenError), 400);
            if (tokenStatus != 200) return response(Map.of("error", "server_error"), tokenStatus);
            if (malformedTokenResponse) return response(Map.of("unexpected", "response"), 200);
            var tokens = new JSONObject();
            tokens.put("access_token", "access-1");
            tokens.put("token_type", "Bearer");
            tokens.put("expires_in", 300);
            if (refreshToken != null) tokens.put("refresh_token", refreshToken);
            if (!omitIdToken) tokens.put("id_token", tokenOverride == null ? signedToken() : tokenOverride);
            return response(tokens, 200);
        }

        String signedToken() {
            try {
                var header = new JSONObject();
                header.put("alg", headerAlgorithm == null ? algorithm : headerAlgorithm);
                header.put("kid", kid);
                headers.accept(header);
                var body = new JSONObject();
                body.put("iss", ISSUER);
                body.put("sub", "subject-17");
                body.put("aud", CLIENT);
                body.put("iat", clock.instant().getEpochSecond());
                body.put("exp", clock.instant().plusSeconds(300).getEpochSecond());
                body.put("nonce", nonce);
                body.put("name", "Example Member");
                body.put("email", "member@example.com");
                claims.accept(body);
                String input = b64(JSON.toJSONBytes(header)) + "." + b64(JSON.toJSONBytes(body));
                var signature = Signature.getInstance(switch (algorithm) {
                    case "EdDSA" -> "Ed25519";
                    case "RS256" -> "SHA256withRSA";
                    case "ES256" -> "SHA256withECDSAinP1363Format";
                    default -> throw new IllegalArgumentException(algorithm);
                });
                signature.initSign(signingKey.getPrivate());
                signature.update(input.getBytes(StandardCharsets.US_ASCII));
                return input + "." + b64(signature.sign());
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        }

        ClientHttpResponse response(Object body, int status) {
            var response = new MockClientHttpResponse(JSON.toJSONBytes(body), status);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return response;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
