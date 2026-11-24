package org.frostnova.nova.adapter.qqbot;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.health.PushActivityRecorder;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.MessagePlaceholders;
import org.frostnova.nova.core.model.Sender;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.service.NovaSenderService;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.timeline.TimelineEventType;
import org.frostnova.nova.core.timeline.TimelineStore;
import org.frostnova.nova.core.util.HttpUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QqBotDeliveryTest {
    private static final String PLATFORM = "qq-official";
    private static final String APP_ID = "12345678";
    private static final String OPENID = "abcd-fixture-group-wxyz";
    private static final String TOKEN = "fixture-bot-access-token";
    private static final String SECRET = "fixture-bot-app-secret";
    private static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+XzWQAAAAASUVORK5CYII=";
    private static final String JPEG = "/9j/4A==";
    private static final String IMAGE = "{image_base64=" + PNG + "}";
    private static final String FILE_INFO = "opaque+/=file-info";

    @TempDir
    Path dataDirectory;

    @Test
    void sendsTextPushToTheRegisteredGroup() {
        withSender(rig -> {
            JSONObject result = rig.send("开播了");
            assertEquals(JSONObject.of("code", 0, "id", "message-1"), result);
            assertEquals(List.of("messages"), rig.endpoints());
            assertEquals(JSONObject.of("msg_type", 0, "content", "开播了"), rig.body(0));
            assertEquals(QqBotDirectory.ProactiveStatus.ENABLED, rig.group().proactive().status());
            assertEquals(QqBotDirectory.SwitchSource.SEND_SUCCESS, rig.group().proactive().source());
        });
    }

    @Test
    void uploadsTheReportImageBeforeSendingItWithNonemptyContent() {
        withSender(rig -> {
            assertEquals(0, rig.send(IMAGE).getIntValue("code"));
            assertEquals(List.of("files", "messages"), rig.endpoints());
            assertEquals(JSONObject.of("file_type", 1, "file_name", "image.png", "file_data", PNG,
                    "srv_send_msg", false), rig.body(0));
            assertEquals(JSONObject.of("msg_type", 7, "content", " ", "media", media()), rig.body(1));
        });
    }

    @Test
    void sendsTheLiveTitleAndCoverInOneMessage() {
        withSender(rig -> {
            assertEquals(0, rig.send("标题" + IMAGE).getIntValue("code"));
            assertEquals(List.of("files", "messages"), rig.endpoints());
            assertEquals(JSONObject.of("msg_type", 7, "content", "标题", "media", media()), rig.body(1));
        });
    }

    @Test
    void repliesTwiceToTheOriginalMessageWithoutUsingProactiveSending() {
        withSender(rig -> {
            assertEquals(0, rig.reply("第一条", "m1").getIntValue("code"));
            assertEquals(0, rig.reply("第二条", "m1").getIntValue("code"));
            assertEquals(List.of("messages", "messages"), rig.endpoints());
            assertEquals(JSONObject.of("msg_type", 0, "content", "第一条", "msg_id", "m1", "msg_seq", 1),
                    rig.body(0));
            assertEquals(JSONObject.of("msg_type", 0, "content", "第二条", "msg_id", "m1", "msg_seq", 2),
                    rig.body(1));
            assertEquals(QqBotDirectory.ProactiveStatus.UNKNOWN, rig.group().proactive().status());
        });
    }

    @Test
    void externalTextCannotMentionTheGroupOrItsMembers() {
        withSender(rig -> {
            String text = "<qqbot-at-everyone /> <QQbot-AT-Everyone /> <@abc> @everyone @EvErYoNe <@everyone>";
            assertEquals(0, rig.send(MessagePlaceholders.escapeExternalText(text)).getIntValue("code"));
            assertEquals("＜qqbot-at-everyone /> ＜QQbot-AT-Everyone /> ＜@abc> ＠everyone ＠EvErYoNe ＜＠everyone>",
                    rig.body(0).getString("content"));
        });
    }

    @Test
    void escapesEachPartOfAMessageSplitByTheCore() {
        withSender(rig -> {
            var messages = Message.create(PLATFORM, PushTargetType.GROUP, rig.number,
                    "<QQbot-at-everyone />{next}<@abc> @Everyone");
            assertEquals(2, messages.size());
            for (Message message : messages) {
                assertEquals(0, rig.send(message.getContent()).getIntValue("code"));
            }
            assertEquals(List.of("messages", "messages"), rig.endpoints());
            assertEquals("＜QQbot-at-everyone />", rig.body(0).getString("content"));
            assertEquals("＜@abc> ＠Everyone", rig.body(1).getString("content"));
        });
    }

    @Test
    void removingPlaceholdersCannotCreateAnActiveMentionInAMediaMessage() {
        withSender(rig -> {
            JSONObject result = rig.send("<qq{at=all}bot-at-everyone /> @{face=1}Everyone" + IMAGE
                    + "{image_base64=" + JPEG + "}");
            assertEquals("message-1", result.getString("id"));
            assertEquals(List.of("files", "files", "messages", "messages"), rig.endpoints());
            assertEquals("image.jpg", rig.body(1).getString("file_name"));
            assertEquals("＜qqbot-at-everyone /> ＠Everyone", rig.body(2).getString("content"));
            assertEquals(" ", rig.body(3).getString("content"));
        });
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void explainsHowTheGroupOwnerCanEnableProactiveSending(CapturedOutput output) {
        withSender(rig -> {
            rig.exchange = path -> json("err_code", 40034105, "message", "official denial " + OPENID,
                    "trace_id", "trace-fixture");
            JSONObject result = rig.send("开播了");
            assertEquals(-1, result.getIntValue("code"));
            String message = result.getString("message");
            assertTrue(message.contains("群「测试群」的群主还没打开"));
            assertTrue(message.contains("群成员里点机器人 → 右上角设置 → 打开"));
            assertEquals(QqBotDirectory.ProactiveStatus.DISABLED, rig.group().proactive().status());
            assertEquals(QqBotDirectory.SwitchSource.SEND_ERROR, rig.group().proactive().source());
            assertEquals(message, rig.group().lastFailure().reason());
            var file = new QqBotDirectoryFile(dataDirectory.resolve("qqbot"));
            assertTrue(assertDoesNotThrow(() -> Files.readString(dataDirectory.resolve("qqbot/directory.json")))
                    .contains("发送报错"));
            assertEquals(QqBotDirectory.SwitchSource.SEND_ERROR,
                    assertDoesNotThrow(file::load).get(APP_ID).groups().get(rig.number).proactive().source());

            String logs = output.getAll();
            assertTrue(logs.contains("40034105") && logs.contains("official denial")
                    && logs.contains("trace-fixture") && logs.contains("abcd…wxyz"));
            assertFalse(logs.contains(OPENID));
        });
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void readTimeoutAfterSendingMarksDeliveryUnknownAndMakesOnlyOneSendRequest(CapturedOutput output) {
        withSender(rig -> {
            rig.exchange = path -> {
                if (path.equals("files")) {
                    return json("file_info", FILE_INFO);
                }
                throw new HttpTimeoutException("fixture read timeout");
            };
            JSONObject result = rig.send("开播了" + IMAGE);
            assertEquals(-1, result.getIntValue("code"));
            assertTrue(result.getBooleanValue(Sender.LocalDelivery.DELIVERY_UNKNOWN));
            assertTrue(result.getString("message").contains("没等到 QQ 的回包"));
            assertEquals(List.of("files", "messages"), rig.endpoints());
            assertTrue(output.getAll().contains("送达不明"));
            assertFalse(output.getAll().contains("发消息失败"));
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void connectionFailureIsAnOrdinaryFailure(boolean timeout) {
        withSender(rig -> {
            rig.exchange = path -> {
                if (timeout) {
                    throw new HttpConnectTimeoutException("fixture connect timeout");
                }
                throw new ConnectException("fixture refused connection");
            };
            JSONObject result = rig.send("开播了");
            assertEquals(-1, result.getIntValue("code"));
            assertFalse(result.getBooleanValue(Sender.LocalDelivery.DELIVERY_UNKNOWN));
            assertEquals(List.of("messages"), rig.endpoints());
        });
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void failedUploadMakesNoSendRequestAndRetriedTextIsEscaped(CapturedOutput output) {
        withSender(rig -> {
            rig.exchange = path -> path.equals("files")
                    ? json("code", 850019, "message", "echo " + PNG + " " + OPENID,
                           "trace_id", TOKEN + " " + SECRET)
                    : json("id", "text-only");
            String content = "<qq" + IMAGE + "bot-at-everyone /> @everyone";
            JSONObject failed = rig.send(content);
            assertEquals(-1, failed.getIntValue("code"));
            assertTrue(failed.getString("message").contains("图片格式不受支持"));
            assertEquals(List.of("files"), rig.endpoints());
            assertEquals(0, rig.send(MessagePlaceholders.stripImages(content)).getIntValue("code"));
            assertEquals(List.of("files", "messages"), rig.endpoints());
            assertEquals("＜qqbot-at-everyone /> ＠everyone", rig.body(1).getString("content"));

            String logs = output.getAll();
            assertTrue(logs.contains("850019") && logs.contains("abcd…wxyz"));
            for (String privateValue : List.of(PNG, OPENID, TOKEN, SECRET)) {
                assertFalse(logs.contains(privateValue));
            }
        });
    }

    @Test
    void rejectsFriendsUnknownGroupsAndRemovedGroupsBeforeMakingARequest() {
        withSender(rig -> {
            assertTrue(rig.deliver(Map.of("type", 0, "num", rig.number, "content", "开播了"))
                    .getString("message").contains("暂不支持私聊推送"));
            assertTrue(rig.deliver(Map.of("type", 1, "num", 1L, "content", "开播了"))
                    .getString("message").contains("这个群还没对上号"));
            rig.directory.groupRemoved(OPENID, Instant.now());
            assertTrue(rig.send("开播了").getString("message").contains("已被移出"));
            assertTrue(rig.endpoints().isEmpty());
        });
    }

    @Test
    void updatesTheExistingSenderAndKeepsItWhenCredentialsAreCleared() {
        withSender(rig -> {
            rig.credentials.setIntervalMillis(3700);
            assertEquals(3700, rig.sender.getDelay());
            rig.credentials.clear();
            assertFalse(rig.credentials.status().configured());
            assertSame(rig.sender, rig.registry.getSender(PLATFORM).orElseThrow());
            assertTrue(rig.send("开播了").getString("message").contains("尚未配置"));
            assertTrue(rig.endpoints().isEmpty());

            var stored = new QqBotSettings(dataDirectory).snapshot();
            assertFalse(stored.configured());
            assertEquals("", stored.appId());
            assertEquals(3700, stored.intervalMillis());
            rig.credentials.save(APP_ID, SECRET);
            assertSame(rig.sender, rig.registry.getSender(PLATFORM).orElseThrow());
            assertEquals(0, rig.send("开播了").getIntValue("code"));
        });
    }

    @Test
    void downloadsNetworkImagesLocallyAndPreservesOpaqueFileInfo() {
        byte[] bytes = Base64.getDecoder().decode(PNG);
        AtomicBoolean cancelled = new AtomicBoolean();
        withSender(fakeDownloader(bytes, false, cancelled), rig -> {
            String opaque = "opaque- " + TOKEN + " /=+";
            rig.exchange = path -> path.equals("files") ? json("file_info", opaque) : json("id", "message-1");
            assertEquals(0, rig.send("标题{image_url=https://images.example.invalid/@everyone/cover.png}")
                    .getIntValue("code"));
            assertEquals(List.of("files", "messages"), rig.endpoints());
            assertEquals(PNG, rig.body(0).getString("file_data"));
            assertEquals(opaque, rig.body(1).getJSONObject("media").getString("file_info"));
            assertFalse(cancelled.get());
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsOversizedNetworkImagesBeforeUpload(boolean declaresSize) {
        byte[] bytes = new byte[QqBotImageDownloader.MAX_BYTES + 1];
        byte[] png = Base64.getDecoder().decode(PNG);
        System.arraycopy(png, 0, bytes, 0, png.length);
        AtomicBoolean cancelled = new AtomicBoolean();
        withSender(fakeDownloader(bytes, declaresSize, cancelled), rig -> {
            JSONObject result = rig.send("{image_url=https://images.example.invalid/@everyone/cover.png}");
            assertEquals(-1, result.getIntValue("code"));
            assertEquals("图片太大", result.getString("message"));
            assertTrue(rig.endpoints().isEmpty());
            assertTrue(cancelled.get());
        });
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            40054010 | 链接被平台拦了（example.invalid）
            40054002 | 在这个群被禁言了
            40034101 | 机器人不在这个群
            40054003 | 机器人不在这个群
            40034006 | 内容被平台判为违规
            40054016 | 机器人当前不在线
            50055001 | QQ 侧发送出错
            22006    | 消息格式被平台拒绝（错误码 22006）
            304061   | 消息格式被平台拒绝（错误码 304061）
            340069   | 消息格式被平台拒绝（错误码 340069）
            304080   | 消息格式被平台拒绝（错误码 304080）
            850018   | 被禁言，图片没有传上去
            850019   | 图片格式不受支持
            850031   | 图片太大
            40093002 | 今天发文件的额度用完了
            40093001 | 图片上传失败
            850027   | 图片上传失败
            987654   | QQ 返回错误 987654：远端原因
            """)
    void explainsPlatformErrorsInPlainLanguage(int code, String expected) {
        withSender(rig -> {
            rig.exchange = path -> json("code", code, "message", "远端原因");
            JSONObject result = rig.send("看看（https://example.invalid/live?detail=1）");
            assertEquals(-1, result.getIntValue("code"));
            assertTrue(result.getString("message").contains(expected));
        });
    }

    @Test
    void confirmsTheFirstMessageWhenAnAdditionalImageIsRejected() {
        withSender(rig -> {
            rig.exchange = path -> path.equals("files") ? json("file_info", FILE_INFO)
                    : ++rig.sent == 1 ? json("id", "first") : json("code", 50055001);
            JSONObject result = rig.send("标题" + IMAGE + "{image_base64=" + JPEG + "}");
            assertEquals(0, result.getInteger("code"));
            assertEquals("first", result.getString("id"));
            assertEquals(List.of("files", "files", "messages", "messages"), rig.endpoints());
            assertTrue(rig.group().lastFailure().reason().contains("QQ 侧发送出错"));
        });
    }

    @Test
    @DisplayName("第二张图被拒时，核心和日志能看出图片有缺，已发的正文只发一次")
    @ExtendWith(OutputCaptureExtension.class)
    void recordsMissingImageWithoutResendingDeliveredText(CapturedOutput output) {
        withSender(rig -> {
            rig.exchange = path -> path.equals("files") ? json("file_info", FILE_INFO)
                    : ++rig.sent == 1 ? json("id", "first") : json("code", 50055001);
            NovaCoreProperties properties = new NovaCoreProperties();
            properties.getLive().setLiveDataPath(dataDirectory.resolve("live.json").toString());
            TimelineStore timeline = new TimelineStore(properties);
            PushActivityRecorder activity = new PushActivityRecorder(timeline);
            NovaMessageSender sender = new NovaMessageSender(mock(HttpUtil.class), rig.registry, activity,
                    null, timeline, null, null, null);
            var message = Message.create(PLATFORM, PushTargetType.GROUP, rig.number,
                    "标题" + IMAGE + "{image_base64=" + JPEG + "}").get(0);
            AtomicInteger degraded = new AtomicInteger();
            message.addOnImageDegradedCallback(degraded::incrementAndGet);

            JSONObject result = sender.sendNow(message);

            assertEquals(List.of("files", "files", "messages", "messages"), rig.endpoints());
            assertEquals("标题", rig.body(2).getString("content"));
            assertEquals(" ", rig.body(3).getString("content"));
            assertEquals(0, result.getInteger("code"));
            assertEquals("first", message.getId());
            assertEquals(1, activity.getSuccessCount());
            assertEquals(0, activity.getFailureCount());
            var filter = new TimelineStore.Filter(null, false, null, null, null, null, null, 10);
            var events = timeline.query(filter, null).events();
            assertEquals(1, events.size());
            TimelineEvent event = events.get(0);
            assertEquals(TimelineEventType.PUSH_SENT, event.type());
            assertTrue(event.text().contains("图片未送达"),
                    () -> "回包 " + result + "，日志页显示「" + event.text() + "」");
            assertEquals(TimelineEvent.Level.WARN, event.level());
            assertTrue(event.detail().getOrDefault("reason", "").contains("QQ 侧发送出错"));
            assertTrue(result.getBooleanValue(Sender.LocalDelivery.IMAGE_DEGRADED));
            assertEquals(1, degraded.get());
            assertTrue(output.getAll().lines().anyMatch(line -> line.contains("图片未送达")
                    && line.contains("QQ 侧发送出错")));
        });
    }

    @Test
    void doesNotResendWhenTheResponseHasNoMessageId() {
        withSender(rig -> {
            rig.exchange = path -> json("timestamp", "2026-11-24T15:20:00Z");
            assertTrue(rig.send("开播了").getBooleanValue(Sender.LocalDelivery.DELIVERY_UNKNOWN));
            assertEquals(List.of("messages"), rig.endpoints());
        });
    }

    @Test
    void aLocalWriteFailureAfterSendingDoesNotTurnDeliveryIntoFailure() {
        withSender(rig -> {
            var path = dataDirectory.resolve("qqbot/directory.json");
            var backup = dataDirectory.resolve("qqbot/directory-backup.json");
            assertDoesNotThrow(() -> Files.move(path, backup));
            assertDoesNotThrow(() -> Files.createDirectory(path));
            try {
                assertEquals(JSONObject.of("code", 0, "id", "message-1"), rig.send("开播了"));
                assertEquals(List.of("messages"), rig.endpoints());
            } finally {
                assertDoesNotThrow(() -> Files.delete(path));
                assertDoesNotThrow(() -> Files.move(backup, path));
            }
        });
    }

    private void withSender(Consumer<Rig> test) {
        withSender(new QqBotImageDownloader(mock(HttpClient.class)), test);
    }

    private void withSender(QqBotImageDownloader downloader, Consumer<Rig> test) {
        Rig rig = new Rig();
        new ApplicationContextRunner().withUserConfiguration(QqBotPluginAutoConfiguration.class)
                .withBean("qqBotSettings", QqBotSettings.class, () -> new QqBotSettings(dataDirectory))
                .withBean(NovaSenderService.class, () -> new NovaSenderService(new NovaCoreProperties()))
                .withBean(QqBotHttpTransport.class, () -> rig::respond, definition -> definition.setPrimary(true))
                .withBean(QqBotGatewayScheduler.class, QqBotGatewayTest.ManualScheduler::new,
                        definition -> definition.setPrimary(true))
                .withBean(QqBotImageDownloader.class, () -> downloader, definition -> definition.setPrimary(true))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    rig.registry = context.getBean(NovaSenderService.class);
                    assertTrue(rig.registry.getSender(PLATFORM).isEmpty());
                    rig.credentials = context.getBean(QqBotCredentials.class);
                    rig.credentials.save(APP_ID, SECRET);
                    rig.sender = rig.registry.getSender(PLATFORM).orElseThrow(
                            () -> new AssertionError("保存凭据后 QQ 官方机器人应当能投递群消息"));
                    assertEquals("QQ 官方机器人", rig.registry.displayName(PLATFORM));
                    assertEquals(2000, rig.sender.getDelay());
                    rig.directory = context.getBean(QqBotDirectory.class);
                    rig.number = rig.directory.groupJoined(OPENID, Instant.now());
                    rig.directory.renameGroup(rig.number, "测试群");
                    await().atMost(Duration.ofSeconds(10)).until(() -> {
                        var group = rig.group();
                        return group.info() != null && group.botState() != null;
                    });
                    test.accept(rig);
                });
    }

    private static QqBotImageDownloader fakeDownloader(byte[] bytes, boolean declaresSize, AtomicBoolean cancelled) {
        HttpClient client = mock(HttpClient.class);
        when(client.<byte[]>sendAsync(any(HttpRequest.class), any())).thenAnswer(call -> {
            HttpRequest request = call.getArgument(0);
            assertEquals("GET", request.method());
            assertEquals(URI.create("https://images.example.invalid/@everyone/cover.png"), request.uri());
            assertEquals(Duration.ofSeconds(15), request.timeout().orElseThrow());
            assertTrue(request.headers().map().isEmpty());
            HttpResponse.BodyHandler<byte[]> handler = call.getArgument(1);
            HttpResponse.ResponseInfo info = mock(HttpResponse.ResponseInfo.class);
            Map<String, List<String>> headers = declaresSize
                    ? Map.of("Content-Length", List.of(Integer.toString(bytes.length))) : Map.of();
            when(info.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
            var subscriber = handler.apply(info);
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {}

                @Override
                public void cancel() {
                    cancelled.set(true);
                }
            });
            if (!cancelled.get()) {
                int firstChunk = bytes.length / 2;
                subscriber.onNext(List.of(ByteBuffer.wrap(bytes, 0, firstChunk)));
                subscriber.onNext(List.of(ByteBuffer.wrap(bytes, firstChunk, bytes.length - firstChunk)));
            }
            if (!cancelled.get()) {
                subscriber.onComplete();
            }
            HttpResponse<byte[]> response = mock();
            when(response.statusCode()).thenReturn(200);
            return subscriber.getBody().thenApply(body -> {
                when(response.body()).thenReturn(body);
                return response;
            }).toCompletableFuture();
        });
        return new QqBotImageDownloader(client);
    }

    private static JSONObject media() {
        return JSONObject.of("file_info", FILE_INFO);
    }

    private static QqBotHttpTransport.Response json(Object... pairs) {
        JSONObject response = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) {
            response.put((String) pairs[i], pairs[i + 1]);
        }
        return new QqBotHttpTransport.Response(200, response.toJSONString());
    }

    @FunctionalInterface
    private interface Exchange {
        QqBotHttpTransport.Response respond(String endpoint) throws IOException;
    }

    private record Request(String endpoint, JSONObject body) {}

    private static final class Rig {
        private final List<Request> requests = new ArrayList<>();
        private Exchange exchange;
        private QqBotCredentials credentials;
        private NovaSenderService registry;
        private Sender sender;
        private QqBotDirectory directory;
        private long number;
        private int sent;

        Rig() {
            exchange = endpoint -> endpoint.equals("files") ? json("file_info", FILE_INFO)
                    : json("id", "message-" + ++sent, "timestamp", "2026-11-24T15:20:00Z");
        }

        QqBotHttpTransport.Response respond(String method, URI uri, Map<String, String> headers, String body)
                throws IOException {
            if (uri.getPath().equals("/app/getAppAccessToken")) {
                return json("access_token", TOKEN, "expires_in", 7200);
            }
            if (method.equals("GET")) {
                return json();
            }
            assertEquals("POST", method);
            String path = uri.getPath();
            assertTrue(path.startsWith("/v2/groups/" + OPENID + "/"));
            String endpoint = path.substring(path.lastIndexOf('/') + 1);
            requests.add(new Request(endpoint, JSON.parseObject(body)));
            return exchange.respond(endpoint);
        }

        JSONObject deliver(Map<String, Object> params) {
            return sender.getLocalDelivery().deliver(Map.of(), params);
        }

        JSONObject send(String content) {
            return deliver(Map.of("type", 1, "num", number, "content", content));
        }

        JSONObject reply(String content, String msgId) {
            return deliver(Map.of("type", 1, "num", number, "content", content, "reply_to", msgId));
        }

        List<String> endpoints() {
            return requests.stream().map(Request::endpoint).toList();
        }

        JSONObject body(int index) {
            return requests.get(index).body();
        }

        QqBotDirectory.GroupEntry group() {
            return directory.group(number).orElseThrow();
        }
    }
}
