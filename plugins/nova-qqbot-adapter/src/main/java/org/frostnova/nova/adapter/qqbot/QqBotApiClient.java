package org.frostnova.nova.adapter.qqbot;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.plugin.NovaComponent;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Locale;
import java.util.Map;

/** 向 QQ 开放平台发送带身份验证的 JSON 请求。 */
@NovaComponent
public class QqBotApiClient {
    private final QqBotHttpTransport transport;
    private final QqBotTokenService tokens;

    QqBotApiClient(QqBotHttpTransport transport, QqBotTokenService tokens) {
        this.transport = transport;
        this.tokens = tokens;
    }

    /** @return 解码后的 JSON 值，空回包返回 null */
    public Object request(String method, String path, JSONObject body) {
        return request(method, path, body, null);
    }

    /** 请求中含有机器人专属标识时，重试也须使用同一 AppID。 */
    Object request(String method, String path, JSONObject body, String appId) {
        return request(method, path, body, appId, false);
    }

    String uploadFile(String path, JSONObject body, String appId) {
        return (String) request("POST", path, body, appId, true);
    }

    private Object request(String method, String path, JSONObject body, String appId, boolean fileInfoOnly) {
        URI uri = officialUri(path);
        var token = tokenFor(appId);
        QqBotResponse response = send(method, uri, body, token);
        boolean rejected = rejectsToken(response);
        if (!rejected && !response.hasCode(11242)) {
            return result(response, new ResponseMask(token, token), fileInfoOnly);
        }

        if (rejected) {
            tokens.invalidate(token);
        }
        var retryToken = rejected ? tokenFor(appId) : token;
        QqBotResponse retry = send(method, uri, body, retryToken);
        if (rejectsToken(retry)) {
            tokens.invalidate(retryToken);
        }
        return result(retry, new ResponseMask(token, retryToken), fileInfoOnly);
    }

    private QqBotTokenService.Token tokenFor(String appId) {
        var token = tokens.token();
        if (appId != null && !appId.equals(token.appId())) {
            throw new QqBotApiException(0, "QQ 官方机器人 AppID 已切换", "", 0);
        }
        return token;
    }

    private QqBotResponse send(String method, URI uri, JSONObject body, QqBotTokenService.Token token) {
        String requestBody = body == null ? null : body.toJSONString();
        try {
            return QqBotResponse.parse(transport.send(method, uri,
                    Map.of("Authorization", "QQBot " + token.value(), "Content-Type", "application/json"),
                    requestBody));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QqBotApiException(0, "QQ 开放平台请求已中断", "", 0, true);
        } catch (IOException e) {
            throw new QqBotApiException(0, "连不上 QQ 开放平台，请检查本机网络", "", 0, !connectionFailed(e));
        } catch (RuntimeException e) {
            throw new QqBotApiException(0, "QQ 开放平台请求失败", "", 0, !connectionFailed(e));
        }
    }

    private static boolean connectionFailed(Exception error) {
        return error instanceof HttpConnectTimeoutException || error instanceof ConnectException
                || error instanceof UnknownHostException || error instanceof UnresolvedAddressException
                || error instanceof NoRouteToHostException || error instanceof SSLHandshakeException;
    }

    private static boolean rejectsToken(QqBotResponse response) {
        return response.status() == 401 || response.hasCode(11241) || response.hasCode(11243);
    }

    private static Object result(QqBotResponse response, ResponseMask mask, boolean fileInfoOnly) {
        if (response.successful()) {
            // 上传回包的文件信息只交给组消息接口，按原值传递。
            return fileInfoOnly ? response.text("file_info") : mask.json(response.body());
        }
        String message = response.text("message");
        throw new QqBotApiException(response.code(),
                message.isBlank() ? "QQ 开放平台请求失败" : mask.text(message),
                mask.text(response.text("trace_id")), response.status(), response.unreadableSuccess());
    }

    private static URI officialUri(String path) {
        if (path != null && path.startsWith("/") && !path.startsWith("//")) {
            try {
                URI relative = URI.create(path);
                if (relative.getRawFragment() == null) {
                    return QqBotHttpTransport.ORIGIN.resolve(relative);
                }
            } catch (IllegalArgumentException e) {
                // URI 解析异常可能带有原始输入。
            }
        }
        throw new IllegalArgumentException("QQ 开放平台接口须使用以 / 开头的站内路径");
    }

    private record ResponseMask(QqBotTokenService.Token first, QqBotTokenService.Token last) {
        Object json(Object value) {
            if (value instanceof JSONObject object) {
                object.entrySet().removeIf(entry -> credentialField(entry.getKey()) || containsCredential(entry.getKey()));
                object.replaceAll((key, member) -> json(member));
            } else if (value instanceof JSONArray array) {
                array.replaceAll(this::json);
            } else if (value != null && containsCredential(value.toString())) {
                return "已隐藏";
            }
            return value;
        }

        String text(String value) {
            return containsCredential(value) ? "已隐藏" : value;
        }

        private boolean containsCredential(String value) {
            return first.echoedIn(value) || (first != last && last.echoedIn(value));
        }

        private static boolean credentialField(String field) {
            return switch (field.toLowerCase(Locale.ROOT)) {
                case "authorization", "clientsecret", "client_secret", "appsecret", "app_secret",
                     "accesstoken", "access_token" -> true;
                default -> false;
            };
        }
    }
}
