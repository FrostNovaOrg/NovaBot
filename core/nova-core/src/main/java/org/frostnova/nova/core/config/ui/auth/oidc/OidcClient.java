package org.frostnova.nova.core.config.ui.auth.oidc;

import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigUiPrefix;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.frostnova.nova.core.config.ui.auth.oidc.OidcException.Kind.*;

/**
 * Standalone authorization-code/PKCE client. It creates no local session and installs no routes or cookies.
 * Settings are captured at construction and changes take effect after restart.
 */
@Component
@Slf4j
public final class OidcClient {
    private static final Duration LOGIN_TTL = Duration.ofMinutes(5);
    private static final int MAX_PENDING_LOGINS = 1024;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Transaction> transactions = new HashMap<>();
    private final boolean enabled;
    private final Map<String, Instant> logoutIds = new HashMap<>();
    private static final int MAX_LOGOUT_IDS = 4096;
    private final String issuer;
    private final String clientId;
    private final String clientSecret;
    private final String scopes;
    private final String redirectUri;
    private final Optional<URI> managementUri;
    private final RestTemplate http;
    private final Clock clock;
    private final OidcProvider provider;
    private final OidcTokenValidator validator;

    @Autowired
    public OidcClient(NovaCoreProperties properties, RestTemplate http) {
        this(properties.getConfigUi().getOidc(), http, Clock.systemUTC());
    }

    OidcClient(NovaCoreProperties.ConfigUi.Oidc config, RestTemplate http, Clock clock) {
        issuer = orEmpty(config.getIssuer());
        clientId = orEmpty(config.getClientId());
        clientSecret = orEmpty(config.getClientSecret());
        scopes = orEmpty(config.getScopes());
        redirectUri = orEmpty(config.getRedirectUri());
        managementUri = readManagementUri(config.getManagementUri());
        enabled = config.isEnabled() && !issuer.isBlank();
        this.http = http;
        this.clock = clock;
        provider = new OidcProvider(issuer, http, clock);
        validator = new OidcTokenValidator(issuer, clientId, provider, clock);
    }

    public boolean isEnabled() { return enabled; }

    /** Cached provider capability; an initial lookup is scheduled without blocking the caller. */
    public boolean createSupported() { return enabled && provider.createSupported(); }

    /** Optional destination exposed by the administrator account list. */
    public Optional<URI> managementUri() {
        return managementUri;
    }

    /** Uses the same management-page rules for stored settings and settings-page validation. */
    public static Optional<URI> parseManagementUri(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        URI uri;
        try {
            uri = OidcJson.https(value, "management-uri", CONFIGURATION);
        } catch (OidcException error) {
            throw new OidcException(CONFIGURATION, "请填写 https:// 开头的完整地址，或者留空");
        }
        if (uri.getPath().contains("/.well-known/")) {
            throw new OidcException(CONFIGURATION, "这是统一登录的发现文档地址，不是管理页；请填账户中心里管理用户、新建账号的那个网页地址，或者留空");
        }
        return Optional.of(uri);
    }

    private static Optional<URI> readManagementUri(String value) {
        try {
            return parseManagementUri(value);
        } catch (OidcException error) {
            log.warn("novabot.core.config-ui.oidc.management-uri 无效，已按留空处理；{}", error.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Creates a five-minute, one-use transaction held in memory independently of the local session cookie.
     * The HTTP integration must bind this authorization to the initiating browser with its own
     * short-lived SameSite=Lax correlation cookie (and pass state only to the intended callback).
     */
    public Authorization startLogin(HttpServletRequest request) {
        return startLogin(request, false);
    }

    public Authorization startLogin(HttpServletRequest request, boolean create) {
        requireEnabled();
        URI callback = callbackUri(request);
        var discovery = provider.discovery();
        String state = randomValue();
        String nonce = randomValue();
        String verifier = randomValue();
        Instant expiry = clock.instant().plus(LOGIN_TTL);
        var builder = UriComponentsBuilder.fromUri(discovery.authorization())
                .queryParam("response_type", "code").queryParam("client_id", "{client}")
                .queryParam("redirect_uri", "{redirect}").queryParam("scope", "{scope}")
                .queryParam("state", "{state}").queryParam("nonce", "{nonce}")
                .queryParam("code_challenge", "{challenge}").queryParam("code_challenge_method", "S256");
        if (create && discovery.createSupported()) builder.queryParam("prompt", "create");
        URI authorization = builder.encode().buildAndExpand(Map.of("client", clientId, "redirect", callback.toString(), "scope", scopes,
                        "state", state, "nonce", nonce, "challenge", challenge(verifier))).toUri();
        synchronized (transactions) {
            transactions.values().removeIf(transaction -> !clock.instant().isBefore(transaction.expiresAt()));
            if (transactions.size() >= MAX_PENDING_LOGINS) throw new OidcException(PROVIDER_UNAVAILABLE, "OIDC too many pending logins");
            transactions.put(state, new Transaction(nonce, verifier, callback, expiry, discovery.responseIssuerRequired()));
        }
        return new Authorization(authorization, state, expiry);
    }

    public void cancelLogin(String state) {
        synchronized (transactions) { transactions.remove(state); }
    }

    /** Consumes state before exchange, including on any failed callback. callbackIssuer is null when iss is absent. */
    public Login completeLogin(String state, String code, String callbackIssuer) {
        requireEnabled();
        Transaction transaction;
        synchronized (transactions) {
            transaction = transactions.remove(state);
        }
        if (transaction == null || !clock.instant().isBefore(transaction.expiresAt())) {
            throw new OidcException(INVALID_LOGIN, "OIDC invalid, expired or already consumed state");
        }
        if (transaction.responseIssuerRequired() && callbackIssuer == null) throw new OidcException(INVALID_LOGIN, "OIDC missing callback iss");
        if (callbackIssuer != null && !issuer.equals(callbackIssuer)) throw new OidcException(INVALID_LOGIN, "OIDC callback iss mismatch");
        if (code == null || code.isBlank()) throw new OidcException(INVALID_LOGIN, "OIDC missing authorization code");
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", transaction.callback().toString());
        form.add("code_verifier", transaction.verifier());
        Tokens tokens = exchange(form);
        Identity identity = validator.validate(tokens.idToken(), transaction.nonce(), true);
        return new Login(identity, tokens, transaction.nonce());
    }

    /**
     * Refreshes a previous verified login. TOKEN_REJECTED means invalid_grant; CONFIGURATION means a client error;
     * PROVIDER_UNAVAILABLE means connectivity, throttling or server failure and must not force logout.
     * A new ID token is optional, but if supplied it must still identify the same issuer and subject.
     * IDENTITY_CHANGED is reserved for a signed identity mismatch; other validation failures are INVALID_TOKEN.
     */
    public Login refresh(Login previous) {
        requireEnabled();
        if (previous == null || previous.identity() == null || !issuer.equals(previous.identity().issuer())
                || previous.tokens() == null || previous.tokens().refreshToken() == null || previous.tokens().refreshToken().isBlank()) {
            throw new OidcException(INVALID_LOGIN, "OIDC missing refresh context");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", previous.tokens().refreshToken());
        Tokens tokens = exchange(form);
        Identity identity = previous.identity();
        if (tokens.idToken() != null) {
            identity = validator.validate(tokens.idToken(), previous.nonce(), false, true);
            if (!previous.identity().subject().equals(identity.subject())) throw new OidcException(IDENTITY_CHANGED, "OIDC refresh sub mismatch");
        }
        if (identity.sid() == null && previous.identity().sid() != null) {
            identity = new Identity(identity.issuer(), identity.subject(), identity.name(), identity.email(), previous.identity().sid());
        }
        if (tokens.refreshToken() == null) {
            tokens = new Tokens(tokens.accessToken(), tokens.idToken(), previous.tokens().refreshToken(), tokens.expiresIn());
        }
        return new Login(identity, tokens, previous.nonce());
    }

    /** Verifies before atomically reserving a one-use logout ID. Active IDs are never evicted. */
    public Logout verifyLogout(String token) {
        requireEnabled();
        Logout logout = validator.validateLogout(token);
        synchronized (logoutIds) {
            Instant now = clock.instant();
            logoutIds.values().removeIf(expiry -> !now.isBefore(expiry));
            if (logoutIds.containsKey(logout.jti()) || logoutIds.size() >= MAX_LOGOUT_IDS) {
                throw new OidcException(INVALID_TOKEN, "OIDC logout replay or capacity reached");
            }
            logoutIds.put(logout.jti(), logout.expiresAt());
        }
        return logout;
    }

    public record Logout(String issuer, String subject, String sid, String jti, Instant expiresAt) {
        @Override public String toString() { return "OIDC logout [redacted]"; }
    }

    /** No forwarded headers are parsed here. Scheme/host/port use the servlet request, prefix uses the servlet context. */
    private URI callbackUri(HttpServletRequest request) {
        if (!redirectUri.isBlank()) return OidcJson.callback(redirectUri);
        int port = request.getServerPort();
        String scheme = request.getScheme();
        boolean defaultPort = "https".equals(scheme) && port == 443 || "http".equals(scheme) && port == 80;
        try {
            URI uri = UriComponentsBuilder.newInstance().scheme(scheme).host(request.getServerName())
                    .port(defaultPort ? -1 : port).path(ConfigUiPrefix.consolePath(request) + "/api/oidc/callback").build().toUri();
            return OidcJson.callback(uri.toString());
        } catch (IllegalArgumentException error) {
            throw new OidcException(CONFIGURATION, "OIDC cannot derive redirect-uri from request");
        }
    }

    private Tokens exchange(MultiValueMap<String, String> form) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        // RFC 6749 section 2.3.1: encode each component before HTTP Basic joins them with ':'.
        headers.setBasicAuth(URLEncoder.encode(clientId, StandardCharsets.UTF_8),
                URLEncoder.encode(clientSecret, StandardCharsets.UTF_8), StandardCharsets.US_ASCII);
        JSONObject json;
        try {
            String body = http.exchange(provider.discovery().token(), HttpMethod.POST, new HttpEntity<>(form, headers), String.class).getBody();
            json = OidcJson.object(body, "token response", PROVIDER_ERROR);
        } catch (ResourceAccessException error) {
            throw new OidcException(PROVIDER_UNAVAILABLE, "OIDC token endpoint could not be reached");
        } catch (HttpStatusCodeException error) {
            if (error.getStatusCode().is5xxServerError() || error.getStatusCode().value() == 429) {
                throw new OidcException(PROVIDER_UNAVAILABLE, "OIDC token endpoint temporarily unavailable");
            }
            if (error.getStatusCode().value() == 400 || error.getStatusCode().value() == 401) {
                json = OidcJson.object(error.getResponseBodyAsString(), "token error response", PROVIDER_ERROR);
                throw tokenError(json);
            }
            throw new OidcException(PROVIDER_ERROR, "OIDC token endpoint returned HTTP " + error.getStatusCode().value());
        } catch (RestClientException error) {
            throw new OidcException(PROVIDER_ERROR, "OIDC token request failed");
        }
        if (json.containsKey("error")) throw tokenError(json);
        String access = OidcJson.text(json, "access_token", true, PROVIDER_ERROR);
        if (!"Bearer".equalsIgnoreCase(OidcJson.text(json, "token_type", true, PROVIDER_ERROR))) {
            throw new OidcException(PROVIDER_ERROR, "OIDC token response has unsupported token_type");
        }
        Long expires = null;
        if (json.containsKey("expires_in")) {
            Object value = json.get("expires_in");
            if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < 0) {
                throw new OidcException(PROVIDER_ERROR, "OIDC token response has invalid expires_in");
            }
            expires = ((Number) value).longValue();
        }
        return new Tokens(access, OidcJson.text(json, "id_token", false, PROVIDER_ERROR),
                OidcJson.text(json, "refresh_token", false, PROVIDER_ERROR), expires);
    }

    private static OidcException tokenError(JSONObject json) {
        String error = OidcJson.text(json, "error", true, PROVIDER_ERROR);
        if (Set.of("invalid_client", "unauthorized_client").contains(error)) return new OidcException(CONFIGURATION, "OIDC client configuration rejected", error);
        if ("invalid_grant".equals(error)) return new OidcException(TOKEN_REJECTED, "OIDC token request rejected: " + error, error);
        if (Set.of("temporarily_unavailable", "server_error").contains(error)) {
            return new OidcException(PROVIDER_UNAVAILABLE, "OIDC token endpoint temporarily unavailable", error);
        }
        // Do not copy arbitrary provider text (including error_description) into exceptions.
        return new OidcException(PROVIDER_ERROR, "OIDC token endpoint returned an unexpected OAuth error");
    }

    private void requireEnabled() {
        if (!enabled) throw new OidcException(DISABLED, "OIDC client is disabled or issuer is empty");
        if (clientId.isBlank() || clientSecret.isBlank() || !Arrays.asList(scopes.split("\\s+")).contains("openid")) {
            throw new OidcException(CONFIGURATION, "OIDC requires client-id, client-secret and the openid scope");
        }
    }

    private String randomValue() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static String orEmpty(String value) { return value == null ? "" : value; }

    private record Transaction(String nonce, String verifier, URI callback, Instant expiresAt, boolean responseIssuerRequired) {
        @Override public String toString() { return "OIDC transaction [redacted]"; }
    }

    public record Authorization(URI uri, String state, Instant expiresAt) {
        @Override public String toString() { return "OIDC authorization [redacted]"; }
    }

    /** Only issuer plus subject form the identity key. Name and email are display attributes. */
    public record Identity(String issuer, String subject, String name, String email, String sid) {
        public Identity(String issuer, String subject, String name, String email) {
            this(issuer, subject, name, email, null);
        }
    }

    public record Tokens(String accessToken, String idToken, String refreshToken, Long expiresIn) {
        @Override public String toString() { return "OIDC tokens [redacted]"; }
    }

    /** Retain server-side for refresh; it includes credentials and the original login nonce. */
    public record Login(Identity identity, Tokens tokens, String nonce) {
        @Override public String toString() { return "OIDC login [redacted]"; }
    }
}
