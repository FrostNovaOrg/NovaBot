package org.frostnova.nova.adapter.qqbot;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.plugin.NovaComponent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

@Slf4j
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
        Plan plan;
        try {
            plan = Plan.parse(response, bytes.length);
        } catch (QqBotApiException e) {
            log.warn("QQ 图片预上传分片计划无效 {}", describePlan(response, bytes.length));
            throw e;
        }
        for (Part part : plan.parts()) {
            uploadPart(part, bytes, plan);
            var finish = JSONObject.of("upload_id", plan.id(), "part_index", part.index(),
                    "block_size", Integer.toString(part.size()), "md5", digest("MD5", bytes, part.offset(), part.size()));
            window.retryWhenLimited(() -> api.request("POST", groupPath + "/upload_part_finish", finish, appId));
        }
        var merge = JSONObject.of("file_type", 1, "upload_id", plan.id(), "file_name", fileName, "srv_send_msg", false);
        return window.retryWhenLimited(() -> api.uploadFile(groupPath + "/files", merge, appId));
    }

    private void uploadPart(Part part, byte[] bytes, Plan plan) {
        long deadline = window.elapsedMillis() + plan.retryTimeoutMillis();
        long remaining = plan.retryTimeoutMillis();
        for (int attempt = 1; ; attempt++) {
            long timeout = remaining == 0 ? PUT_TIMEOUT_MILLIS : Math.min(remaining, PUT_TIMEOUT_MILLIS);
            // 预签名地址已包含本片的授权，只发送原始字节。
            HttpRequest request = HttpRequest.newBuilder(part.url()).timeout(Duration.ofMillis(timeout))
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes, part.offset(), part.size())).build();
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

    private static String describePlan(Object response, int fileSize) {
        JSONObject json = response instanceof JSONObject fields ? fields : new JSONObject();
        Object parts = json.get("parts");
        Object partCount = parts == null ? "missing" : "invalid";
        var details = new JSONArray();
        if (parts instanceof JSONArray items) {
            partCount = items.size();
            for (Object item : items) {
                details.add(item instanceof JSONObject fields ? JSONObject.of(
                        "index", diagnosticNumber(fields.get("index")),
                        "block_size", diagnosticNumber(fields.get("block_size")),
                        "presigned_url_present", fields.containsKey("presigned_url")) : "invalid");
            }
        }
        Object config = json.get("upload_config");
        Object settings = config instanceof JSONObject fields ? JSONObject.of(
                "concurrency", diagnosticNumber(fields.get("concurrency")),
                "retry_timeout", diagnosticNumber(fields.get("retry_timeout")),
                "retry_delay", diagnosticNumber(fields.get("retry_delay"))) : config == null ? "missing" : "invalid";
        return JSONObject.of("keys", json.keySet(), "file_size", fileSize,
                "block_size", diagnosticNumber(json.get("block_size")), "upload_config", settings,
                "upload_id_present", json.containsKey("upload_id"), "parts_count", partCount, "parts", details).toJSONString();
    }

    private static Object diagnosticNumber(Object value) {
        if (value instanceof Number || value instanceof String text && text.matches("[+-]?[0-9]+")) return value;
        return value == null ? "missing" : "invalid";
    }

    private record Block(URI url, int size) {}

    private record Part(int index, URI url, int offset, int size) {}

    private record Plan(String id, Part[] parts, long retryTimeoutMillis, long retryDelayMillis) {
        static Plan parse(Object response, int fileSize) {
            if (!(response instanceof JSONObject json) || !(json.get("upload_id") instanceof String id) || id.isBlank()
                    || !(json.get("parts") instanceof JSONArray items) || items.isEmpty()) throw invalidPlan();
            // 按回包序号存放分片，多留一格以容纳从 1 开始的序号。
            Block[] blocks = new Block[items.size() + 1];
            long totalSize = 0;
            boolean allSizesExplicit = true;
            for (Object item : items) {
                if (!(item instanceof JSONObject fields)) throw invalidPlan();
                int index = number(fields.get("index"));
                Object sizeField = fields.get("block_size");
                int size = number(sizeField == null ? json.get("block_size") : sizeField);
                if (index >= blocks.length || blocks[index] != null || size == 0) throw invalidPlan();
                blocks[index] = new Block(url(fields.get("presigned_url")), size);
                totalSize += size;
                allSizesExplicit &= sizeField != null;
            }
            // 兼容逐片给出实际长度、各片合计正好等于文件大小的回包。
            Part[] parts = slice(blocks, fileSize, allSizesExplicit && totalSize == fileSize);
            Object config = json.get("upload_config");
            if (config != null && !(config instanceof JSONObject)) throw invalidPlan();
            JSONObject settings = (JSONObject) config;
            return new Plan(id, parts, millis(settings, "retry_timeout", 300), millis(settings, "retry_delay", 1));
        }

        private static Part[] slice(Block[] blocks, int fileSize, boolean exactSizes) {
            Part[] parts = new Part[blocks.length - 1];
            int firstIndex = blocks[0] == null ? 1 : 0;
            int covered = 0;
            for (int position = 0; position < parts.length; position++) {
                int index = firstIndex + position;
                Block block = blocks[index];
                if (block == null) throw invalidPlan();
                long offset = exactSizes ? covered : (long) position * block.size();
                if (offset != covered || covered >= fileSize) throw invalidPlan();
                int size = Math.min(block.size(), fileSize - covered);
                parts[position] = new Part(index, block.url(), covered, size);
                covered += size;
            }
            if (covered != fileSize) throw invalidPlan();
            return parts;
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
