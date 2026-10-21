package org.frostnova.nova.core.config.ui.auth.oidc;

import com.alibaba.fastjson2.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.util.List;

import static org.frostnova.nova.core.config.ui.auth.oidc.OidcException.Kind.INVALID_TOKEN;

/** Verifies compact signed ID tokens; algorithms and key sources never come from token URLs. */
final class OidcTokenValidator {
    private static final long CLOCK_SKEW_SECONDS = 60;
    private final String issuer;
    private final String clientId;
    private final OidcProvider provider;
    private final Clock clock;

    OidcTokenValidator(String issuer, String clientId, OidcProvider provider, Clock clock) {
        this.issuer = issuer;
        this.clientId = clientId;
        this.provider = provider;
        this.clock = clock;
    }

    OidcClient.Identity validate(String token, String nonce, boolean nonceRequired) {
        if (token == null || token.length() > 131072) throw invalid("ID token");
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) throw invalid("ID token");
        JSONObject header = OidcJson.object(new String(OidcJson.base64(parts[0]), StandardCharsets.UTF_8), "header", INVALID_TOKEN);
        if (header.containsKey("crit") || header.containsKey("b64") || header.containsKey("zip")) throw invalid("header extensions");
        String algorithm = OidcJson.text(header, "alg", true, INVALID_TOKEN);
        String signatureAlgorithm = switch (algorithm) {
            case "EdDSA" -> "Ed25519";
            case "RS256" -> "SHA256withRSA";
            case "ES256" -> "SHA256withECDSAinP1363Format";
            default -> throw invalid("alg");
        };
        String kid = OidcJson.text(header, "kid", true, INVALID_TOKEN);
        var key = provider.key(kid, algorithm);
        byte[] signatureBytes = OidcJson.base64(parts[2]);
        try {
            Signature signature = Signature.getInstance(signatureAlgorithm);
            signature.initVerify(key.publicKey());
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(signatureBytes)) throw invalid("signature");
        } catch (GeneralSecurityException error) {
            throw invalid("signature");
        }

        JSONObject claims = OidcJson.object(new String(OidcJson.base64(parts[1]), StandardCharsets.UTF_8), "claims", INVALID_TOKEN);
        if (!issuer.equals(OidcJson.text(claims, "iss", true, INVALID_TOKEN))) throw invalid("iss");
        String subject = OidcJson.text(claims, "sub", true, INVALID_TOKEN);
        validateAudience(claims);
        long now = clock.instant().getEpochSecond();
        long expiry = numericDate(claims, "exp");
        long issued = numericDate(claims, "iat");
        if (expiry <= now - CLOCK_SKEW_SECONDS) throw invalid("exp");
        if (issued > now + CLOCK_SKEW_SECONDS || issued >= expiry) throw invalid("iat");
        if (claims.containsKey("nbf") && numericDate(claims, "nbf") > now + CLOCK_SKEW_SECONDS) throw invalid("nbf");
        if (nonceRequired || claims.containsKey("nonce")) {
            String actual = OidcJson.text(claims, "nonce", true, INVALID_TOKEN);
            if (nonce == null || !MessageDigest.isEqual(nonce.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8))) {
                throw invalid("nonce");
            }
        }
        return new OidcClient.Identity(issuer, subject, display(claims, "name"), display(claims, "email"), display(claims, "sid"));
    }

    private static String display(JSONObject claims, String name) {
        Object value = claims.get(name);
        if (value == null) return null;
        if (value instanceof String text) return text;
        throw invalid(name);
    }

    private void validateAudience(JSONObject claims) {
        Object audience = claims.get("aud");
        int count;
        boolean matches;
        if (audience instanceof String text) {
            count = 1;
            matches = clientId.equals(text);
        } else if (audience instanceof List<?> list && !list.isEmpty()
                && list.stream().allMatch(item -> item instanceof String text && !text.isBlank())) {
            count = list.size();
            matches = list.contains(clientId);
        } else {
            throw invalid("aud");
        }
        if (!matches) throw invalid("aud");
        if (count > 1 || claims.containsKey("azp")) {
            if (!clientId.equals(OidcJson.text(claims, "azp", true, INVALID_TOKEN))) throw invalid("azp");
        }
    }

    private static long numericDate(JSONObject claims, String name) {
        Object value = claims.get(name);
        if (value instanceof Long number) return number;
        if (value instanceof Integer number) return number.longValue();
        throw invalid(name);
    }

    private static OidcException invalid(String field) {
        return new OidcException(INVALID_TOKEN, "OIDC invalid " + field);
    }
}
