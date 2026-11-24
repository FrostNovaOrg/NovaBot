package org.frostnova.nova.adapter.qqbot;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.command.CommandContext;
import org.frostnova.nova.core.command.CommandReplyHint;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.health.HealthProbe;
import org.frostnova.nova.core.health.HealthStatus;
import org.frostnova.nova.core.secret.SecretStore;
import org.frostnova.nova.core.service.NovaSenderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class QqBotAdapterTest {
    private static final String APP_ID = "12345678";
    private static final String SECRET = "test-secret-\"with\\escapes";
    private static final String TOKEN = "test-token-for-qq";
    private static final URI TOKEN_URI = URI.create("https://api.bot.qq.com/app/getAppAccessToken");

    @TempDir
    Path dataDirectory;

    @Test
    void replyHintsRequireAMentionOnlyInOfficialGroups() {
        new ApplicationContextRunner()
                .withUserConfiguration(QqBotPluginAutoConfiguration.class, CommandReplyHint.class)
                .withBean("qqBotSettings", QqBotSettings.class, () -> new QqBotSettings(dataDirectory))
                .withBean(NovaSenderService.class, () -> new NovaSenderService(new NovaCoreProperties()))
                .run(context -> {
                    var hints = context.getBean(CommandReplyHint.class);
                    var group = new CommandContext("qq-official", PushTargetType.GROUP, 1L, 2L, "", List.of(), "");
                    var friend = new CommandContext("qq-official", PushTargetType.FRIEND, 2L, 2L, "", List.of(), "");
                    var oneBot = new CommandContext("qq-onebot", PushTargetType.GROUP, 1L, 2L, "", List.of(), "");
                    assertEquals("@我 回", hints.replyVerb(group));
                    assertEquals("回", hints.replyVerb(friend));
                    assertEquals("回", hints.replyVerb(oneBot));
                });
    }

    @Test
    void pluginLoadsItsServicesAndChecksStoredCredentialsOnTheReadyEvent() throws IOException {
        Path directory = dataDirectory.resolve("qqbot");
        new SecretStore(directory, "app-secret").save(SECRET);
        Files.writeString(directory.resolve("settings.json"),
                JSONObject.of("appId", APP_ID, "intervalMillis", 4500).toJSONString());
        AtomicInteger exchanges = new AtomicInteger();
        QqBotHttpTransport transport = (method, uri, headers, body) -> {
            exchanges.incrementAndGet();
            return token(TOKEN, 7200);
        };
        new ApplicationContextRunner().withUserConfiguration(QqBotPluginAutoConfiguration.class)
                .withBean("qqBotSettings", QqBotSettings.class, () -> new QqBotSettings(dataDirectory))
                .withBean(NovaSenderService.class, () -> new NovaSenderService(new NovaCoreProperties()))
                .withBean(QqBotHttpTransport.class, () -> transport, definition -> definition.setPrimary(true))
                .withBean(QqBotGatewayScheduler.class, QqBotGatewayTest.ManualScheduler::new,
                        definition -> definition.setPrimary(true))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(QqBotCredentials.class));
                    assertNotNull(context.getBean(QqBotApiClient.class));
                    NovaSenderService senders = context.getBean(NovaSenderService.class);
                    assertTrue(senders.getSender("qq-official").isEmpty());
                    QqBotHealthProbe probe = context.getBean(QqBotHealthProbe.class);
                    assertTrue(probe.active());
                    assertEquals(0, exchanges.get());
                    context.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0],
                            context.getSourceApplicationContext(), Duration.ZERO));
                    assertEquals(1, exchanges.get());
                    assertEquals(HealthStatus.Level.DEGRADED, probe.check().level());
                    assertTrue(probe.check().summary().contains("正在连接"));
                    assertEquals(4500, senders.getSender("qq-official").orElseThrow().getDelay());
                });
    }

    @ParameterizedTest
    @MethodSource("brokenSettingsFiles")
    @ExtendWith(OutputCaptureExtension.class)
    void brokenSettingsLeaveApplicationReadyAndShowHowToRecover(byte[] original, CapturedOutput output)
            throws IOException {
        Path directory = dataDirectory.resolve("qqbot");
        new SecretStore(directory, "app-secret").save(SECRET);
        Path settings = directory.resolve("settings.json");
        Files.write(settings, original);
        AtomicInteger exchanges = new AtomicInteger();
        QqBotHttpTransport transport = (method, uri, headers, body) -> {
            exchanges.incrementAndGet();
            return token(TOKEN, 7200);
        };

        new ApplicationContextRunner().withUserConfiguration(QqBotPluginAutoConfiguration.class)
                .withBean("qqBotSettings", QqBotSettings.class, () -> new QqBotSettings(dataDirectory))
                .withBean(NovaSenderService.class, () -> new NovaSenderService(new NovaCoreProperties()))
                .withBean(QqBotHttpTransport.class, () -> transport, definition -> definition.setPrimary(true))
                .withBean(QqBotGatewayScheduler.class, QqBotGatewayTest.ManualScheduler::new,
                        definition -> definition.setPrimary(true))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    context.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0],
                            context.getSourceApplicationContext(), Duration.ZERO));
                    QqBotCredentials credentials = context.getBean(QqBotCredentials.class);
                    assertFalse(credentials.status().configured());
                    assertTrue(credentials.status().appId().isEmpty());
                    QqBotHealthProbe probe = context.getBean(QqBotHealthProbe.class);
                    assertTrue(probe.active(), "首页应显示无法读取设置的探针");
                    HealthStatus health = probe.check();
                    assertEquals(HealthStatus.Level.DOWN, health.level());
                    assertTrue(health.summary().contains("设置文件无法读取"));
                    assertTrue(health.advice().contains("连接页重新填写"));
                    assertEquals(0, exchanges.get());
                    assertArrayEquals(original, Files.readAllBytes(settings));
                    assertEquals(1, output.getAll().lines()
                            .filter(line -> line.contains("WARN") && line.contains(settings.toString())).count());
                    assertFalse(output.getAll().contains(APP_ID));
                    assertNoCredentials(output.getAll());

                    assertThrows(IllegalArgumentException.class, () -> credentials.save(APP_ID, "invalid secret"));
                    assertArrayEquals(original, Files.readAllBytes(settings));
                    assertEquals(0, exchanges.get());
                    credentials.save(APP_ID, SECRET);
                    assertEquals(1, exchanges.get());
                    assertTrue(credentials.status().configured());
                    assertEquals(HealthStatus.Level.DEGRADED, probe.check().level());
                    assertTrue(probe.check().summary().contains("正在连接"));
                    assertEquals(APP_ID, JSON.parseObject(Files.readString(settings)).getString("appId"));
                });
    }

    private static Stream<byte[]> brokenSettingsFiles() {
        String complete = JSONObject.of("appId", APP_ID, "unused", SECRET + " " + TOKEN).toJSONString();
        return Stream.of(new byte[0],
                complete.substring(0, complete.length() - 1).getBytes(StandardCharsets.UTF_8),
                ("{'appId':'" + APP_ID + "'}").getBytes(StandardCharsets.UTF_8),
                new byte[] {(byte) 0xc3});
    }

    @Test
    void missingAppIdDoesNotSendAnyRequestEvenWithStoredSecret() {
        new SecretStore(dataDirectory.resolve("qqbot"), "app-secret").save(SECRET);
        Rig rig = rig((method, uri, headers, body) -> {
            fail("未配置 AppID 时不应请求 QQ 开放平台");
            return null;
        });

        rig.credentials.onApplicationReady();
        assertFalse(rig.probe.active());
        assertEquals("QQ 官方机器人", rig.probe.name());
        assertEquals(HealthProbe.Scope.BOT, rig.probe.scope());
        assertEquals(21, rig.probe.order());
        assertEquals(HealthStatus.Level.DOWN, rig.probe.check().level());
    }

    @Test
    void savesCredentialsAndRechecksThemOnceOnStartup() throws IOException {
        Path settings = dataDirectory.resolve("qqbot/settings.json");
        AtomicInteger requests = new AtomicInteger();
        QqBotHttpTransport transport = (method, uri, headers, body) -> {
            assertEquals("POST", method);
            assertEquals(TOKEN_URI, uri);
            assertFalse(headers.containsKey("Authorization"));
            JSONObject request = JSON.parseObject(body);
            assertEquals(APP_ID, request.getString("appId"));
            assertTrue(SECRET.equals(request.getString("clientSecret")));
            requests.incrementAndGet();
            return token(TOKEN, 7200);
        };
        Rig first = rig(transport);
        assertEquals(2000, first.credentials.intervalMillis());
        first.credentials.save(APP_ID, SECRET);
        assertEquals(JSONObject.of("appId", APP_ID, "intervalMillis", 2000), JSON.parseObject(Files.readString(settings)));
        assertEquals(1, requests.get());
        assertTrue(first.probe.active());
        assertEquals("凭据有效", first.tokens.health().summary());

        Files.writeString(settings, JSONObject.of("appId", APP_ID, "intervalMillis", 4500).toJSONString());
        Rig restarted = rig(transport);
        assertEquals(4500, restarted.credentials.intervalMillis());
        assertTrue(restarted.probe.active());
        assertEquals(APP_ID, restarted.credentials.status().appId());
        assertFalse(restarted.credentials.status().maskedSecret().isBlank());
        assertEquals(1, requests.get());
        restarted.credentials.onApplicationReady();
        assertEquals(2, requests.get());
        assertEquals(HealthStatus.Level.OK, restarted.tokens.health().level());
        restarted.credentials.save(APP_ID, "");
        assertEquals(3, requests.get());
        assertEquals(4500, JSON.parseObject(Files.readString(settings)).getIntValue("intervalMillis"));
    }

    @Test
    void invalidAppIdAndSecretLeaveExistingCredentialsUsable() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        Rig rig = rig((method, uri, headers, body) -> {
            requests.incrementAndGet();
            assertTrue(SECRET.equals(JSON.parseObject(body).getString("clientSecret")));
            return token(TOKEN, 7200);
        });
        assertThrows(IllegalArgumentException.class, () -> rig.credentials.save(APP_ID, ""));
        rig.credentials.save(APP_ID, SECRET);
        Path settings = dataDirectory.resolve("qqbot/settings.json");
        byte[] original = Files.readAllBytes(settings);

        assertThrows(IllegalArgumentException.class, () -> rig.credentials.save("12ab", "replacement"));
        assertThrows(IllegalArgumentException.class, () -> rig.credentials.save("124", "invalid secret"));
        assertEquals(APP_ID, rig.credentials.status().appId());
        assertArrayEquals(original, Files.readAllBytes(settings));
        assertEquals(1, requests.get());
        rig.credentials.save(APP_ID, null);
        assertEquals(2, requests.get());
    }

    @Test
    void pastingAppIdIntoSecretFieldLeavesAllCredentialFilesUntouched() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        Rig rig = rig((method, uri, headers, body) -> {
            requests.incrementAndGet();
            return token(TOKEN, 7200);
        });
        rig.credentials.save(APP_ID, SECRET);
        Path directory = dataDirectory.resolve("qqbot");
        Path settings = directory.resolve("settings.json");
        Path encrypted = directory.resolve("app-secret.enc");
        Path masterKey = directory.resolve("app-secret-master.key");
        byte[] originalSettings = Files.readAllBytes(settings);
        byte[] originalEncrypted = Files.readAllBytes(encrypted);
        byte[] originalMasterKey = Files.readAllBytes(masterKey);
        var originalStatus = rig.credentials.status();

        String pastedAppId = "12345678901234567890";
        var error = assertThrows(IllegalArgumentException.class,
                () -> rig.credentials.save(pastedAppId, pastedAppId));
        assertTrue(error.getMessage().contains("AppSecret 和 AppID 一样"));
        assertTrue(error.getMessage().contains("到 QQ 开放平台复制 AppSecret"));
        assertArrayEquals(originalSettings, Files.readAllBytes(settings));
        assertArrayEquals(originalEncrypted, Files.readAllBytes(encrypted));
        assertArrayEquals(originalMasterKey, Files.readAllBytes(masterKey));
        assertEquals(originalStatus, rig.credentials.status());
        assertEquals(1, requests.get());
    }

    @ParameterizedTest
    @CsvSource(value = {
            "100016|AppID 或 AppSecret 不对，请到连接页重填",
            "100007|机器人被封禁或已删除",
            "10004|这个 AppID 下没有机器人，请核对 AppID",
            "100001|取令牌太频繁，稍后自动再试",
            "987654|取令牌失败（错误码 987654）"
    }, delimiter = '|')
    void failedCredentialsShowActionableHealthWithoutProbing(int code, String summary) {
        AtomicInteger requests = new AtomicInteger();
        Rig rig = rig((method, uri, headers, body) -> {
            requests.incrementAndGet();
            return response(200, JSONObject.of("code", code, "message", "请勿使用远端说明"));
        });

        rig.credentials.save(APP_ID, SECRET);
        assertTrue(rig.probe.active());
        assertEquals(HealthStatus.Level.DOWN, rig.probe.check().level());
        assertEquals(summary, rig.probe.check().summary());
        assertFalse(rig.probe.check().advice().isBlank());
        assertEquals(1, requests.get());
    }

    @Test
    void timeoutShowsNetworkAdviceWithoutExposingExceptionDetails() {
        Rig rig = rig((method, uri, headers, body) -> {
            throw new HttpTimeoutException(body);
        });
        rig.credentials.save(APP_ID, SECRET);
        HealthStatus health = rig.probe.check();
        assertEquals(HealthStatus.Level.DOWN, health.level());
        assertEquals("连不上 QQ 开放平台，请检查本机网络", health.summary());
        assertFalse(health.toString().contains(SECRET));
    }

    @Test
    void unreadableEncryptedSecretShowsAdviceWithoutTakingDownStartup() throws IOException {
        Path directory = dataDirectory.resolve("qqbot");
        new SecretStore(directory, "app-secret").save(SECRET);
        Files.writeString(directory.resolve("settings.json"), JSONObject.of("appId", APP_ID).toJSONString());
        Files.writeString(directory.resolve("app-secret.enc"), "invalid encrypted content");
        Rig rig = rig((method, uri, headers, body) -> {
            fail("密钥无法解密时不应请求 QQ 开放平台");
            return null;
        });

        rig.credentials.onApplicationReady();
        assertTrue(rig.probe.active());
        assertEquals("…", rig.credentials.status().maskedSecret());
        assertEquals(HealthStatus.Level.DOWN, rig.probe.check().level());
        assertTrue(rig.probe.check().summary().contains("密钥无法读取或解密"));
        assertTrue(rig.probe.check().advice().contains("重新填写 AppSecret"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"7200\"", "7200", "7200.0", ""})
    void refreshesTokenBeforeExpiryForBothWireFormatsAndDefault(String expiresIn) {
        AtomicInteger requests = new AtomicInteger();
        Rig rig = rig((method, uri, headers, body) -> {
            JSONObject response = JSONObject.of("access_token", TOKEN + requests.incrementAndGet());
            if (!expiresIn.isEmpty()) {
                response.put("expires_in", JSON.parse(expiresIn));
            }
            return response(200, response);
        });

        rig.credentials.save(APP_ID, SECRET);
        assertSame(rig.tokens.token(), rig.tokens.token());
        rig.clock.advance(7200 - 55);
        assertTrue((TOKEN + "1").equals(rig.tokens.token().value()));
        assertEquals(1, requests.get());
        rig.clock.advance(5);
        assertTrue((TOKEN + "2").equals(rig.tokens.token().value()));
        assertEquals(2, requests.get());
    }

    @Test
    void keepsHttpStatusAndHandlesErrCodeInAnHttpSuccess() {
        Rig rig = rig((method, uri, headers, body) -> uri.equals(TOKEN_URI) ? token(TOKEN, 7200)
                : response(200, JSONObject.of("err_code", 12501, "message", "没有权限", "trace_id", "trace-fixture")));
        rig.credentials.save(APP_ID, SECRET);

        QqBotApiException error = assertThrows(QqBotApiException.class, () -> rig.api.request("GET", "/gateway", null));
        assertEquals(12501, error.code());
        assertEquals("没有权限", error.getMessage());
        assertEquals("trace-fixture", error.traceId());
        assertEquals(200, error.httpStatus());
    }

    @ParameterizedTest
    @CsvSource({"401, 0, 2", "200, 11241, 2", "200, 11243, 2", "200, 11242, 1"})
    void retriesOnceAndUsesANewTokenOnlyWhenTheOriginalIsRejected(int httpStatus, int code, int tokenRequests) {
        List<String> authorization = new ArrayList<>();
        AtomicInteger exchanges = new AtomicInteger();
        Rig rig = rig((method, uri, headers, body) -> {
            if (uri.equals(TOKEN_URI)) {
                return token(TOKEN + exchanges.incrementAndGet(), 7200);
            }
            assertEquals(URI.create("https://api.bot.qq.com/gateway"), uri);
            authorization.add(headers.get("Authorization"));
            return response(httpStatus, JSONObject.of("err_code", code));
        });
        rig.credentials.save(APP_ID, SECRET);

        QqBotApiException error = assertThrows(QqBotApiException.class, () -> rig.api.request("GET", "/gateway", null));
        assertEquals(httpStatus, error.httpStatus());
        assertEquals(code, error.code());
        assertEquals(tokenRequests, exchanges.get());
        assertTrue(authorization.equals(List.of("QQBot " + TOKEN + "1", "QQBot " + TOKEN + tokenRequests)));
        assertEquals(tokenRequests == 2 ? HealthStatus.Level.DOWN : HealthStatus.Level.OK, rig.tokens.health().level());
    }

    @Test
    void acceptsSuccessfulRetryAndRejectsPathsOutsideTheOfficialOrigin() {
        AtomicInteger calls = new AtomicInteger();
        Rig rig = rig((method, uri, headers, body) -> {
            if (uri.equals(TOKEN_URI)) {
                return token(TOKEN + calls.get(), 7200);
            }
            return calls.incrementAndGet() == 1 ? new QqBotHttpTransport.Response(401, "Unauthorized")
                    : response(200, JSONObject.of("url", "wss://example.invalid"));
        });
        rig.credentials.save(APP_ID, SECRET);
        assertThrows(IllegalArgumentException.class, () -> rig.api.request("GET", "//example.invalid", null));
        assertThrows(IllegalArgumentException.class, () -> rig.api.request("GET", "https://example.invalid", null));
        assertEquals(0, calls.get());
        assertEquals(JSONObject.of("url", "wss://example.invalid"), rig.api.request("GET", "/gateway", null));
        assertEquals(2, calls.get());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void credentialsNeverAppearInFilesLogsExceptionsOrApiResults(CapturedOutput output) throws IOException {
        Rig rig = rig((method, uri, headers, body) -> {
            if (uri.equals(TOKEN_URI)) {
                return token(TOKEN, 7200);
            }
            return switch (uri.getPath()) {
                case "/error" -> response(500, JSONObject.of("code", 50001, "message", "unexpected " + SECRET,
                        "trace_id", "QQBot " + TOKEN));
                case "/echo" -> response(400, JSONObject.of("code", 50001, "message",
                        JSONObject.of("clientSecret", SECRET).toJSONString()));
                case "/io" -> throw new IOException("Authorization: " + headers.get("Authorization") + " " + SECRET);
                default -> response(200, JSONObject.of("access_token", "unexpected credential",
                        SECRET, "unexpected key", "clientSecret", "unexpected field",
                        "data", List.of(JSONObject.of("detail", TOKEN, "value", "before " + SECRET + " after",
                                "ordinary", "保留正常结果"))));
            };
        });
        rig.credentials.save(APP_ID, SECRET);
        assertTrue(rig.probe.active());
        assertFalse(rig.credentials.status().toString().contains(SECRET));
        assertFalse(rig.tokens.token().toString().contains(TOKEN));

        QqBotApiException error = assertThrows(QqBotApiException.class, () -> rig.api.request("GET", "/error", null));
        assertEquals(50001, error.code());
        assertNull(error.getCause());
        assertNoCredentials(error.toString() + error.traceId());
        QqBotApiException echo = assertThrows(QqBotApiException.class, () -> rig.api.request("GET", "/echo", null));
        assertNoCredentials(echo.getMessage());
        QqBotApiException network = assertThrows(QqBotApiException.class, () -> rig.api.request("GET", "/io", null));
        assertNull(network.getCause());
        assertNoCredentials(network.toString());
        String result = JSON.toJSONString(rig.api.request("GET", "/success", null));
        assertNoCredentials(result);
        assertFalse(result.contains("unexpected"));
        assertTrue(result.contains("保留正常结果"));
        assertNoCredentials(output.getAll());
        assertFalse(output.getAll().contains("Authorization:"));
        assertFalse(output.getAll().contains("clientSecret"));

        Path directory = dataDirectory.resolve("qqbot");
        try (var paths = Files.walk(dataDirectory)) {
            List<Path> files = paths.filter(Files::isRegularFile).sorted().toList();
            assertEquals(List.of("app-secret-master.key", "app-secret.enc", "settings.json"),
                    files.stream().map(path -> path.getFileName().toString()).toList());
            for (Path file : files) {
                byte[] bytes = Files.readAllBytes(file);
                assertFalse(contains(bytes, SECRET));
                assertFalse(contains(bytes, jsonStringContents(SECRET)));
                assertFalse(contains(bytes, TOKEN));
                if (Files.getFileStore(file).supportsFileAttributeView(PosixFileAttributeView.class)) {
                    assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
                }
            }
        }
        if (Files.getFileStore(directory).supportsFileAttributeView(PosixFileAttributeView.class)) {
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
        }
    }

    @Test
    void tenConcurrentCallersShareOneTokenRequest() throws Exception {
        AtomicInteger exchanges = new AtomicInteger();
        CountDownLatch refreshing = new CountDownLatch(1);
        CountDownLatch allowReply = new CountDownLatch(1);
        Rig rig = rig((method, uri, headers, body) -> {
            int call = exchanges.incrementAndGet();
            if (call > 1) {
                refreshing.countDown();
                assertTrue(allowReply.await(10, TimeUnit.SECONDS));
            }
            return token(TOKEN + call, 7200);
        });
        rig.credentials.save(APP_ID, SECRET);
        rig.clock.advance(7150);

        var callers = Executors.newFixedThreadPool(10);
        CountDownLatch ready = new CountDownLatch(10);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<QqBotTokenService.Token>> results = new ArrayList<>();
            for (int thread = 0; thread < 10; thread++) {
                results.add(callers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return rig.tokens.token();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(refreshing.await(10, TimeUnit.SECONDS));
            assertEquals(HealthStatus.Level.OK, rig.tokens.health().level());
            allowReply.countDown();
            var token = results.get(0).get(10, TimeUnit.SECONDS);
            for (var result : results) {
                assertSame(token, result.get(10, TimeUnit.SECONDS));
            }
            assertEquals(2, exchanges.get());
        } finally {
            start.countDown();
            allowReply.countDown();
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private Rig rig(QqBotHttpTransport transport) {
        TestClock clock = new TestClock();
        QqBotTokenService tokens = new QqBotTokenService(new QqBotSettings(dataDirectory), transport, clock);
        QqBotApiClient api = new QqBotApiClient(transport, tokens);
        var gateway = new QqBotGateway(api, tokens, (uri, listener) -> fail("不应连接网关"),
                new QqBotGatewayTest.ManualScheduler(), new DefaultListableBeanFactory().getBeanProvider(QqBotEventListener.class));
        var credentials = new QqBotCredentials(tokens, gateway, mock(QqBotSender.class));
        var probe = new QqBotHealthProbe(tokens, gateway, mock(QqBotDirectory.class), Optional.empty());
        return new Rig(clock, tokens, credentials, probe, api);
    }

    private static QqBotHttpTransport.Response token(String token, Object expiresIn) {
        return response(200, JSONObject.of("access_token", token, "expires_in", expiresIn));
    }

    private static QqBotHttpTransport.Response response(int status, JSONObject body) {
        return new QqBotHttpTransport.Response(status, body.toJSONString());
    }

    private static void assertNoCredentials(String text) {
        assertFalse(text.contains(SECRET));
        assertFalse(text.contains(jsonStringContents(SECRET)));
        assertFalse(text.contains(TOKEN));
    }

    private static String jsonStringContents(String text) {
        String json = JSON.toJSONString(text);
        return json.substring(1, json.length() - 1);
    }

    private static boolean contains(byte[] data, String value) {
        byte[] expected = value.getBytes(StandardCharsets.UTF_8);
        for (int offset = 0; offset <= data.length - expected.length; offset++) {
            if (Arrays.equals(data, offset, offset + expected.length, expected, 0, expected.length)) {
                return true;
            }
        }
        return false;
    }

    private record Rig(TestClock clock, QqBotTokenService tokens, QqBotCredentials credentials, QqBotHealthProbe probe,
                       QqBotApiClient api) {
    }

    private static final class TestClock extends Clock {
        private volatile Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(long seconds) {
            now = now.plusSeconds(seconds);
        }
    }
}
