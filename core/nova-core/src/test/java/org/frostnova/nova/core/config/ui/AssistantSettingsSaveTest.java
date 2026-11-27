package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpServer;
import org.frostnova.nova.core.alert.LoopbackPort;
import org.frostnova.nova.core.assistant.AssistantClient;
import org.frostnova.nova.core.assistant.AssistantConfiguration;
import org.frostnova.nova.core.assistant.AssistantController;
import org.frostnova.nova.core.assistant.AssistantException;
import org.frostnova.nova.core.assistant.AssistantKeyStore;
import org.frostnova.nova.core.assistant.AssistantProperties;
import org.frostnova.nova.core.assistant.AssistantUsageStore;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.service.StreamerNames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class AssistantSettingsSaveTest {
    private static final String PREFIX = "novabot.assistant.";
    private static final String FIXTURE_KEY = "fixture-model-key";
    private static final String PLATFORM = "bilibili";
    private static final long STREAMER = 123L;
    private static final AssistantClient.Request QUESTION = new AssistantClient.Request(
            List.of(JSONObject.of("role", "user", "content", "测试问题")), List.of(), "none", 0);
    private static final ConfigUiAccount ADMIN = ConfigUiAccount.local(Instant.EPOCH);
    @TempDir Path directory;
    private AnnotationConfigApplicationContext context;
    private HttpServer provider;
    private String address;
    private Path config;
    private MockMvc mvc;
    private AssistantClient client;
    private ConfigUiController settings;
    private Transport transport = Transport.MOCK;
    private Consumer<String> beforeReply = ignored -> { };
    private final LinkedBlockingQueue<ProviderRequest> received = new LinkedBlockingQueue<>();

    @AfterEach void close() {
        if (provider != null) provider.stop(0);
        if (context != null) context.close();
    }

    @ParameterizedTest
    @EnumSource(Transport.class)
    @DisplayName("修正接口地址后保存密钥，防止试连仍向旧地址发送并被拒绝")
    void keyProbeUsesSavedAddressWithoutRestart(Transport transport) throws Exception {
        startProvider(transport);
        start(address + "/obsolete");

        JSONObject saved = save(Map.of(PREFIX + "base-url", address + "/v1"), 200);
        var keyResult = postJson("/config/api/assistant/key", JSONObject.of("key", FIXTURE_KEY));
        ProviderRequest probe = nextRequest();

        assertAll(
                () -> assertEquals("/v1/chat/completions", probe.path()),
                () -> assertEquals(200, keyResult.status(), keyResult.body()::toJSONString),
                () -> assertTrue(client.status().keySet(), "通过新地址验证的密钥应已保存"),
                () -> assertApplied(saved));
    }

    @ParameterizedTest
    @EnumSource(Transport.class)
    @DisplayName("换模型和接口格式后提问，防止新问题仍走旧模型或旧接口")
    void nextQuestionUsesSavedModelAndFormat(Transport transport) throws Exception {
        startProvider(transport);
        start(address + "/v1");
        connect();

        JSONObject saved = save(Map.of(PREFIX + "model", "fixture-new-model", PREFIX + "api-format", "responses"), 200);
        ask();
        ProviderRequest answer = nextRequest();

        assertAll(
                () -> assertEquals("/v1/responses", answer.path()),
                () -> assertEquals("fixture-new-model", answer.body().getString("model")),
                () -> assertApplied(saved));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            base-url | incomplete/address | 接口地址
            model | '' | 模型名称
            api-format | unknown | 接口格式
            enabled | maybe | 开关
            adaptive-thinking | maybe | 开关
            thinking | unknown | 思考强度
            hard-thinking | off | 思考强度
            max-concurrent-questions | 0 | 1 至 8
            max-concurrent-questions | 9 | 1 至 8
            hard-timeout-seconds | 0 | 难题限时
            answer-limit | 0 | 上限
            max-tool-calls | 0 | 上限
            question-input-limit | 0 | 上限
            price-input-yuan-per-million | -1 | 单价
            price-cached-input-yuan-per-million | -1 | 单价
            price-output-yuan-per-million | -1 | 单价
            weekly-budget-yuan | 0 | 大于 0
            streamer-daily-budget-yuan | -1 | 不能小于 0
            streamer-weekly-budget-yuan | -1 | 不能小于 0
            """)
    @DisplayName("无效值与有效地址同批保存，防止配置文件和运行设置只改一半")
    void invalidValueRejectsWholeSave(String key, String value, String expectedMessage) throws Exception {
        String initialAddress = "http://127.0.0.1:1/old";
        start(initialAddress);
        String before = Files.readString(config);
        String otherKey = key.equals("base-url") ? "model" : "base-url";
        String otherValue = key.equals("base-url") ? "fixture-new-model" : "http://127.0.0.1:1/v1";

        JSONObject result = save(Map.of(PREFIX + otherKey, otherValue, PREFIX + key, value), 400);

        assertFalse(result.getBooleanValue("success"));
        assertTrue(result.getString("message").contains(expectedMessage), result::toJSONString);
        assertEquals(before, Files.readString(config));
        assertEquals(initialAddress, client.status().baseUrl());
        assertEquals("fixture-old-model", client.status().model());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 65536})
    @DisplayName("拒绝越界的接口端口，防止保存显示已生效、之后每一问都连不上")
    void checkPortOutsideTcpRangeRejectsWholeSave(int port) throws Exception {
        String initialAddress = "http://127.0.0.1:1/old";
        start(initialAddress);
        String before = Files.readString(config);

        var result = postJson("/config/api/values", JSONObject.of(
                PREFIX + "base-url", "http://127.0.0.1:" + port + "/v1",
                PREFIX + "model", "fixture-new-model"));
        String message = result.body().getString("message");

        assertAll(
                () -> assertEquals(400, result.status(), result.body()::toJSONString),
                () -> assertFalse(result.body().getBooleanValue("success")),
                () -> assertTrue(message.contains("接口地址") && message.contains("端口"), result.body()::toJSONString),
                () -> assertEquals(before, Files.readString(config)),
                () -> assertEquals(initialAddress, client.status().baseUrl()),
                () -> assertEquals("fixture-old-model", client.status().model()));
    }

    @ParameterizedTest
    @CsvSource({
            "weekly-budget-yuan, weekly_budget",
            "streamer-daily-budget-yuan, streamer_daily_budget",
            "streamer-weekly-budget-yuan, streamer_weekly_budget"
    })
    @DisplayName("降低金额上限后立即拦下超额的新问题，防止重启前继续产生费用")
    void nextQuestionUsesSavedBudget(String key, String reason) throws Exception {
        startProvider(Transport.MOCK);
        start(address + "/v1");
        connect();
        ask();
        nextRequest();

        JSONObject saved = save(Map.of(PREFIX + key, "0.10"), 200);
        assertAll(
                () -> assertEquals(reason, assertThrows(AssistantException.class, this::ask).reason()),
                () -> assertApplied(saved));
        assertTrue(received.isEmpty(), "额度耗尽的问答不应发送给模型服务");
    }

    @Test
    @DisplayName("回答进行中保存配置，防止同一问的多轮请求和账单混用两份设置")
    void inFlightQuestionKeepsItsModelFormatAndPrices() throws Exception {
        startProvider(Transport.MOCK);
        start(address + "/v1");
        connect();
        var answering = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        beforeReply = path -> {
            if (!"/v1/chat/completions".equals(path)) return;
            answering.countDown();
            try {
                if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("应在保存新设置后放行旧请求");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError(error);
            }
        };
        var executor = Executors.newSingleThreadExecutor();
        try (var oldQuestion = client.beginQuestion(PLATFORM, STREAMER)) {
            var firstRound = executor.submit(() -> oldQuestion.complete(QUESTION));
            assertTrue(answering.await(10, TimeUnit.SECONDS));
            assertEquals("/v1/chat/completions", nextRequest().path());
            assertApplied(save(Map.of(PREFIX + "model", "fixture-new-model", PREFIX + "api-format", "responses",
                    PREFIX + "price-input-yuan-per-million", "1", PREFIX + "answer-limit", "360"), 200));
            release.countDown();
            assertEquals(0, new BigDecimal("0.4400086").compareTo(firstRound.get(10, TimeUnit.SECONDS).costYuan()));
            assertEquals(0, new BigDecimal("0.4400086").compareTo(oldQuestion.complete(QUESTION).costYuan()));
            var followUp = nextRequest();
            assertEquals("/v1/chat/completions", followUp.path());
            assertEquals("fixture-old-model", followUp.body().getString("model"));
            assertEquals(800, followUp.body().getIntValue("max_tokens"));

            assertEquals(0, new BigDecimal("0.2000086").compareTo(ask().costYuan()));
            var updated = nextRequest();
            assertEquals("/v1/responses", updated.path());
            assertEquals("fixture-new-model", updated.body().getString("model"));
            assertEquals(4360, updated.body().getIntValue("max_output_tokens"));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    @DisplayName("换地址并保存新密钥时，防止旧问题后续轮次向原来的接口发送新密钥")
    void rotatingKeyStopsFurtherCallsFromAnOldQuestion() throws Exception {
        startProvider(Transport.MOCK);
        start(address + "/v1");
        connect();
        try (var oldQuestion = client.beginQuestion(PLATFORM, STREAMER)) {
            oldQuestion.complete(QUESTION);
            nextRequest();
            assertApplied(save(Map.of(PREFIX + "base-url", address + "/next"), 200));
            var key = postJson("/config/api/assistant/key", JSONObject.of("key", "fixture-replacement-key"));
            assertEquals(200, key.status(), key.body()::toJSONString);
            assertEquals("/next/chat/completions", nextRequest().path());

            assertTrue(assertThrows(AssistantException.class, () -> oldQuestion.complete(QUESTION)).getMessage().contains("密钥"));
            assertTrue(received.isEmpty(), "旧问题不应发送后续请求");
            ask();
            assertEquals("/next/chat/completions", nextRequest().path());
        }
    }

    @Test
    @DisplayName("整组保存后核对页面和启动读数，防止遗漏即时生效项或运行值与落盘值不同")
    void allAssistantSettingsApplyAndMatchTheSavedFile() throws Exception {
        start("http://127.0.0.1:1/old");
        Map<String, String> changes = Map.ofEntries(
                Map.entry("enabled", "false"), Map.entry("base-url", "http://127.0.0.1:1/v1"),
                Map.entry("model", "fixture-new-model"), Map.entry("api-format", "responses"),
                Map.entry("difficulty-model", "fixture-classifier"), Map.entry("thinking", "low"),
                Map.entry("hard-thinking", "max"), Map.entry("adaptive-thinking", "true"),
                Map.entry("hard-timeout-seconds", "125"), Map.entry("max-concurrent-questions", "3"), Map.entry("answer-limit", "600"),
                Map.entry("max-tool-calls", "4"), Map.entry("question-input-limit", "24000"),
                Map.entry("price-input-yuan-per-million", "3"), Map.entry("price-cached-input-yuan-per-million", "0.4"),
                Map.entry("price-output-yuan-per-million", "12"), Map.entry("weekly-budget-yuan", "11"),
                Map.entry("streamer-daily-budget-yuan", "2"), Map.entry("streamer-weekly-budget-yuan", "6"),
                Map.entry("excluded-uids", "11\n33"));
        var keyed = changes.entrySet().stream().collect(Collectors.toMap(entry -> PREFIX + entry.getKey(), Map.Entry::getValue));
        var saved = save(keyed, 200);
        assertApplied(saved);
        assertEquals(keyed.size(), saved.getIntValue("changed"));
        var assistant = context.getBean(AssistantProperties.class);
        assertEquals(readSavedProperties().snapshot(), assistant.snapshot());
        assertEquals(3, readSavedProperties().getMaxConcurrentQuestions());
        assertEquals(3, assistant.getMaxConcurrentQuestions());
        assertFalse(client.status().enabled());

        var group = settings.schema().getList("groups", JSONObject.class).stream()
                .filter(g -> "assistant".equals(g.getString("group"))).findFirst().orElseThrow();
        Set<String> immediate = group.getList("fields", JSONObject.class).stream()
                .filter(field -> "IMMEDIATE".equals(field.getString("effect")))
                .map(field -> field.getString("name")).collect(Collectors.toSet());
        assertEquals(keyed.keySet(), immediate);
        assertFalse(group.getBooleanValue("allRestart"));

        assertApplied(save(Map.of(PREFIX + "weekly-budget-yuan", "", PREFIX + "difficulty-model", ""), 200));
        assertEquals(readSavedProperties().snapshot(), assistant.snapshot());
        assertEquals(new AssistantProperties().getWeeklyBudgetYuan(), assistant.getWeeklyBudgetYuan());
    }

    private AssistantProperties readSavedProperties() throws IOException {
        var source = new MapConfigurationPropertySource(new ConfigurationFileService(config).readAsLoaded());
        return new Binder(source).bind("novabot.assistant", AssistantProperties.class).get();
    }

    private void start(String baseUrl) throws IOException {
        config = directory.resolve("application.yml");
        Files.writeString(config, """
                novabot:
                  assistant:
                    enabled: true
                    base-url: %s
                    model: fixture-old-model
                """.formatted(baseUrl));
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "assistant", new ConfigurationFileService(config).readAsLoaded()));
        context.registerBean(NovaCoreProperties.class, () -> {
            var properties = new NovaCoreProperties();
            properties.getLive().setLiveDataPath(directory.resolve("data.json").toString());
            return properties;
        });
        context.registerBean("testAssistantKeys", AssistantKeyStore.class, () -> new AssistantKeyStore(directory),
                definition -> definition.setPrimary(true));
        context.registerBean("testAssistantUsage", AssistantUsageStore.class,
                () -> new AssistantUsageStore(directory, Clock.systemUTC()), definition -> definition.setPrimary(true));
        context.register(AssistantConfiguration.class);
        context.refresh();
        if (transport == Transport.HTTP) {
            client = context.getBean(AssistantClient.class);
        } else {
            var template = new RestTemplate();
            MockRestServiceServer.bindTo(template).build().expect(ExpectedCount.manyTimes(), request -> { })
                    .andRespond(request -> {
                        var mock = (MockClientHttpRequest) request;
                        var reply = reply(request.getURI().getPath(), mock.getBodyAsBytes());
                        return withStatus(HttpStatusCode.valueOf(reply.status())).contentType(MediaType.APPLICATION_JSON)
                                .body(reply.body()).createResponse(request);
                    });
            client = new AssistantClient(context.getBean(AssistantProperties.class),
                    context.getBean(AssistantKeyStore.class), context.getBean(AssistantUsageStore.class), template);
        }
        settings = ExcludedViewersFileTest.controller(context, config);
        mvc = MockMvcBuilders.standaloneSetup(settings,
                new AssistantController(client, mock(AbstractDataSource.class), StreamerNames.none())).build();
    }

    private void startProvider(Transport transport) throws IOException {
        this.transport = transport;
        address = "http://127.0.0.1:1";
        if (transport == Transport.MOCK) return;
        LoopbackPort.assumeAllowed();
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        address = "http://127.0.0.1:" + provider.getAddress().getPort();
        provider.createContext("/", exchange -> {
            try (exchange) {
                var reply = reply(exchange.getRequestURI().getPath(), exchange.getRequestBody().readAllBytes());
                byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(reply.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        provider.start();
    }

    private ProviderReply reply(String path, byte[] request) {
        received.add(new ProviderRequest(path, JSON.parseObject(request)));
        beforeReply.accept(path);
        return switch (path) {
            case "/v1/chat/completions", "/next/chat/completions" -> new ProviderReply(200, """
                    {"choices":[{"message":{"content":"好"}}],
                     "usage":{"prompt_tokens":200000,"completion_tokens":1}}
                    """);
            case "/v1/responses" -> new ProviderReply(200, """
                    {"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"好"}]}],
                     "usage":{"input_tokens":200000,"output_tokens":1}}
                    """);
            default -> new ProviderReply(401, "{}");
        };
    }

    private void connect() throws Exception {
        var saved = postJson("/config/api/assistant/key", JSONObject.of("key", FIXTURE_KEY));
        assertEquals(200, saved.status(), saved.body()::toJSONString);
        nextRequest();
        client.acknowledge(ADMIN.id());
    }

    private AssistantClient.Response ask() {
        try (var question = client.beginQuestion(PLATFORM, STREAMER)) {
            return question.complete(QUESTION);
        }
    }

    private ProviderRequest nextRequest() throws InterruptedException {
        var result = received.poll(10, TimeUnit.SECONDS);
        assertNotNull(result, "本机模型服务应收到请求");
        return result;
    }

    private JSONObject save(Map<String, String> changes, int status) throws Exception {
        var result = postJson("/config/api/values", new JSONObject(changes));
        assertEquals(status, result.status(), result.body()::toJSONString);
        return result.body();
    }

    private static void assertApplied(JSONObject result) {
        assertTrue(result.getBooleanValue("success"), result::toJSONString);
        assertEquals(List.of(), result.getList("restartRequired", String.class));
        assertEquals(List.of(), result.getList("restartPending", String.class));
        assertTrue(result.getString("message").contains("已生效"), result::toJSONString);
    }

    private HttpResult postJson(String route, JSONObject body) throws Exception {
        var response = mvc.perform(post(route).requestAttr(ConfigUiAccess.ACCOUNT, ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content(body.toJSONString())).andReturn().getResponse();
        return new HttpResult(response.getStatus(), JSON.parseObject(response.getContentAsString(StandardCharsets.UTF_8)));
    }

    private record ProviderRequest(String path, JSONObject body) { }
    private record ProviderReply(int status, String body) { }
    private record HttpResult(int status, JSONObject body) { }
    private enum Transport { HTTP, MOCK }
}
