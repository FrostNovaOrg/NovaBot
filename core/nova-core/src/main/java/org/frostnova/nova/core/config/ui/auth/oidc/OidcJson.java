package org.frostnova.nova.core.config.ui.auth.oidc;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONReader;

import java.net.URI;
import java.util.Base64;

import static org.frostnova.nova.core.config.ui.auth.oidc.OidcException.Kind.*;

final class OidcJson {
    private OidcJson() { }

    static JSONObject object(String body, String stage, OidcException.Kind kind) {
        try {
            if (body == null || body.length() > 262144) throw new IllegalArgumentException();
            JSONObject value = JSON.parseObject(body, JSONReader.Feature.DuplicateKeyValueAsArray);
            if (value == null) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException error) {
            throw new OidcException(kind, "OIDC " + stage + ": invalid JSON object");
        }
    }

    static String text(JSONObject object, String name, boolean required, OidcException.Kind kind) {
        Object value = object.get(name);
        if (value == null && !required && !object.containsKey(name)) return null;
        if (!(value instanceof String text) || text.isBlank()) {
            throw new OidcException(kind, "OIDC invalid or missing " + name);
        }
        return text;
    }

    static URI https(String value, String name, OidcException.Kind kind) {
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (RuntimeException error) {
            throw new OidcException(kind, "OIDC " + name + " must be an absolute HTTPS URI without user info or fragment");
        }
    }

    static byte[] base64(String value) {
        try {
            if (value == null || value.length() > 131072 || !value.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException();
            }
            byte[] bytes = Base64.getUrlDecoder().decode(value);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value)) {
                throw new IllegalArgumentException();
            }
            return bytes;
        } catch (RuntimeException error) {
            throw new OidcException(INVALID_TOKEN, "OIDC invalid base64url encoding");
        }
    }
}
