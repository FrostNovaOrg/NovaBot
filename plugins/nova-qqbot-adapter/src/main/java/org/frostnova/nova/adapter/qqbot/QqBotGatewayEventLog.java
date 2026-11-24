package org.frostnova.nova.adapter.qqbot;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

final class QqBotGatewayEventLog {
    private QqBotGatewayEventLog() {
    }

    static String mask(JSONObject event, QqBotTokenService.Token token) {
        String json = event.toJSONString(JSONWriter.Feature.WriteNulls);
        if (token.echoedIn(json)) {
            return "已隐藏";
        }
        Set<String> identifiers = new LinkedHashSet<>();
        Object masked = maskValue(event, false, identifiers);
        String safe = JSON.toJSONString(masked, JSONWriter.Feature.WriteNulls);
        for (String id : identifiers) {
            // 同一标识也可能出现在报文的其他位置。
            safe = safe.replace(QqBotSecretFingerprint.jsonStringContents(id),
                    QqBotSecretFingerprint.jsonStringContents(maskId(id)));
        }
        return safe;
    }

    private static Object maskValue(Object value, boolean identifier, Set<String> identifiers) {
        if (value instanceof JSONObject object) {
            JSONObject masked = new JSONObject();
            object.forEach((key, member) -> {
                String field = key.replace("_", "").toLowerCase(Locale.ROOT);
                Object safe = switch (field) {
                    case "authorization", "token", "accesstoken", "clientsecret", "appsecret", "sessionid" -> "已隐藏";
                    default -> maskValue(member,
                            identifier || field.endsWith("openid") || field.endsWith("openids") || field.equals("id"),
                            identifiers);
                };
                masked.put(key, safe);
            });
            return masked;
        }
        if (value instanceof JSONArray array) {
            JSONArray masked = new JSONArray(array.size());
            array.forEach(member -> masked.add(maskValue(member, identifier, identifiers)));
            return masked;
        }
        if (identifier && value != null) {
            String id = value.toString();
            if (id.length() > 8) {
                identifiers.add(id);
            }
            return maskId(id);
        }
        return value;
    }

    static String maskId(String value) {
        return value.length() > 8 ? value.substring(0, 4) + "…" + value.substring(value.length() - 4) : "…";
    }
}
