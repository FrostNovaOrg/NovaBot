package org.frostnova.nova.adapter.qqbot;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.adapter.onebot.config.OneBotAdapterPluginProperties;
import org.frostnova.nova.adapter.onebot.controller.OneBotController;
import org.frostnova.nova.adapter.onebot.converter.OneBotMessageConverter;
import org.frostnova.nova.adapter.onebot.health.OneBotConnectionState;
import org.frostnova.nova.adapter.onebot.health.OneBotConnectionTester;
import org.frostnova.nova.adapter.onebot.http.OneBotHttpAdapter;
import org.frostnova.nova.adapter.onebot.http.OneBotHttpAdapterProxy;
import org.frostnova.nova.adapter.onebot.security.PushApiTokenStore;
import org.frostnova.nova.adapter.onebot.service.OneBotConnectionManager;
import org.frostnova.nova.adapter.onebot.service.OneBotHttpService;
import org.frostnova.nova.adapter.onebot.service.OneBotWebsocketService;
import org.frostnova.nova.core.account.BotConnectionTester;
import org.frostnova.nova.core.assistant.AssistantClient;
import org.frostnova.nova.core.assistant.AssistantController;
import org.frostnova.nova.core.assistant.AssistantKeyStore;
import org.frostnova.nova.core.assistant.AssistantProperties;
import org.frostnova.nova.core.assistant.AssistantUsageStore;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.RestTemplateConfig;
import org.frostnova.nova.core.config.ui.ConfigUiAccess;
import org.frostnova.nova.core.config.ui.ConfigUiController;
import org.frostnova.nova.core.config.ui.ConfigurationFileService;
import org.frostnova.nova.core.config.ui.ConfigurationMetadataService;
import org.frostnova.nova.core.config.ui.ConsoleCredentialLogAdvice;
import org.frostnova.nova.core.config.ui.ConsoleCredentialLogFilter;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.properties.LogProperties;
import org.frostnova.nova.core.secret.SecretLogMask;
import org.frostnova.nova.core.service.NovaSenderService;
import org.frostnova.nova.core.util.HttpUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Credentials taken in by the console and then used to connect elsewhere must not reach any log,
 * on whichever thread the client logs, with Spring and NovaBot logging at TRACE.
 * <p>
 * The real clients are pointed at an address nothing listens on: the WebSocket handshake headers and the
 * HTTP client's request diagnostics are written before the connection is attempted, so no port is bound.
 */
class OutboundCredentialLoggingTest {
    /** Nothing listens on port 1 of the loopback address; connecting fails without binding anything. */
    private static final String UNREACHABLE_HOST = "127.0.0.1";
    private static final int UNREACHABLE_PORT = 1;
    private static final Duration PATIENCE = Duration.ofSeconds(30);
    private static final String HANDSHAKE_LOGGER = "org.springframework.web.socket.client.standard.StandardWebSocketClient";
    private static final String REST_LOGGER = "org.springframework.web.client.RestTemplate";

    private static final String SOCKET_TOKEN = "fixture-napcat-socket-token-q7";
    private static final String HTTP_TOKEN = "fixture-napcat-http-token-k3";
    private static final String PROBE_TOKEN = "fixture-napcat-probe-token-m5";
    private static final String APP_ID = "12349876";
    private static final String APP_SECRET = "fixture-official-app-secret-x9";
    private static final String ACCESS_TOKEN = "fixture-official-access-token-b2";
    private static final String MODEL_KEY = "fixture-model-key-d8";

    @TempDir
    Path directory;

    /**
     * Keep every outbound use of a console-held credential that a test can drive through its real client
     * in this list. Each use waits for its client to have run before the logs are checked.
     */
    private List<OutboundUse> outboundUses(Fixture fixture) {
        return List.of(
                new OutboundUse("NapCat connection save: WebSocket handshake with reconnects, HTTP check on a pool thread",
                        List.of(SOCKET_TOKEN, HTTP_TOKEN), fixture::saveNapCatConnection),
                new OutboundUse("NapCat connection test: HTTP request header",
                        List.of(PROBE_TOKEN), fixture::testNapCatConnection),
                new OutboundUse("Official bot: AppSecret in the token exchange body",
                        List.of(APP_SECRET), fixture::exchangeOfficialToken),
                new OutboundUse("Official bot: access token in the API request header",
                        List.of(ACCESS_TOKEN), fixture::callOfficialApi),
                new OutboundUse("Assistant: model key in the model request header",
                        List.of(MODEL_KEY), fixture::validateModelKey));
    }

    @Test
    void credentialsUsedForOutboundConnectionsStayOutOfTraceLogs() throws Exception {
        try (var logging = new AnnotationConfigApplicationContext(ConsoleCredentialLogFilter.class);
             var logs = new CapturedLogs(Level.TRACE);
             var fixture = new Fixture(directory, logs)) {
            var uses = outboundUses(fixture);
            var secrets = uses.stream().flatMap(use -> use.secrets().stream()).toList();
            var leaks = new LinkedHashSet<String>();
            for (var use : uses) {
                logs.clear();
                use.action().run();
                for (var event : logs.events()) {
                    String text = rendered(event);
                    secrets.stream().filter(text::contains).forEach(secret -> leaks.add(use.name() + ": "
                            + event.getLoggerName() + " on " + event.getThreadName() + " logged " + secret));
                }
            }
            assertTrue(leaks.isEmpty(), () -> "Credentials reached logs:\n" + String.join("\n", leaks));
        }
    }

    private static String rendered(ILoggingEvent event) {
        var throwable = event.getThrowableProxy();
        return event.getFormattedMessage() + (throwable == null ? "" : ThrowableProxyUtil.asString(throwable));
    }

    private record OutboundUse(String name, List<String> secrets, Action action) {}

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    private static final class Fixture implements AutoCloseable {
        private final Path directory;
        private final CapturedLogs logs;
        private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        private final OneBotAdapterPluginProperties oneBot = new OneBotAdapterPluginProperties();
        private final OneBotHttpService httpService;
        private final OneBotWebsocketService websocketService;
        private final MockMvc console;
        private final AssistantClient assistant;

        private Fixture(Path directory, CapturedLogs logs) throws IOException {
            this.directory = directory;
            this.logs = logs;
            executor.setCorePoolSize(2);
            executor.setMaxPoolSize(4);
            executor.setThreadNamePrefix("fixture-pool-");
            executor.setDaemon(true);
            executor.initialize();
            scheduler.setPoolSize(1);
            scheduler.setDaemon(true);
            scheduler.initialize();

            // NapCat adapter as the application wires it: HttpUtil over the production RestTemplate.
            oneBot.getDetect().setEnableHttpDetect(false);
            oneBot.getDetect().setEnableWebsocketDetect(false);
            var state = new OneBotConnectionState();
            var http = new HttpUtil(executor, RestTemplateConfig.buildTemplate(Duration.ofSeconds(5), Duration.ofSeconds(5)),
                    new LogProperties());
            var adapter = (OneBotHttpAdapter) Proxy.newProxyInstance(OneBotHttpAdapter.class.getClassLoader(),
                    new Class<?>[]{OneBotHttpAdapter.class}, new OneBotHttpAdapterProxy(http, state));
            httpService = new OneBotHttpService(scheduler, executor, oneBot, adapter, new OneBotMessageConverter(), state);
            websocketService = new OneBotWebsocketService(scheduler, executor, oneBot, httpService, state,
                    mock(ApplicationEventPublisher.class));
            WebServer server = mock(WebServer.class);
            when(server.getPort()).thenReturn(8080);
            var web = mock(WebServerApplicationContext.class);
            when(web.getWebServer()).thenReturn(server);
            var controller = new OneBotController(web, mock(RequestMappingHandlerMapping.class), oneBot,
                    new NovaSenderService(new NovaCoreProperties()), httpService, new PushApiTokenStore());
            @SuppressWarnings("unchecked")
            ObjectProvider<org.frostnova.nova.adapter.onebot.service.OneBotTargetDirectory> targets = mock(ObjectProvider.class);
            var manager = new OneBotConnectionManager(oneBot, controller, websocketService, httpService, state, targets);
            var tester = new OneBotConnectionTester(adapter, oneBot, manager);

            var metadata = mock(ConfigurationMetadataService.class);
            when(metadata.getKnownTypes()).thenReturn(Map.of());
            var config = mock(ConfigUiController.class, CALLS_REAL_METHODS);
            @SuppressWarnings("unchecked")
            ObjectProvider<BotConnectionTester> testers = mock(ObjectProvider.class);
            when(testers.orderedStream()).thenAnswer(invocation -> Stream.of(tester));
            ReflectionTestUtils.setField(config, "metadataService", metadata);
            ReflectionTestUtils.setField(config, "connectionTesters", testers);
            ReflectionTestUtils.setField(config, "botConnections", mock(ObjectProvider.class));
            ReflectionTestUtils.setField(config, "fileService", mock(ConfigurationFileService.class));

            var properties = new AssistantProperties();
            properties.setBaseUrl("http://" + UNREACHABLE_HOST + ":" + UNREACHABLE_PORT + "/v1");
            assistant = new AssistantClient(properties, new AssistantKeyStore(directory.resolve("assistant")),
                    new AssistantUsageStore(directory.resolve("assistant"), Clock.systemUTC()),
                    RestTemplateConfig.buildTemplate(Duration.ofSeconds(5), Duration.ofSeconds(5)));

            console = MockMvcBuilders.standaloneSetup(config, new AssistantController(assistant, null, null))
                    .setControllerAdvice(new ConsoleCredentialLogAdvice(metadata)).build();
        }

        /** Saves the connection the way the setup page does; the adapter then connects on its own threads. */
        void saveNapCatConnection() throws Exception {
            var response = perform("/config/api/setup/bot", JSONObject.of("address", UNREACHABLE_HOST,
                    "httpPort", UNREACHABLE_PORT, "websocketPort", UNREACHABLE_PORT,
                    "httpToken", HTTP_TOKEN, "websocketToken", SOCKET_TOKEN));
            assertTrue(response.getBooleanValue("live"), response::toJSONString);
            // The first attempt and at least one reconnect write the handshake headers on the client's threads.
            await("two WebSocket handshakes logged at TRACE", () -> logs.events().stream()
                    .filter(event -> event.getLoggerName().equals(HANDSHAKE_LOGGER)
                            && event.getFormattedMessage().startsWith("Handshake request headers")
                            && event.getFormattedMessage().contains("Authorization")
                            && !event.getThreadName().equals(Thread.currentThread().getName()))
                    .count() >= 2);
            // The scheduled HTTP check runs on a pool thread, outside any console request.
            var sender = oneBot.getSenders().get(0);
            CompletableFuture.runAsync(() -> httpService.check(sender), executor).get();
            assertLogged("HTTP request through RestTemplate", event -> event.getLoggerName().equals(REST_LOGGER));
        }

        void testNapCatConnection() throws Exception {
            var response = perform("/config/api/setup/test-bot", JSONObject.of("address", UNREACHABLE_HOST,
                    "httpPort", UNREACHABLE_PORT, "httpToken", PROBE_TOKEN));
            assertEquals(Boolean.FALSE, response.getBoolean("success"), response::toJSONString);
            assertLogged("HTTP request through RestTemplate", event -> event.getLoggerName().equals(REST_LOGGER));
        }

        /** The token is exchanged on a scheduler thread once credentials are saved. */
        void exchangeOfficialToken() throws Exception {
            var transport = new UnreachableQqBot(null);
            var tokens = new QqBotTokenService(new QqBotSettings(directory.resolve("official-exchange")), transport);
            tokens.save(APP_ID, APP_SECRET);
            var failure = CompletableFuture.supplyAsync(() -> {
                try {
                    tokens.token();
                    return null;
                } catch (RuntimeException e) {
                    return e;
                }
            }, executor).get();
            assertInstanceOf(QqBotApiException.class, failure);
            transport.assertReachedRealClient("/app/getAppAccessToken", APP_SECRET);
            // The stored secret is registered, so a client that did log the body would be masked too.
            assertEquals(SecretLogMask.MASK, SecretLogMask.mask(APP_SECRET));
        }

        /** The platform's token reply is simulated; the API request then goes out through the real client. */
        void callOfficialApi() throws Exception {
            var transport = new UnreachableQqBot(JSONObject.of("access_token", ACCESS_TOKEN, "expires_in", 7200));
            var tokens = new QqBotTokenService(new QqBotSettings(directory.resolve("official-api")), transport);
            tokens.save(APP_ID, "fixture-official-api-secret-z4");
            var api = new QqBotApiClient(transport, tokens);
            var failure = CompletableFuture.supplyAsync(() -> {
                try {
                    api.request("POST", "/v2/groups/fixture-group/messages", JSONObject.of("content", "hello"));
                    return null;
                } catch (RuntimeException e) {
                    return e;
                }
            }, executor).get();
            assertNotNull(failure);
            transport.assertReachedRealClient("/v2/groups/fixture-group/messages", ACCESS_TOKEN);
            // Not submitted by anyone: registered when exchanged, for the header and the gateway's identify message.
            assertEquals(SecretLogMask.MASK, SecretLogMask.mask(ACCESS_TOKEN));
        }

        void validateModelKey() throws Exception {
            var response = console.perform(post("/config/api/assistant/key")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JSONObject.of("key", MODEL_KEY).toJSONString())
                            .requestAttr(ConfigUiAccess.ACCOUNT, ConfigUiAccount.local(Instant.EPOCH))
                            .requestAttr(ConfigUiAccess.FRESH_LOCAL, true))
                    .andReturn().getResponse();
            assertEquals(400, response.getStatus(), response.getContentAsString());
            assertLogged("model request through RestTemplate", event -> event.getLoggerName().equals(REST_LOGGER)
                    && event.getFormattedMessage().contains(UNREACHABLE_HOST + ":" + UNREACHABLE_PORT));
            assertEquals(SecretLogMask.MASK, SecretLogMask.mask(MODEL_KEY));
        }

        private JSONObject perform(String path, JSONObject body) throws Exception {
            var response = console.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                            .content(body.toJSONString())
                            .requestAttr(ConfigUiAccess.ACCOUNT, ConfigUiAccount.local(Instant.EPOCH))
                            .requestAttr(ConfigUiAccess.FRESH_LOCAL, true))
                    .andReturn().getResponse();
            assertEquals(200, response.getStatus(), path);
            return JSONObject.parseObject(response.getContentAsString());
        }

        private void assertLogged(String what, Predicate<ILoggingEvent> evidence) {
            assertTrue(logs.events().stream().anyMatch(evidence), what + " was not logged");
        }

        private void await(String what, BooleanSupplier condition) throws InterruptedException {
            Instant deadline = Instant.now().plus(PATIENCE);
            while (!condition.getAsBoolean()) {
                assertTrue(Instant.now().isBefore(deadline), () -> what + ": not within " + PATIENCE.toSeconds()
                        + " s; logged by " + logs.events().stream().map(event -> event.getLoggerName() + "@"
                        + event.getThreadName()).distinct().toList());
                Thread.sleep(50);
            }
        }

        @Override
        public void close() {
            oneBot.getSenders().forEach(sender -> websocketService.stop(sender.getName()));
            executor.shutdown();
            scheduler.shutdown();
        }
    }

    /**
     * The official bot's real JDK transport, sent to an unreachable address instead of the platform.
     * When a token reply is given, the token exchange is answered with it instead of going out.
     */
    private static final class UnreachableQqBot implements QqBotHttpTransport {
        private final JdkQqBotHttpTransport real = new JdkQqBotHttpTransport();
        private final JSONObject tokenReply;
        private final AtomicReference<String> sent = new AtomicReference<>();

        private UnreachableQqBot(JSONObject tokenReply) {
            this.tokenReply = tokenReply;
        }

        @Override
        public Response send(String method, URI uri, Map<String, String> headers, String body)
                throws IOException, InterruptedException {
            if (tokenReply != null && uri.getPath().equals("/app/getAppAccessToken")) {
                return new Response(200, tokenReply.toJSONString());
            }
            URI unreachable = URI.create("http://" + UNREACHABLE_HOST + ":" + UNREACHABLE_PORT + uri.getRawPath());
            sent.set(uri.getPath() + " " + headers + " " + body);
            try {
                return real.send(method, unreachable, headers, body);
            } catch (IOException e) {
                sent.set(sent.get() + " failed");
                throw e;
            }
        }

        @Override
        public Response send(HttpRequest request) throws IOException, InterruptedException {
            throw new IOException("not used here");
        }

        private void assertReachedRealClient(String path, String secret) {
            String request = sent.get();
            assertNotNull(request, "the real client was not called");
            assertTrue(request.startsWith(path) && request.contains(secret) && request.endsWith(" failed"),
                    "the real client did not carry the credential to the unreachable address");
        }
    }

    /** Attaches one appender to the root and the noisy categories, every one at the given level. */
    private static final class CapturedLogs implements AutoCloseable {
        private final Map<Logger, Level> previous = new LinkedHashMap<>();
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>() {
            @Override
            protected void append(ILoggingEvent event) {
                // Fixes the thread name and message now; read later they would describe the reading thread.
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        private final java.util.logging.Logger julRoot = java.util.logging.LogManager.getLogManager().getLogger("");
        private final java.util.logging.Level julLevel = julRoot.getLevel();
        private final boolean bridged = SLF4JBridgeHandler.isInstalled();

        private CapturedLogs(Level level) {
            appender.start();
            for (String name : List.of(Logger.ROOT_LOGGER_NAME, "org.springframework", "org.springframework.web",
                    "org.frostnova.nova", "org.apache", "jdk", "NetworkLogger", "EventLogger")) {
                Logger logger = (Logger) LoggerFactory.getLogger(name);
                previous.put(logger, logger.getLevel());
                logger.setLevel(level);
            }
            ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(appender);
            // JDK and Tomcat clients log through java.util.logging; the application routes it to the same logs.
            if (!bridged) {
                SLF4JBridgeHandler.install();
            }
            julRoot.setLevel(java.util.logging.Level.ALL);
        }

        List<ILoggingEvent> events() {
            synchronized (appender) {
                return new ArrayList<>(appender.list);
            }
        }

        void clear() {
            synchronized (appender) {
                appender.list.clear();
            }
        }

        @Override
        public void close() {
            julRoot.setLevel(julLevel);
            if (!bridged) {
                SLF4JBridgeHandler.uninstall();
            }
            ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(appender);
            previous.forEach(Logger::setLevel);
            appender.stop();
        }
    }
}
