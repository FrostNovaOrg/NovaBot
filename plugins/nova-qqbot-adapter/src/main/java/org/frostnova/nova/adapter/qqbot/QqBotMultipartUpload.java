package org.frostnova.nova.adapter.qqbot;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.plugin.NovaComponent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

@NovaComponent
final class QqBotMultipartUpload {
    private static final int PREFIX_BYTES = 10002432;
    private static final long PUT_TIMEOUT_MILLIS = 60_000;
    private static final int MAX_ATTEMPTS = 5;

    private final QqBotApiClient api;
    private final QqBotHttpTransport transport;
    private final QqBotSendWindow window;

    QqBotMultipartUpload(QqBotApiClient api, QqBotHttpTransport transport, QqBotSendWindow window) {
        this.api = api;
        this.transport = transport;
        this.window = window;
    }

    String upload(String groupPath, String appId, String fileName, byte[] bytes) {
        String md5 = digest("MD5", bytes, 0, bytes.length);
        var prepare = JSONObject.of("file_type", 1, "file_name", fileName, "file_size", Integer.toString(bytes.length),
                "md5", md5, "sha1", digest("SHA-1", bytes, 0, bytes.length),
                "md5_10m", bytes.length <= PREFIX_BYTES ? md5 : digest("MD5", bytes, 0, PREFIX_BYTES));
        Object response = window.retryWhenLimited(() -> api.prepareUpload(groupPath + "/upload_prepare", prepare, appId));
        Plan plan = Plan.parse(response, bytes.length);
        int offset = 0;
        for (int index = 0; index < plan.parts().length; index++) {
            Part part = plan.parts()[index];
            uploadPart(part, bytes, offset, plan);
            var finish = JSONObject.of("upload_id", plan.id(), "part_index", index,
                    "block_size", Integer.toString(part.size()), "md5", digest("MD5", bytes, offset, part.size()));
            window.retryWhenLimited(() -> api.request("POST", groupPath + "/upload_part_finish", finish, appId));
            offset += part.size();
        }
        var merge = JSONObject.of("file_type", 1, "upload_id", plan.id(), "file_name", fileName, "srv_send_msg", false);
        return window.retryWhenLimited(() -> api.uploadFile(groupPath + "/files", merge, appId));
    }

    private void uploadPart(Part part, byte[] bytes, int offset, Plan plan) {
        long deadline = window.elapsedMillis() + plan.retryTimeoutMillis();
        long remaining = plan.retryTimeoutMillis();
        for (int attempt = 1; ; attempt++) {
            long timeout = remaining == 0 ? PUT_TIMEOUT_MILLIS : Math.min(remaining, PUT_TIMEOUT_MILLIS);
            // 预签名地址已包含本片的授权，只发送原始字节。
            HttpRequest request = HttpRequest.newBuilder(part.url()).timeout(Duration.ofMillis(timeout))
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes, offset, part.size())).build();
            try {
                put(request);
                return;
            } catch (QqBotApiException e) {
                remaining = deadline - window.elapsedMillis();
                if (Thread.currentThread().isInterrupted() || attempt == MAX_ATTEMPTS || remaining <= plan.retryDelayMillis()) {
                    throw e;
                }
                window.pause(plan.retryDelayMillis());
                remaining = deadline - window.elapsedMillis();
                if (remaining <= 0) throw e;
            }
        }
    }

    private void put(HttpRequest request) {
        QqBotHttpTransport.Response response;
        try {
            response = transport.send(request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QqBotApiException(0, "QQ 开放平台请求已中断", "", 0);
        } catch (IOException | RuntimeException e) {
            throw new QqBotApiException(0, "连不上 QQ 开放平台，请检查本机网络", "", 0);
        }
        // 存储服务的成功回包可以为空，也可以不是 JSON。
        int code = QqBotResponse.parse(response).code();
        if (response.status() / 100 != 2 || code != 0) {
            throw new QqBotApiException(code, "图片上传失败，请稍后再试", "", response.status());
        }
    }

    private static String digest(String algorithm, byte[] bytes, int offset, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            digest.update(bytes, offset, length);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Part(URI url, int size) {}

    private record Plan(String id, Part[] parts, long retryTimeoutMillis, long retryDelayMillis) {
        static Plan parse(Object response, int fileSize) {
            if (!(response instanceof JSONObject json) || !(json.get("upload_id") instanceof String id) || id.isBlank()
                    || !(json.get("parts") instanceof JSONArray items) || items.isEmpty()) throw invalidPlan();
            Part[] parts = new Part[items.size()];
            int remaining = fileSize;
            for (Object item : items) {
                if (!(item instanceof JSONObject fields)) throw invalidPlan();
                int index = number(fields.get("index"));
                int size = number(fields.get("block_size"));
                if (index >= parts.length || parts[index] != null || size == 0 || size > remaining) throw invalidPlan();
                parts[index] = new Part(url(fields.get("presigned_url")), size);
                remaining -= size;
            }
            if (remaining != 0) throw invalidPlan();
            Object config = json.get("upload_config");
            if (config != null && !(config instanceof JSONObject)) throw invalidPlan();
            JSONObject settings = (JSONObject) config;
            return new Plan(id, parts, millis(settings, "retry_timeout", 300), millis(settings, "retry_delay", 1));
        }

        private static URI url(Object value) {
            if (value instanceof String text) {
                try {
                    URI uri = URI.create(text);
                    if (("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                            && uri.getHost() != null && uri.getUserInfo() == null && uri.getFragment() == null) return uri;
                } catch (IllegalArgumentException ignored) {
                    // 地址可能含有签名，解析异常不带入投递结果。
                }
            }
            throw invalidPlan();
        }

        private static long millis(JSONObject config, String key, int fallback) {
            Object value = config == null ? null : config.get(key);
            return TimeUnit.SECONDS.toMillis(value == null ? fallback : number(value));
        }

        private static int number(Object value) {
            try {
                int parsed = Integer.parseInt(String.valueOf(value));
                if (parsed >= 0) return parsed;
            } catch (NumberFormatException ignored) {
                // 分片序号、大小和重试参数都应为非负整数。
            }
            throw invalidPlan();
        }

        private static QqBotApiException invalidPlan() {
            return new QqBotApiException(0, "QQ 的图片预上传回包缺少有效的分片信息", "", 0);
        }
    }
}
