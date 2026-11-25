package org.frostnova.nova.adapter.qqbot;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.health.PushActivityRecorder;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.service.NovaSenderService;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Flow;

import static org.frostnova.nova.adapter.qqbot.QqBotPluginAutoConfiguration.PLATFORM;
import static org.junit.jupiter.api.Assertions.*;

class QqBotMultipartUploadTest {
    private static final int MIB = 1024 * 1024;
    private static final int BLOCK_SIZE = 5 * MIB;
    private static final int FILE_SIZE = 6 * MIB;
    private static final String UPLOAD_ID = "fixture-upload-id";
    private static final String SIGNED_URL = "https://media.example.invalid/put-%d?signature=fixture";
    private static final String FILE_INFO = "fixture-file-info";

    @TempDir
    Path dataDirectory;

    @Test
    @DisplayName("超过 5 MB 的动态图尾片按标称大小返回时，群里收到配图和正文")
    void deliversLargeDynamicImageWithNominalTailSize() {
        byte[] image = image();
        try (var rig = new Rig(dataDirectory, prepare(part(0), part(1)))) {
            var message = Message.create(PLATFORM, PushTargetType.GROUP, rig.number, content(image)).get(0);
            var result = rig.core.sendNow(message);

            List<JSONObject> messages = rig.bodies("messages");
            assertEquals(1, messages.size());
            assertEquals(7, messages.get(0).getIntValue("msg_type"), "群里应收到图片消息");
            assertEquals(0, result.getIntValue("code"));
            assertEquals("动态配图", messages.get(0).getString("content"));
            assertEquals(FILE_INFO, messages.get(0).getJSONObject("media").getString("file_info"));
            assertEquals(List.of("upload_prepare", "put-0", "upload_part_finish", "put-1", "upload_part_finish",
                    "files", "messages"), rig.endpoints);

            int tailSize = FILE_SIZE - BLOCK_SIZE;
            List<JSONObject> finishes = rig.bodies("upload_part_finish");
            assertEquals(List.of(BLOCK_SIZE, tailSize), rig.puts.stream().map(Put::size).toList());
            assertPart(0, BLOCK_SIZE, md5(image, 0, BLOCK_SIZE), rig.puts.get(0), finishes.get(0));
            assertPart(1, tailSize, md5(image, BLOCK_SIZE, tailSize), rig.puts.get(1), finishes.get(1));
            assertEquals(UPLOAD_ID, rig.bodies("files").get(0).getString("upload_id"));
        }
    }

    @Test
    @DisplayName("QQ 给整图一片编号 1 时，大图随正文送达，群里不会只收到文字")
    void deliversSinglePartImageWithOneBasedIndex() {
        byte[] image = image(7_684_003);
        String size = Integer.toString(image.length);
        var prepared = JSONObject.of("upload_id", UPLOAD_ID, "block_size", size, "parts", List.of(part(1, image.length)),
                "upload_config", JSONObject.of("concurrency", 1, "retry_timeout", 300, "retry_delay", 1));
        try (var rig = new Rig(dataDirectory, prepared)) {
            var message = Message.create(PLATFORM, PushTargetType.GROUP, rig.number, content(image)).get(0);
            var result = rig.core.sendNow(message);

            List<JSONObject> messages = rig.bodies("messages");
            assertEquals(1, messages.size());
            assertEquals("动态配图", messages.get(0).getString("content"));
            assertEquals(7, messages.get(0).getIntValue("msg_type"), "群里应收到图片消息");
            assertEquals(0, result.getIntValue("code"));
            assertEquals(FILE_INFO, messages.get(0).getJSONObject("media").getString("file_info"));
            assertEquals(List.of("upload_prepare", "put-1", "upload_part_finish", "files", "messages"), rig.endpoints);
            assertEquals(image.length, rig.puts.get(0).size());
            assertPart(1, image.length, md5(image, 0, image.length), rig.puts.get(0), rig.bodies("upload_part_finish").get(0));
        }
    }

    @Test
    @DisplayName("QQ 给两片编号 1 和 2 时，大图的首块和尾块按原序号上传，群里收到配图")
    void deliversTwoPartImageWithOneBasedIndices() {
        byte[] image = image();
        try (var rig = new Rig(dataDirectory, prepare(part(2), part(1)))) {
            var message = Message.create(PLATFORM, PushTargetType.GROUP, rig.number, content(image)).get(0);
            var result = rig.core.sendNow(message);

            assertEquals(List.of("upload_prepare", "put-1", "upload_part_finish", "put-2", "upload_part_finish",
                    "files", "messages"), rig.endpoints);
            assertEquals(0, result.getIntValue("code"));
            assertEquals(7, rig.bodies("messages").get(0).getIntValue("msg_type"));
            int tailSize = image.length - BLOCK_SIZE;
            assertEquals(List.of(BLOCK_SIZE, tailSize), rig.puts.stream().map(Put::size).toList());
            List<JSONObject> finishes = rig.bodies("upload_part_finish");
            assertPart(1, BLOCK_SIZE, md5(image, 0, BLOCK_SIZE), rig.puts.get(0), finishes.get(0));
            assertPart(2, tailSize, md5(image, BLOCK_SIZE, tailSize), rig.puts.get(1), finishes.get(1));
        }
    }

    @ParameterizedTest(name = "坏分片计划 {0} 时拒绝上传并记告警")
    @CsvSource({"起点不是 0 或 1,2,3", "混用起点,0,2", "序号有空缺,1,3", "序号重复,1,1"})
    @ExtendWith(OutputCaptureExtension.class)
    void rejectsInvalidPartIndices(String reason, int first, int second, CapturedOutput output) {
        try (var rig = new Rig(dataDirectory, prepare(part(first), part(second)))) {
            var result = rig.registry.getSender(PLATFORM).orElseThrow().getLocalDelivery().deliver(Map.of(),
                    Map.of("type", PushTargetType.GROUP.getCode(), "num", rig.number, "content", content(image())));

            assertEquals(-1, result.getIntValue("code"), reason);
            assertTrue(result.getString("message").contains("分片信息"));
            assertEquals(List.of("upload_prepare"), rig.endpoints);
            assertEquals(1, output.getAll().lines().filter(line -> line.contains("WARN")
                    && line.contains("QQ 图片预上传分片计划无效")).count());
        }
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("分片计划缺片时，告警能查到 QQ 返回的分片信息且隐藏签名和上传标识")
    void logsIncompletePlanWithoutSignedUrlOrUploadId(CapturedOutput output) {
        try (var rig = new Rig(dataDirectory, prepare(part(0)))) {
            var result = rig.registry.getSender(PLATFORM).orElseThrow().getLocalDelivery().deliver(Map.of(),
                    Map.of("type", PushTargetType.GROUP.getCode(), "num", rig.number, "content", content(image())));

            assertEquals(-1, result.getIntValue("code"));
            assertTrue(result.getString("message").contains("分片信息"));
            assertEquals(List.of("upload_prepare"), rig.endpoints);
            var warnings = output.getAll().lines().filter(line -> line.contains("WARN")
                    && line.contains("parts_count")).toList();
            assertEquals(1, warnings.size(), "应记录一行分片计划告警");
            String warning = warnings.get(0);
            JSONObject diagnostic = JSON.parseObject(warning.substring(warning.indexOf('{')));
            assertTrue(diagnostic.getJSONArray("keys").containsAll(List.of("upload_id", "parts", "block_size", "upload_config")));
            assertEquals(1, diagnostic.getIntValue("parts_count"));
            assertEquals(FILE_SIZE, diagnostic.getIntValue("file_size"));
            assertEquals(Integer.toString(BLOCK_SIZE), diagnostic.get("block_size"));
            assertEquals(JSONObject.of("concurrency", 3, "retry_timeout", 300, "retry_delay", 2),
                    diagnostic.getJSONObject("upload_config"));
            assertTrue(diagnostic.getBooleanValue("upload_id_present"));
            JSONObject part = diagnostic.getJSONArray("parts").getJSONObject(0);
            assertEquals(0, part.get("index"));
            assertEquals(Integer.toString(BLOCK_SIZE), part.get("block_size"));
            assertTrue(part.getBooleanValue("presigned_url_present"));
            assertFalse(output.getAll().contains("media.example.invalid"));
            assertFalse(output.getAll().contains("signature=fixture"));
            assertFalse(output.getAll().contains(UPLOAD_ID));
        }
    }

    private static void assertPart(int index, int size, String md5, Put put, JSONObject finish) {
        assertEquals(md5, put.md5());
        assertEquals(index, finish.get("part_index"));
        assertEquals(Integer.toString(size), finish.get("block_size"));
        assertEquals(md5, finish.getString("md5"));
        assertEquals(UPLOAD_ID, finish.getString("upload_id"));
    }

    private static JSONObject prepare(JSONObject... parts) {
        return JSONObject.of("upload_id", UPLOAD_ID, "block_size", Integer.toString(BLOCK_SIZE), "parts", List.of(parts),
                "upload_config", JSONObject.of("concurrency", 3, "retry_timeout", 300, "retry_delay", 2));
    }

    private static JSONObject part(int index) {
        return part(index, BLOCK_SIZE);
    }

    private static JSONObject part(int index, int size) {
        return JSONObject.of("index", index, "block_size", Integer.toString(size), "presigned_url", SIGNED_URL.formatted(index));
    }

    private static byte[] image() {
        return image(FILE_SIZE);
    }

    private static byte[] image(int size) {
        byte[] bytes = new byte[size];
        new Random(1234).nextBytes(bytes);
        byte[] png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+XzWQAAAAASUVORK5CYII=");
        System.arraycopy(png, 0, bytes, 0, png.length);
        return bytes;
    }

    private static String content(byte[] image) {
        return "动态配图{image_base64=" + Base64.getEncoder().encodeToString(image) + "}";
    }

    private static String md5(byte[] bytes, int offset, int length) {
        try {
            var digest = MessageDigest.getInstance("MD5");
            digest.update(bytes, offset, length);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private record Post(String endpoint, JSONObject body) {}

    private record Put(int size, String md5) {}

    private static final class Rig implements QqBotHttpTransport, AutoCloseable {
        private final List<String> endpoints = new ArrayList<>();
        private final List<Post> posts = new ArrayList<>();
        private final List<Put> puts = new ArrayList<>();
        private final JSONObject prepared;
        private final QqBotDirectory directory;
        private final QqBotSender sender;
        private final NovaSenderService registry = new NovaSenderService(new NovaCoreProperties());
        private final NovaMessageSender core;
        private final long number;

        Rig(Path dataDirectory, JSONObject prepared) {
            this.prepared = prepared;
            var settings = new QqBotSettings(dataDirectory);
            settings.save("12345678", "fixture-bot-app-secret");
            var api = new QqBotApiClient(this, new QqBotTokenService(settings, this));
            var clock = Clock.systemUTC();
            directory = new QqBotDirectory(settings, api, clock, Runnable::run, QqBotDirectory::sha256);
            number = directory.groupJoined("fixture-group", clock.instant());
            var window = new QqBotSendWindow();
            sender = new QqBotSender(settings, directory, api, null, registry, window, new QqBotReplyWindows(),
                    new QqBotMultipartUpload(api, this, window));
            sender.updateRegistration();
            TimelineWriter timeline = event -> {};
            core = new NovaMessageSender(null, registry, new PushActivityRecorder(timeline), null, timeline, null, null, null);
        }

        @Override
        public Response send(String method, URI uri, Map<String, String> headers, String body) {
            if (uri.getPath().equals("/app/getAppAccessToken")) {
                return json(JSONObject.of("access_token", "fixture-bot-access-token", "expires_in", 7200));
            }
            if (method.equals("GET")) return json(new JSONObject());
            assertEquals("POST", method);
            String endpoint = uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1);
            endpoints.add(endpoint);
            posts.add(new Post(endpoint, JSON.parseObject(body)));
            return switch (endpoint) {
                case "upload_prepare" -> json(prepared);
                case "upload_part_finish" -> json(new JSONObject());
                case "files" -> json(JSONObject.of("file_info", FILE_INFO));
                case "messages" -> json(JSONObject.of("id", "fixture-message-id"));
                default -> throw new AssertionError(endpoint);
            };
        }

        @Override
        public Response send(HttpRequest request) {
            assertEquals("PUT", request.method());
            var subscriber = HttpResponse.BodySubscribers.ofByteArray();
            request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
                public void onSubscribe(Flow.Subscription subscription) { subscriber.onSubscribe(subscription); }
                public void onNext(ByteBuffer item) { subscriber.onNext(List.of(item)); }
                public void onError(Throwable error) { subscriber.onError(error); }
                public void onComplete() { subscriber.onComplete(); }
            });
            byte[] bytes = subscriber.getBody().toCompletableFuture().join();
            endpoints.add(request.uri().getPath().substring(1));
            puts.add(new Put(bytes.length, md5(bytes, 0, bytes.length)));
            return new Response(200, "");
        }

        List<JSONObject> bodies(String endpoint) {
            return posts.stream().filter(post -> post.endpoint().equals(endpoint)).map(Post::body).toList();
        }

        private static Response json(JSONObject body) {
            return new Response(200, body.toJSONString());
        }

        @Override
        public void close() {
            core.shutdown();
            sender.stopEventReplies();
            directory.stop();
        }
    }
}
