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

public abstract class OidcTestSupport {
    public static final String ISSUER = "https://accounts.example.com/tenant";
    public static final String CLIENT = "console-client";
    public final MutableClock clock = new MutableClock();
    public NovaCoreProperties.ConfigUi.Oidc config;
    public FakeProvider provider;
    public RestTemplate http;
    public OidcClient client;

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
            public ClientHttpResponse executeInternal() throws IOException {
                return provider.respond(this);
            }
        });
        client = new OidcClient(config, http, clock);
    }

    public void rebuildClient() { client = new OidcClient(config, http, clock); }

    public OidcClient.Authorization begin() {
        var request = request();
        var authorization = client.startLogin(request);
        assertNull(request.getSession(false));
        var query = query(authorization.uri().getRawQuery());
        provider.nonce = query.get("nonce");
        provider.challenge = query.get("code_challenge");
        provider.redirect = query.get("redirect_uri");
        return authorization;
    }

    public OidcClient.Login complete(OidcClient.Authorization authorization) {
        return client.completeLogin(authorization.state(), "code-1", ISSUER);
    }

    public static MockHttpServletRequest request() {
        MockServletContext context = new MockServletContext();
        context.setContextPath("/app");
        var request = new MockHttpServletRequest(context);
        request.setScheme("https");
        request.setServerName("console.example.com");
        request.setServerPort(443);
        return request;
    }

    public static void assertRejected(String field, Runnable action) {
        var error = assertThrows(OidcException.class, action::run);
        assertTrue(error.getMessage().contains(field), error.getMessage());
    }

    public static Map<String, String> query(String encoded) {
        var result = new LinkedHashMap<String, String>();
        for (String entry : encoded.split("&")) {
            var pair = entry.split("=", 2);
            result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return result;
    }

    public static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static KeyPair key(String algorithm) throws Exception {
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

    public static byte[] unsigned(BigInteger integer, int length) {
        byte[] encoded = integer.toByteArray();
        if (length == 0) return encoded[0] == 0 ? Arrays.copyOfRange(encoded, 1, encoded.length) : encoded;
        byte[] result = new byte[length];
        int count = Math.min(length, encoded.length);
        System.arraycopy(encoded, encoded.length - count, result, length - count, count);
        return result;
    }

    public final class FakeProvider {
        public String algorithm = "EdDSA";
        public String headerAlgorithm;
        public String kid = "key-1";
        public KeyPair signingKey;
        public final List<JSONObject> keys = new ArrayList<>();
        public String discoveryIssuer = ISSUER;
        public String tokenEndpoint = ISSUER + "/token";
        public String nonce;
        public String challenge;
        public String redirect;
        public String refreshToken = "refresh-1";
        public String tokenOverride;
        public String tokenError;
        public String lastGrant;
        public String expectedRefreshToken = "refresh-1";
        public String lastRefreshToken;
        public Runnable tokenHook = () -> {};
        public boolean tokenTimeout;
        public int tokenStatus = 200;
        public int discoveryRequests;
        public int keyRequests;
        public int tokenRequests;
        public boolean responseIssuerSupported;
        public boolean discoveryUnavailable;
        public boolean keysUnavailable;
        public boolean tokenUnavailable;
        public boolean omitIdToken;
        public boolean malformedTokenResponse;
        public boolean pkceChecked;
        public Consumer<JSONObject> claims = ignored -> {};
        public Consumer<JSONObject> headers = ignored -> {};

        FakeProvider() throws Exception {
            useKey("EdDSA", "key-1");
        }

        public void useKey(String nextAlgorithm, String nextKid) throws Exception {
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
                        "token_endpoint", tokenEndpoint, "jwks_uri", ISSUER + "/jwks",
                        "authorization_response_iss_parameter_supported", responseIssuerSupported), 200);
            }
            if (url.equals(ISSUER + "/jwks")) {
                keyRequests++;
                if (keysUnavailable) throw new IOException("keys unavailable");
                return response(Map.of("keys", keys), 200);
            }
            assertEquals(tokenEndpoint, url);
            assertEquals(HttpMethod.POST, request.getMethod());
            tokenRequests++;
            tokenHook.run();
            if (tokenTimeout) throw new java.net.SocketTimeoutException("token timeout");
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
                lastRefreshToken = form.get("refresh_token");
                assertEquals(expectedRefreshToken, lastRefreshToken);
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

        public String signedToken() {
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

    public static final class MutableClock extends Clock {
        public Instant now = Instant.now();

        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
