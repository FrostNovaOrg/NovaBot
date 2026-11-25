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
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** 向 QQ 开放平台发送带身份验证的 JSON 请求。 */
@NovaComponent
public class QqBotApiClient {
    private enum ResultType { MASKED_JSON, UPLOAD_PLAN, FILE_INFO }

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
        return request(method, path, body, appId, ResultType.MASKED_JSON);
    }

    Object queryGroup(String path, String appId, Consumer<GroupQueryOutcome> onQueryOutcome) {
        return request("GET", path, null, appId, ResultType.MASKED_JSON, onQueryOutcome);
    }

    Object prepareUpload(String path, JSONObject body, String appId) {
        return request("POST", path, body, appId, ResultType.UPLOAD_PLAN);
    }

    String uploadFile(String path, JSONObject body, String appId) {
        return (String) request("POST", path, body, appId, ResultType.FILE_INFO);
    }

    private Object request(String method, String path, JSONObject body, String appId, ResultType resultType) {
        return request(method, path, body, appId, resultType, null);
    }

    private Object request(String method, String path, JSONObject body, String appId, ResultType resultType,
                           Consumer<GroupQueryOutcome> onQueryOutcome) {
        URI uri = officialUri(path);
        var token = tokenFor(appId);
        QqBotResponse response = send(method, uri, body, token, onQueryOutcome);
        boolean rejected = rejectsToken(response);
        if (!rejected && !response.hasCode(11242)) {
            return result(response, new ResponseMask(token, token), resultType);
        }

        if (rejected) {
            tokens.invalidate(token);
        }
        var retryToken = rejected ? tokenFor(appId) : token;
        // 首次回包已计入查询，重试结果不覆盖这次查询反馈。
        QqBotResponse retry = send(method, uri, body, retryToken, null);
        if (rejectsToken(retry)) {
            tokens.invalidate(retryToken);
        }
        return result(retry, new ResponseMask(token, retryToken), resultType);
    }

    private QqBotTokenService.Token tokenFor(String appId) {
        var token = tokens.token();
        if (appId != null && !appId.equals(token.appId())) {
            throw new QqBotApiException(0, "QQ 官方机器人 AppID 已切换", "", 0);
        }
        return token;
    }

    private QqBotResponse send(String method, URI uri, JSONObject body, QqBotTokenService.Token token,
                               Consumer<GroupQueryOutcome> onQueryOutcome) {
        String requestBody = body == null ? null : body.toJSONString();
        QqBotResponse response;
        try {
            response = QqBotResponse.parse(transport.send(method, uri,
                    Map.of("Authorization", "QQBot " + token.value(), "Content-Type", "application/json"),
                    requestBody));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            report(onQueryOutcome, GroupQueryOutcome.INTERRUPTED);
            throw new QqBotApiException(0, "QQ 开放平台请求已中断", "", 0, true);
        } catch (IOException e) {
            report(onQueryOutcome, GroupQueryOutcome.fromNetworkError(e));
            throw new QqBotApiException(0, "连不上 QQ 开放平台，请检查本机网络", "", 0, !connectionFailed(e));
        } catch (RuntimeException e) {
            report(onQueryOutcome, GroupQueryOutcome.fromNetworkError(e));
            throw new QqBotApiException(0, "QQ 开放平台请求失败", "", 0, !connectionFailed(e));
        }
        report(onQueryOutcome, GroupQueryOutcome.REPLIED);
        return response;
    }

    private static void report(Consumer<GroupQueryOutcome> listener, GroupQueryOutcome outcome) {
        if (listener != null) listener.accept(outcome);
    }

    private static boolean connectionFailed(Exception error) {
        return error instanceof HttpConnectTimeoutException || error instanceof ConnectException
                || error instanceof UnknownHostException || error instanceof UnresolvedAddressException
                || error instanceof NoRouteToHostException || error instanceof SSLHandshakeException;
    }

    private static boolean rejectsToken(QqBotResponse response) {
        return response.status() == 401 || response.hasCode(11241) || response.hasCode(11243);
    }

    private static Object result(QqBotResponse response, ResponseMask mask, ResultType resultType) {
        if (response.successful()) {
            // 签名地址、上传编号和文件信息只在投递过程中使用，须按原值传递。
            return switch (resultType) {
                case UPLOAD_PLAN -> response.body();
                case FILE_INFO -> response.text("file_info");
                case MASKED_JSON -> mask.json(response.body());
            };
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

    enum GroupQueryOutcome {
        REPLIED(null),
        UNREACHABLE("连不上 QQ 接口"),
        TIMED_OUT("等待 QQ 接口回包超时"),
        INTERRUPTED("查询已中断"),
        NO_REPLY("未收到 QQ 接口回包");

        private final String reason;

        GroupQueryOutcome(String reason) {
            this.reason = reason;
        }

        String reason() {
            return reason;
        }

        private static GroupQueryOutcome fromNetworkError(Exception e) {
            if (connectionFailed(e)) return UNREACHABLE;
            if (e instanceof HttpTimeoutException) return TIMED_OUT;
            return NO_REPLY;
        }
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
