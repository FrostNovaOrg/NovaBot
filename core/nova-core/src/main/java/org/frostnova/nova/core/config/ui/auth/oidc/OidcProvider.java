package org.frostnova.nova.core.config.ui.auth.oidc;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigInteger;
import java.net.URI;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.frostnova.nova.core.config.ui.auth.oidc.OidcException.Kind.*;

/** Lazy discovery and kid-indexed public keys. Refreshes are serialized and bounded per client. */
final class OidcProvider {
    private static final Duration CACHE_TTL = Duration.ofHours(1);
    private static final Duration KEY_REFRESH_INTERVAL = Duration.ofSeconds(30);
    private final String issuer;
    private final RestTemplate http;
    private final Clock clock;
    private Discovery discovery;
    private Instant discoveryExpires = Instant.MIN;
    private Map<String, SigningKey> keys = Map.of();
    private Instant keysExpire = Instant.MIN;
    private Instant nextKeyRefresh = Instant.MIN;

    OidcProvider(String issuer, RestTemplate http, Clock clock) {
        this.issuer = issuer;
        this.http = http;
        this.clock = clock;
    }

    synchronized Discovery discovery() {
        Instant now = clock.instant();
        if (discovery != null && now.isBefore(discoveryExpires)) return discovery;
        URI issuerUri = OidcJson.https(issuer, "issuer", CONFIGURATION);
        if (issuerUri.getRawQuery() != null) throw new OidcException(CONFIGURATION, "OIDC issuer must not have a query");
        String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        JSONObject json = get(URI.create(base + "/.well-known/openid-configuration"), "discovery");
        if (!issuer.equals(OidcJson.text(json, "issuer", true, PROVIDER_ERROR))) {
            throw new OidcException(PROVIDER_ERROR, "OIDC discovery issuer does not exactly match configured issuer");
        }
        var fresh = new Discovery(endpoint(json, "authorization_endpoint"), endpoint(json, "token_endpoint"),
                endpoint(json, "jwks_uri"));
        supports(json, "response_types_supported", "code");
        supports(json, "code_challenge_methods_supported", "S256");
        supports(json, "token_endpoint_auth_methods_supported", "client_secret_basic");
        if (discovery != null && !discovery.jwks().equals(fresh.jwks())) {
            keys = Map.of();
            keysExpire = Instant.MIN;
        }
        discovery = fresh;
        discoveryExpires = now.plus(CACHE_TTL);
        return fresh;
    }

    synchronized SigningKey key(String kid, String algorithm) {
        Discovery metadata = discovery();
        Instant now = clock.instant();
        if (!now.isBefore(keysExpire)) {
            if (now.isBefore(nextKeyRefresh)) throw new OidcException(INVALID_TOKEN, "OIDC jwks refresh rate limit");
            // Reserve before I/O: failed requests are rate limited too.
            nextKeyRefresh = now.plus(KEY_REFRESH_INTERVAL);
            loadKeys(metadata.jwks(), now);
            // A first successful load already answers this kid; a later unknown kid may trigger one refresh.
            return matchingKey(kid, algorithm);
        }
        if (!keys.containsKey(kid) && !now.isBefore(nextKeyRefresh)) {
            nextKeyRefresh = now.plus(KEY_REFRESH_INTERVAL);
            loadKeys(metadata.jwks(), now);
        }
        return matchingKey(kid, algorithm);
    }

    private SigningKey matchingKey(String kid, String algorithm) {
        SigningKey key = keys.get(kid);
        if (key == null || !key.algorithm().equals(algorithm)) {
            throw new OidcException(INVALID_TOKEN, "OIDC unknown kid or incompatible alg");
        }
        return key;
    }

    private void loadKeys(URI uri, Instant now) {
        boolean initial = keysExpire.equals(Instant.MIN) && keys.isEmpty();
        JSONObject json = get(uri, "jwks");
        Object value = json.get("keys");
        if (!(value instanceof JSONArray array) || array.isEmpty() || array.size() > 100) {
            throw new OidcException(PROVIDER_ERROR, "OIDC jwks must contain 1 to 100 keys");
        }
        Map<String, SigningKey> loaded = new HashMap<>();
        try {
            for (Object entry : array) {
                if (!(entry instanceof JSONObject jwk)) throw new IllegalArgumentException();
                SigningKey key = parseKey(jwk);
                if (key == null) continue;
                String kid = OidcJson.text(jwk, "kid", true, PROVIDER_ERROR);
                if (loaded.putIfAbsent(kid, key) != null) throw new IllegalArgumentException();
            }
        } catch (GeneralSecurityException | RuntimeException error) {
            throw new OidcException(PROVIDER_ERROR, "OIDC jwks contains an invalid or duplicate signing key");
        }
        if (loaded.isEmpty()) throw new OidcException(PROVIDER_ERROR, "OIDC jwks has no supported signing keys");
        keys = Map.copyOf(loaded);
        keysExpire = now.plus(CACHE_TTL);
        // Permit one immediate rotation after initial discovery, then enforce the refresh interval.
        if (initial) nextKeyRefresh = Instant.MIN;
    }

    private static SigningKey parseKey(JSONObject jwk) throws GeneralSecurityException {
        if (jwk.containsKey("use") && !"sig".equals(jwk.get("use"))) return null;
        if (jwk.containsKey("key_ops")) {
            if (!(jwk.get("key_ops") instanceof JSONArray ops) || !ops.contains("verify")) return null;
        }
        String kty = OidcJson.text(jwk, "kty", true, PROVIDER_ERROR);
        String algorithm = switch (kty) {
            case "RSA" -> "RS256";
            case "EC" -> "P-256".equals(jwk.get("crv")) ? "ES256" : null;
            case "OKP" -> "Ed25519".equals(jwk.get("crv")) ? "EdDSA" : null;
            default -> null;
        };
        if (algorithm == null || jwk.containsKey("alg") && !algorithm.equals(jwk.get("alg"))) return null;
        PublicKey key = switch (algorithm) {
            case "RS256" -> {
                BigInteger modulus = new BigInteger(1, bytes(jwk, "n", 0));
                BigInteger exponent = new BigInteger(1, bytes(jwk, "e", 0));
                if (modulus.bitLength() < 2048 || modulus.bitLength() > 8192 || !modulus.testBit(0)
                        || exponent.compareTo(BigInteger.valueOf(3)) < 0 || !exponent.testBit(0)) {
                    throw new IllegalArgumentException();
                }
                yield KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
            }
            case "ES256" -> {
                AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
                parameters.init(new ECGenParameterSpec("secp256r1"));
                ECParameterSpec spec = parameters.getParameterSpec(ECParameterSpec.class);
                ECPoint point = new ECPoint(new BigInteger(1, bytes(jwk, "x", 32)),
                        new BigInteger(1, bytes(jwk, "y", 32)));
                yield KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, spec));
            }
            case "EdDSA" -> {
                byte[] encoded = bytes(jwk, "x", 32);
                boolean xOdd = (encoded[31] & 0x80) != 0;
                encoded[31] &= 0x7f;
                byte[] reversed = new byte[32];
                for (int i = 0; i < 32; i++) reversed[i] = encoded[31 - i];
                var spec = new EdECPublicKeySpec(NamedParameterSpec.ED25519, new EdECPoint(xOdd, new BigInteger(1, reversed)));
                yield KeyFactory.getInstance("Ed25519").generatePublic(spec);
            }
            default -> throw new IllegalArgumentException();
        };
        return new SigningKey(algorithm, key);
    }

    private static byte[] bytes(JSONObject jwk, String name, int length) {
        byte[] bytes = OidcJson.base64(OidcJson.text(jwk, name, true, PROVIDER_ERROR));
        if (bytes.length > 1024 || length > 0 && bytes.length != length) throw new IllegalArgumentException();
        return bytes;
    }

    private JSONObject get(URI uri, String stage) {
        try {
            var headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            String body = http.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
            return OidcJson.object(body, stage, PROVIDER_ERROR);
        } catch (ResourceAccessException error) {
            throw new OidcException(PROVIDER_UNAVAILABLE, "OIDC " + stage + " could not be reached");
        } catch (HttpStatusCodeException error) {
            throw new OidcException(error.getStatusCode().is5xxServerError() || error.getStatusCode().value() == 429
                    ? PROVIDER_UNAVAILABLE : PROVIDER_ERROR,
                    "OIDC " + stage + " returned HTTP " + error.getStatusCode().value());
        } catch (RestClientException error) {
            throw new OidcException(PROVIDER_ERROR, "OIDC " + stage + " request failed");
        }
    }

    private static URI endpoint(JSONObject json, String name) {
        return OidcJson.https(OidcJson.text(json, name, true, PROVIDER_ERROR), "discovery " + name, PROVIDER_ERROR);
    }

    private static void supports(JSONObject json, String name, String required) {
        if (json.containsKey(name) && (!(json.get(name) instanceof JSONArray values) || !values.contains(required))) {
            throw new OidcException(PROVIDER_ERROR, "OIDC discovery " + name + " does not support " + required);
        }
    }

    record Discovery(URI authorization, URI token, URI jwks) { }
    record SigningKey(String algorithm, PublicKey publicKey) { }
}
