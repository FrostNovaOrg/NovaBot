package org.frostnova.nova.adapter.qqbot;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.health.HealthStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class QqBotGatewayTest {
    private static final String APP_ID = "12345678";
    private static final String SECRET = "fixture-gateway-secret";
    private static final String TOKEN = "fixture-gateway-token";
    private static final URI GATEWAY_URL = URI.create("wss://gateway.example.invalid/connect");
    private static final URI FALLBACK_URL = URI.create("wss://fallback.example.invalid/connect");

    @TempDir
    Path dataDirectory;

    @Test
    void storedCredentialsIdentifyOnApplicationReadyAndReportConnectedRobot() {
        new QqBotSettings(dataDirectory).save(APP_ID, SECRET);
        withRig(rig -> {
            assertTrue(rig.probe.active());
            assertTrue(rig.gateway.connections.isEmpty());
            rig.readyEvent.run();
            rig.scheduler.advance(0);
            assertEquals(HealthStatus.Level.DEGRADED, rig.probe.check().level());
            assertTrue(rig.probe.check().summary().contains("正在连接 QQ 开放平台"));

            var connection = rig.gateway.last();
            assertTrue(connection.frames(2).isEmpty());
            connection.hello(45_000);
            rig.scheduler.advance(0);
            var identify = connection.onlyFrame(2).getJSONObject("d");
            assertTrue(("QQBot " + TOKEN).equals(identify.getString("token")));
            assertEquals(33_554_432, identify.getIntValue("intents"));
            assertEquals(List.of(0, 1), identify.getJSONArray("shard"));
            assertTrue(identify.getJSONObject("properties").isEmpty());

            rig.ready(connection);
            assertEquals(HealthStatus.Level.OK, rig.probe.check().level());
            assertTrue(rig.probe.check().summary().contains("已连接 · 网关测试机器人"));
        });
    }

    @Test
    void liveConnectionStaysHealthyAfterCachedTokenExpiresUntilRefreshFails() {
        withRig(rig -> {
            var connection = rig.connect();
            for (int elapsedSeconds = 0; elapsedSeconds <= 7_200; elapsedSeconds += 45) {
                connection.receive(11, null);
                rig.scheduler.advance(45_000);
            }

            var message = new Event("GROUP_AT_MESSAGE_CREATE", JSONObject.of("content", "ping"), "late-event", 5L);
            connection.dispatch(message.name(), message.data(), message.id(), message.sequence());
            rig.scheduler.advance(0);
            assertEquals(List.of(message), rig.events);
            assertEquals(1, rig.gateway.connections.size());
            assertFalse(connection.closed);

            var connected = rig.probe.check();
            assertEquals(HealthStatus.Level.OK, connected.level(), connected::summary);
            assertTrue(connected.summary().startsWith("已连接 · "));
            assertTrue(connected.summary().contains("网关测试机器人"));
            assertEquals(1, rig.http.tokenRequests);

            rig.http.tokenError = response(200, JSONObject.of("code", 100016));
            var error = assertThrows(QqBotApiException.class, () -> rig.api.request("GET", "/gateway/bot", null));
            assertEquals(100016, error.code());
            assertEquals(2, rig.http.tokenRequests);
            assertFalse(connection.closed);
            var rejected = rig.probe.check();
            assertEquals(HealthStatus.Level.DOWN, rejected.level());
            assertTrue(rejected.summary().contains("AppID 或 AppSecret 不对"));
        });
    }

    @Test
    void expiredConnectionResumesWithLastSequenceAndDeliversCommandsAgain() {
        withRig(rig -> {
            var first = rig.connect();
            first.dispatch("GROUP_AT_MESSAGE_CREATE", JSONObject.of("content", "first"), "event-before", 5);
            rig.scheduler.advance(0);
            rig.scheduler.advance(6_000);
            first.remoteClose(4009);

            rig.scheduler.advance(999);
            assertEquals(1, rig.gateway.connections.size());
            rig.scheduler.advance(1);
            var second = rig.gateway.last();
            second.hello(45_000);
            rig.scheduler.advance(0);

            JSONObject resume = second.onlyFrame(6).getJSONObject("d");
            assertTrue(("QQBot " + TOKEN + "-renewed").equals(resume.getString("token")));
            assertEquals(2, rig.http.tokenRequests);
            assertEquals("session-fixture", resume.getString("session_id"));
            assertEquals(5L, resume.getLongValue("seq"));
            assertTrue(second.frames(2).isEmpty());

            JSONObject command = JSONObject.of("content", "again");
            second.dispatch("GROUP_AT_MESSAGE_CREATE", command, "replayed-one", 6);
            second.dispatch("GROUP_ADD_ROBOT", JSONObject.of("group_openid", "fixture-group"), "replayed-two", 7);
            second.dispatch("RESUMED", new JSONObject(), null, 8);
            rig.scheduler.advance(0);
            assertEquals(List.of("event-before", "replayed-one", "replayed-two"),
                    rig.events.stream().map(Event::id).toList());
            assertEquals(new Event("GROUP_AT_MESSAGE_CREATE", command, "replayed-one", 6L), rig.events.get(1));
            assertEquals(HealthStatus.Level.OK, rig.probe.check().level());
            assertTrue(rig.probe.check().summary().contains("网关测试机器人"));
        }, http -> http.expiresIn = 60);
    }

    @Test
    void bannedRobotStopsReconnectingAndReportsWhy() {
        withRig(rig -> {
            var first = rig.connect();
            first.remoteClose(4915);
            rig.scheduler.advance(60_000);

            assertTrue(first.closed);
            assertEquals(1, rig.gateway.connections.size());
            assertEquals(HealthStatus.Level.DOWN, rig.probe.check().level());
            assertTrue(rig.probe.check().summary().contains("机器人被封禁"));

            rig.readyEvent.run();
            rig.scheduler.advance(60_000);
            assertEquals(1, rig.gateway.connections.size());
            rig.credentials.save(APP_ID, "");
            rig.scheduler.advance(0);
            var next = rig.gateway.last();
            assertNotSame(first, next);
            next.hello(45_000);
            rig.scheduler.advance(0);
            assertEquals(2, next.onlyFrame(2).getIntValue("op"));
        });
    }

    @Test
    void twoMissedHeartbeatAcksDisconnectAndResumeInsteadOfStayingHealthy() {
        withRig(rig -> {
            var first = rig.connect();
            first.dispatch("GROUP_AT_MESSAGE_CREATE", new JSONObject(), null, 5);
            rig.scheduler.advance(0);
            assertEquals(HealthStatus.Level.OK, rig.probe.check().level());
            assertTrue(first.onlyFrame(1).containsKey("d"));
            assertNull(first.onlyFrame(1).get("d"));

            rig.scheduler.advance(45_000);
            assertEquals(5L, first.frames(1).get(1).getLongValue("d"));
            assertFalse(first.closed);
            rig.scheduler.advance(45_000);
            assertTrue(first.closed);
            assertEquals(HealthStatus.Level.DEGRADED, rig.probe.check().level());
            assertTrue(rig.probe.check().summary().contains("正在重连"));

            rig.scheduler.advance(1_000);
            var second = rig.gateway.last();
            second.hello(45_000);
            rig.scheduler.advance(0);
            assertEquals(5L, second.onlyFrame(6).getJSONObject("d").getLongValue("seq"));
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {4006, 4007, 4900, 4913})
    void rejectedSessionAndPlatformInternalErrorsRequireIdentify(int code) {
        withRig(rig -> {
            rig.connect().remoteClose(code);
            rig.scheduler.advance(1_000);
            var next = rig.gateway.last();
            next.hello(45_000);
            rig.scheduler.advance(0);
            assertEquals(2, next.onlyFrame(2).getIntValue("op"));
            assertTrue(next.frames(6).isEmpty());
        });
    }

    @ParameterizedTest
    @CsvSource({
            "4914, 机器人已下架，只能连沙箱环境",
            "4001, 连接参数被平台拒绝（关闭码 4001）",
            "4002, 连接参数被平台拒绝（关闭码 4002）",
            "4010, 连接参数被平台拒绝（关闭码 4010）",
            "4011, 连接参数被平台拒绝（关闭码 4011）",
            "4012, 连接参数被平台拒绝（关闭码 4012）",
            "4013, 连接参数被平台拒绝（关闭码 4013）",
            "4014, 连接参数被平台拒绝（关闭码 4014）"
    })
    void permanentCloseCodesStopRetriesAndExplainTheFailure(int code, String summary) {
        withRig(rig -> {
            var first = rig.connect();
            first.remoteClose(code);
            rig.scheduler.advance(60_000);
            assertTrue(first.closed);
            assertEquals(1, rig.gateway.connections.size());
            assertEquals(HealthStatus.Level.DOWN, rig.probe.check().level());
            assertTrue(rig.probe.check().summary().contains(summary));
        });
    }

    @Test
    void platformRateLimitWaitsBeforeResuming() {
        withRig(rig -> {
            rig.connect().remoteClose(4008);
            rig.scheduler.advance(4_999);
            assertEquals(1, rig.gateway.connections.size());
            rig.scheduler.advance(1);
            var next = rig.gateway.last();
            next.hello(45_000);
            rig.scheduler.advance(0);
            assertEquals("session-fixture", next.onlyFrame(6).getJSONObject("d").getString("session_id"));
        });
    }

    @ParameterizedTest
    @CsvSource({"7, false, 6", "9, true, 6", "9, false, 2"})
    void gatewayReconnectAndInvalidSessionChooseTheExpectedHandshake(int op, boolean resumable, int handshake) {
        withRig(rig -> {
            rig.connect().receive(op, resumable);
            rig.scheduler.advance(1_000);
            var next = rig.gateway.last();
            next.hello(45_000);
            rig.scheduler.advance(0);
            assertEquals(handshake, next.onlyFrame(handshake).getIntValue("op"));
            assertTrue(next.frames(handshake == 6 ? 2 : 6).isEmpty());
        });
    }

    @Test
    void networkLossAttemptsResumeOnceThenIdentifiesWhenResumeFails() {
        withRig(rig -> {
            var first = rig.connect();
            first.networkFailure();
            rig.scheduler.advance(1_000);
            var second = rig.gateway.last();
            second.hello(45_000);
            rig.scheduler.advance(0);
            assertEquals(6, second.onlyFrame(6).getIntValue("op"));

            rig.scheduler.advance(30_000); // 没收到 RESUMED。
            assertTrue(second.closed);
            rig.scheduler.advance(1_999);
            assertEquals(2, rig.gateway.connections.size());
            rig.scheduler.advance(1);
            var third = rig.gateway.last();
            third.hello(45_000);
            rig.scheduler.advance(0);
            assertEquals(2, third.onlyFrame(2).getIntValue("op"));
        });
    }

    @Test
    void usesTheGeneralGatewayUrlWhenTheShardedEndpointFails() {
        withRig(rig -> {
            rig.http.shardedEndpoint = response(503, JSONObject.of("err_code", 123));
            rig.connect();
            assertEquals(List.of("/gateway/bot", "/gateway"), rig.http.gatewayRequests);
            assertEquals(List.of(FALLBACK_URL), rig.gateway.urls);
        });
    }

    @Test
    void exhaustedSessionStartsWaitForThePlatformReset() {
        withRig(rig -> {
            rig.http.shardedEndpoint = response(200, JSONObject.of("url", GATEWAY_URL.toString(),
                    "session_start_limit", JSONObject.of("remaining", 0, "reset_after", 2_500)));
            rig.credentials.save(APP_ID, SECRET);
            rig.scheduler.advance(2_499);
            assertTrue(rig.gateway.urls.isEmpty());
            assertEquals(List.of("/gateway/bot"), rig.http.gatewayRequests);
            rig.http.shardedEndpoint = response(200, JSONObject.of("url", GATEWAY_URL.toString()));
            rig.scheduler.advance(1);
            assertEquals(List.of("/gateway/bot", "/gateway/bot"), rig.http.gatewayRequests);
            assertEquals(List.of(GATEWAY_URL), rig.gateway.urls);
        });
    }

    @Test
    void retriesBackOffToOneMinuteAndAContinuousOutageEventuallyGoesDown() {
        withRig(rig -> {
            rig.gateway.rejectConnections = true;
            rig.credentials.save(APP_ID, SECRET);
            rig.scheduler.advance(0);
            long[] delays = {1_000, 2_000, 4_000, 8_000, 16_000, 32_000, 60_000, 60_000, 60_000};
            for (int i = 0; i < delays.length; i++) {
                assertEquals(HealthStatus.Level.DEGRADED, rig.probe.check().level());
                assertTrue(rig.probe.check().summary().contains("第 " + (i + 1) + " 次"));
                rig.scheduler.advance(delays[i] - 1);
                assertEquals(i + 1, rig.gateway.urls.size());
                rig.scheduler.advance(1);
                assertEquals(i + 2, rig.gateway.urls.size());
            }
            rig.scheduler.advance(299_999 - rig.scheduler.nowMillis());
            assertEquals(HealthStatus.Level.DEGRADED, rig.probe.check().level());
            rig.scheduler.advance(1);
            assertEquals(HealthStatus.Level.DOWN, rig.probe.check().level());
            assertTrue(rig.probe.check().summary().contains("已超过 5 分钟"));
        });
    }

    @Test
    void healthyConnectionResetsBackoffAfterOneMinute() {
        withRig(rig -> {
            rig.gateway.rejectConnections = true;
            rig.credentials.save(APP_ID, SECRET);
            rig.scheduler.advance(1_000);
            rig.gateway.rejectConnections = false;
            rig.scheduler.advance(2_000);
            var connected = rig.gateway.last();
            connected.hello(45_000);
            rig.scheduler.advance(0);
            rig.ready(connected);
            connected.receive(11, null);
            rig.scheduler.advance(45_000);
            connected.receive(11, null);
            rig.scheduler.advance(15_000);

            connected.remoteClose(4009);
            rig.scheduler.advance(999);
            assertEquals(3, rig.gateway.urls.size());
            rig.scheduler.advance(1);
            assertEquals(4, rig.gateway.urls.size());
        });
    }

    @Test
    void savingDifferentCredentialsReplacesTheConnectionAndClearingCancelsReconnection() {
        withRig(rig -> {
            var first = rig.connect();
            rig.credentials.save("87654321", "fixture-replacement-secret");
            first.dispatch("GROUP_ADD_ROBOT", new JSONObject(), "obsolete-event", 5);
            rig.scheduler.advance(0);
            assertTrue(first.closed);
            assertTrue(rig.events.isEmpty());
            var second = rig.gateway.last();
            second.hello(45_000);
            rig.scheduler.advance(0);
            assertEquals(2, second.onlyFrame(2).getIntValue("op"));
            rig.ready(second);

            second.remoteClose(4009);
            rig.scheduler.advance(0);
            rig.credentials.clear();
            rig.scheduler.advance(60_000);
            assertTrue(second.closed);
            assertFalse(rig.probe.active());
            assertFalse(rig.credentials.status().configured());
            assertTrue(rig.credentials.status().appId().isEmpty());
            assertFalse(new QqBotSettings(dataDirectory).snapshot().configured());
            assertEquals(2, rig.gateway.connections.size());

            rig.connect();
            assertEquals(3, rig.gateway.connections.size());
        });
    }

    @Test
    void dispatchKeepsOriginalDataAndLogsOnlyMaskedEventsEvenWhenAListenerThrows() {
        String openId = "ABCD9876543210WXYZ";
        QqBotEventListener failing = (name, data, id, sequence) -> {
            throw new IOException(TOKEN + " " + openId);
        };
        Logger logger = (Logger) LoggerFactory.getLogger(QqBotGateway.class);
        var originalLevel = logger.getLevel();
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        logger.setLevel(Level.DEBUG);
        try {
            withRig(rig -> {
                var connection = rig.connect();
                JSONObject data = JSONObject.of("group_openid", openId, "author", JSONObject.of("id", openId),
                        "content", "引用 " + openId);
                connection.dispatch("GROUP_ADD_ROBOT", data, "fixture-event", 5);
                connection.dispatch("C2C_MESSAGE_CREATE", JSONObject.of("content", TOKEN), null, 6);
                rig.scheduler.advance(0);
                assertEquals(new Event("GROUP_ADD_ROBOT", data, "fixture-event", 5L), rig.events.get(0));
                assertNull(rig.events.get(1).id());
                assertFalse(connection.closed);
                assertEquals(HealthStatus.Level.OK, rig.probe.check().level());
            }, http -> { }, failing);
        } finally {
            logger.detachAppender(logs);
            logger.setLevel(originalLevel);
            logs.stop();
        }
        var payloads = logs.list.stream().filter(line -> line.getFormattedMessage().contains("收到 QQ 开放平台事件")).toList();
        assertTrue(payloads.stream().allMatch(line -> line.getLevel() == Level.DEBUG));
        assertTrue(payloads.stream().anyMatch(line -> line.getFormattedMessage().contains("ABCD…WXYZ")));
        for (var line : logs.list) {
            assertNull(line.getThrowableProxy());
            assertFalse(line.getFormattedMessage().contains(openId));
            assertFalse(line.getFormattedMessage().contains(TOKEN));
            assertFalse(line.getFormattedMessage().contains(SECRET));
        }
    }

    @Test
    void gatewaySchedulerRunsOnADaemonThread() throws Exception {
        var scheduler = new DaemonQqBotGatewayScheduler();
        try {
            var daemon = new CompletableFuture<Boolean>();
            scheduler.schedule(() -> daemon.complete(Thread.currentThread().isDaemon()), 0);
            assertTrue(daemon.get(10, TimeUnit.SECONDS));
        } finally {
            scheduler.shutdown();
        }
    }

    private void withRig(Consumer<Rig> test) {
        withRig(test, http -> { });
    }

    private void withRig(Consumer<Rig> test, Consumer<FakeHttp> configure) {
        withRig(test, configure, (name, data, id, sequence) -> { });
    }

    private void withRig(Consumer<Rig> test, Consumer<FakeHttp> configure, QqBotEventListener firstListener) {
        ManualScheduler scheduler = new ManualScheduler();
        FakeGateway gateway = new FakeGateway();
        List<Event> events = new ArrayList<>();
        QqBotEventListener listener = (name, data, id, sequence) -> events.add(new Event(name, data, id, sequence));
        var settings = new QqBotSettings(dataDirectory);
        var http = new FakeHttp();
        configure.accept(http);
        var tokens = new QqBotTokenService(settings, http, scheduler.clock());
        new ApplicationContextRunner().withUserConfiguration(QqBotPluginAutoConfiguration.class)
                .withBean("qqBotSettings", QqBotSettings.class, () -> settings)
                .withBean("qqBotTokenService", QqBotTokenService.class, () -> tokens)
                .withBean(QqBotHttpTransport.class, () -> http, definition -> definition.setPrimary(true))
                .withBean(QqBotWebSocketTransport.class, () -> gateway, definition -> definition.setPrimary(true))
                .withBean(QqBotGatewayScheduler.class, () -> scheduler, definition -> definition.setPrimary(true))
                .withBean("firstListener", QqBotEventListener.class, () -> firstListener)
                .withBean("recordingListener", QqBotEventListener.class, () -> listener)
                .run(context -> test.accept(new Rig(context.getBean(QqBotCredentials.class),
                        context.getBean(QqBotHealthProbe.class), context.getBean(QqBotApiClient.class),
                        scheduler, gateway, http, events,
                        () -> context.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0],
                                context.getSourceApplicationContext(), Duration.ZERO)))));
        assertTrue(scheduler.stopped);
        assertTrue(gateway.connections.stream().allMatch(connection -> connection.closed));
    }

    private static QqBotHttpTransport.Response response(int status, JSONObject body) {
        return new QqBotHttpTransport.Response(status, body.toJSONString());
    }

    private record Event(String name, Object data, String id, Long sequence) {
    }

    private record Rig(QqBotCredentials credentials, QqBotHealthProbe probe, QqBotApiClient api,
                       ManualScheduler scheduler, FakeGateway gateway, FakeHttp http, List<Event> events,
                       Runnable readyEvent) {
        FakeConnection connect() {
            credentials.save(APP_ID, SECRET);
            scheduler.advance(0);
            var connection = gateway.last();
            connection.hello(45_000);
            scheduler.advance(0);
            ready(connection);
            return connection;
        }

        void ready(FakeConnection connection) {
            connection.dispatch("READY", JSONObject.of("session_id", "session-fixture",
                    "user", JSONObject.of("username", "网关测试机器人")), null, 1);
            scheduler.advance(0);
        }
    }

    static final class ManualScheduler implements QqBotGatewayScheduler {
        private final PriorityQueue<Task> tasks = new PriorityQueue<>(Comparator.comparingLong(Task::at)
                .thenComparingLong(Task::order));
        private long now;
        private long nextOrder;
        private boolean stopped;

        @Override
        public long nowMillis() {
            return now;
        }

        Clock clock() {
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(ZoneId zone) {
                    return Clock.fixed(instant(), zone);
                }

                @Override
                public Instant instant() {
                    return Instant.EPOCH.plusMillis(nowMillis());
                }
            };
        }

        @Override
        public Future<?> schedule(Runnable task, long delayMillis) {
            if (stopped) {
                throw new RejectedExecutionException();
            }
            var future = new FutureTask<Void>(task, null);
            tasks.add(new Task(now + delayMillis, nextOrder++, future));
            return future;
        }

        @Override
        public void shutdown() {
            stopped = true;
            tasks.clear();
        }

        void advance(long millis) {
            long end = now + millis;
            while (!tasks.isEmpty() && tasks.peek().at() <= end) {
                Task task = tasks.remove();
                now = task.at();
                task.future().run();
                if (!task.future().isCancelled()) {
                    assertDoesNotThrow(() -> task.future().get());
                }
            }
            now = end;
        }

        private record Task(long at, long order, FutureTask<Void> future) {
        }
    }

    private static final class FakeHttp implements QqBotHttpTransport {
        private final List<String> gatewayRequests = new ArrayList<>();
        private int tokenRequests;
        private int expiresIn = 7_200;
        private Response tokenError;
        private Response shardedEndpoint = response(200, JSONObject.of("url", GATEWAY_URL.toString()));

        @Override
        public Response send(String method, URI uri, Map<String, String> headers, String body) {
            switch (uri.getPath()) {
                case "/app/getAppAccessToken":
                    assertEquals("POST", method);
                    tokenRequests++;
                    if (tokenError != null) {
                        return tokenError;
                    }
                    return response(200, JSONObject.of("access_token", tokenValue(), "expires_in", expiresIn));
                case "/gateway/bot", "/gateway":
                    assertEquals("GET", method);
                    assertTrue(("QQBot " + tokenValue()).equals(headers.get("Authorization")));
                    gatewayRequests.add(uri.getPath());
                    return uri.getPath().equals("/gateway/bot") ? shardedEndpoint
                            : response(200, JSONObject.of("url", FALLBACK_URL.toString()));
                default:
                    throw new AssertionError("未预期的接口 " + uri.getPath());
            }
        }

        private String tokenValue() {
            return tokenRequests == 1 ? TOKEN : TOKEN + "-renewed";
        }
    }

    private static final class FakeGateway implements QqBotWebSocketTransport {
        private final List<FakeConnection> connections = new ArrayList<>();
        private final List<URI> urls = new ArrayList<>();
        private boolean rejectConnections;

        @Override
        public Connection connect(URI uri, Listener listener) throws IOException {
            assertTrue(connections.isEmpty() || last().closed, "只能有一条连接");
            urls.add(uri);
            if (rejectConnections) {
                throw new IOException("模拟网络暂时断开");
            }
            var connection = new FakeConnection(listener);
            connections.add(connection);
            return connection;
        }

        FakeConnection last() {
            assertFalse(connections.isEmpty(), "应已连接假网关");
            return connections.get(connections.size() - 1);
        }
    }

    private static final class FakeConnection implements QqBotWebSocketTransport.Connection {
        private final QqBotWebSocketTransport.Listener listener;
        private final List<JSONObject> sent = new ArrayList<>();
        private boolean closed;

        FakeConnection(QqBotWebSocketTransport.Listener listener) {
            this.listener = listener;
        }

        @Override
        public CompletionStage<?> send(String text) {
            assertFalse(closed);
            sent.add(JSON.parseObject(text));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
            closed = true;
        }

        void remoteClose(int code) {
            listener.onClose(code);
        }

        void networkFailure() {
            listener.onFailure();
        }

        void receive(int op, Object data) {
            listener.onText(JSONObject.of("op", op, "d", data).toJSONString());
        }

        void hello(long interval) {
            listener.onText(JSONObject.of("op", 10, "d", JSONObject.of("heartbeat_interval", interval)).toJSONString());
        }

        void dispatch(String name, Object data, String id, long sequence) {
            listener.onText(JSONObject.of("op", 0, "t", name, "d", data, "id", id, "s", sequence).toJSONString());
        }

        List<JSONObject> frames(int op) {
            return sent.stream().filter(frame -> frame.getIntValue("op") == op).toList();
        }

        JSONObject onlyFrame(int op) {
            var frames = frames(op);
            assertEquals(1, frames.size(), "op " + op);
            return frames.get(0);
        }
    }
}
