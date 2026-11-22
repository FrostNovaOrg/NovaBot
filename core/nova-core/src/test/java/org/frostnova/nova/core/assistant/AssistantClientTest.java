package org.frostnova.nova.core.assistant;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AssistantClientTest {
    @TempDir Path directory;
    private static final String SECRET = "test-secret-1234567890";
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-24T08:00:00Z"), ZoneOffset.UTC);
    private AssistantProperties properties;
    private AssistantKeyStore keys;
    private AssistantUsageStore usage;
    private AssistantClient client;
    private MockRestServiceServer server;
    private RestTemplate template;

    @BeforeEach void setup() {
        properties = new AssistantProperties();
        properties.setEnabled(true);
        keys = new AssistantKeyStore(directory);
        usage = new AssistantUsageStore(directory, clock);
        template = new RestTemplate();
        server = MockRestServiceServer.bindTo(template).build();
        client = new AssistantClient(properties, keys, usage, template);
        keys.save(SECRET);
        client.acknowledge("admin");
    }

    @Test void acknowledgementDescribesCurrentPayloadAndUsesConfiguredEndpoint() {
        properties.setBaseUrl("https://model.example/v1");
        var status = client.status();
        assertEquals("2026-11-22-v3", status.acknowledgementVersion());
        assertEquals("""
                开启后，NovaBot 会经你填的接口将提问和查到的直播数据发给所配置的模型服务商（https://model.example/v1）处理，适用它自己的协议和隐私政策。
                查到的资料可能包括汇总数字和金额、场次时间、标题和分区、PK 结果、贡献者代号和占比。只读查询还可能按观众代号发礼物和打赏的名称或种类、个数与金额（含盲盒和上舰），送礼、打赏、关注、发弹幕的时刻，弹幕字数、名单到场记录和第一次留档的统计日。QQ 里的查数按通道的金额可见设置处理。控制台查弹幕时可能带少量截断的原话（含醒目留言），QQ 不会从归档带原话。
                追问会带上最近的问答，QQ 还可能带上之前查过哪些数据的说明。有效的助手备忘会连同记录日期和已填的截止日随该主播的提问发送。观众明细和备忘中能识别的观众昵称会换成代号。软件不附观众账号编号或提问人的 QQ 号，提问、备忘等文字里自己写入的个人信息仍可能发出。
                发不发、发给谁由你决定，也由你负责；调用费用由你和模型服务商结算。
                智能助手的回答可能出错，只供参考，数字以报告里的原始数据为准。
                如需在国内办理备案、登记等手续，由你自己办；回答旁的「由人工智能生成」提示请保留。""", status.acknowledgementText());
        properties.setBaseUrl("https://other.example/api");
        assertTrue(client.status().acknowledgementText().contains("（https://other.example/api）"));
    }

    @Test void currentAcknowledgementIsRequiredAndStateIsReadOnly() {
        var unconfirmed = new AssistantClient(properties, keys, new AssistantUsageStore(directory.resolve("new"), clock), template);
        assertEquals(List.of("not_acknowledged"), unconfirmed.state("member").reasons());
        assertEquals("not_acknowledged", assertThrows(AssistantException.class,
                () -> unconfirmed.beginQuestion("member")).reason());
        assertFalse(Files.exists(directory.resolve("new/assistant-usage.json")));
        server.verify();
    }

    @Test void unreadableUsageDoesNotReportUnknownAcknowledgement() throws Exception {
        Files.writeString(directory.resolve("assistant-usage.json"), "{}");
        var unreadable = new AssistantClient(properties, keys, new AssistantUsageStore(directory, clock), template);
        var state = unreadable.state("member");
        assertFalse(state.available());
        assertEquals(List.of("usage_unreadable"), state.reasons());
        assertEquals(0, state.questionsLeftToday());
        assertEquals("usage_unreadable", assertThrows(AssistantException.class,
                () -> unreadable.beginQuestion("member")).reason());
        assertEquals("{}", Files.readString(directory.resolve("assistant-usage.json")));
        server.verify();
    }

    @Test void dailyLimitPrecedesMonthlyBudgetWhenBothAreExhausted() {
        properties.setDailyQuestionsPerAccount(1);
        usage.startQuestion("member", properties);
        usage.addCost(properties.getMonthlyBudgetYuan());
        assertEquals("daily_limit", assertThrows(AssistantException.class, () -> client.beginQuestion("member")).reason());
        assertEquals(List.of("daily_limit", "monthly_budget"), client.state("member").reasons());
        assertEquals(0, client.state("member").questionsLeftToday());
        assertEquals(1, client.state("other").questionsLeftToday());
        server.verify();
    }

    private AssistantClient.Request request() {
        return new AssistantClient.Request(List.of(JSONObject.of("role", "user", "content", "你好")),
                List.of(), "auto", 0.2);
    }

    private void reply(String tokens) {
        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + SECRET))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"好\",\"tool_calls\":[]}}],\"usage\":"
                        + tokens + "}", MediaType.APPLICATION_JSON));
    }

    private void useResponses() {
        properties.setBaseUrl("https://model.example/v1///");
        new Binder(new MapConfigurationPropertySource(Map.of("novabot.assistant.api-format", "responses")))
                .bind("novabot.assistant", Bindable.ofInstance(properties));
    }

    private static final String RESPONSES_ANSWER = """
            {"status":"completed","output":[
              {"type":"reasoning","summary":[{"type":"summary_text","text":"不属于回答"}]},
              {"type":"message","content":[
                {"type":"output_text","text":"第一段"},
                {"type":"refusal","refusal":"不属于回答"},
                {"type":"output_text","text":"第二段"}]},
              {"type":"message","content":[{"type":"output_text","text":"第三段"}]}
            ],"usage":{"input_tokens":1000000,"input_tokens_details":{"cached_tokens":400000},
              "output_tokens":100000,"output_tokens_details":{"reasoning_tokens":80000}}}
            """;

    // 抓住使用旧地址或发送不受支持的字段时，接口拒绝整轮查数。
    @ParameterizedTest
    @CsvSource({"off, 4800", "low, 4800", "high, 16800", "max, 32800"})
    void responsesSendsDeveloperToolsAndEffortWithoutChatFields(String thinking, int limit) {
        useResponses();
        properties.setThinking(thinking);
        var tools = List.of(JSONObject.of("type", "function", "function", JSONObject.of("name", "lookup",
                "description", "查数", "parameters", JSONObject.of("type", "object"))));
        var messages = List.of(JSONObject.of("role", "system", "content", "规则"), request().messages().get(0),
                JSONObject.of("role", "assistant", "content", "之前的回答"));
        server.expect(requestTo("https://model.example/v1/responses"))
                .andRespond(outgoing -> {
                    var body = JSON.parseObject(((MockClientHttpRequest) outgoing).getBodyAsString());
                    if (body.containsKey("temperature") || body.containsKey("max_tokens"))
                        return withStatus(HttpStatus.BAD_REQUEST).createResponse(outgoing);
                    assertEquals(properties.getModel(), body.getString("model"));
                    assertEquals(List.of(JSONObject.of("role", "developer", "content", "规则"), messages.get(1), messages.get(2)),
                            body.getJSONArray("input"));
                    assertEquals(List.of(JSONObject.of("type", "function", "name", "lookup", "description", "查数",
                            "parameters", JSONObject.of("type", "object"))), body.getJSONArray("tools"));
                    assertEquals(JSONObject.of("type", "function", "name", "lookup"), body.getJSONObject("tool_choice"));
                    assertEquals(false, body.getBoolean("store"));
                    assertEquals(List.of("reasoning.encrypted_content"), body.getJSONArray("include"));
                    assertEquals(limit, body.getIntValue("max_output_tokens"));
                    assertFalse(body.containsKey("previous_response_id"));
                    if ("off".equals(thinking)) assertFalse(body.containsKey("reasoning"));
                    else assertEquals(JSONObject.of("effort", thinking), body.getJSONObject("reasoning"));
                    return withSuccess(RESPONSES_ANSWER, MediaType.APPLICATION_JSON).createResponse(outgoing);
                });
        try (var question = client.beginQuestion("member")) {
            var request = new AssistantClient.Request(messages, tools,
                    Map.of("type", "function", "function", Map.of("name", "lookup")), 0.3);
            assertEquals("第一段第二段第三段", question.complete(request).content());
        }
        server.verify();
    }

    // 抓住思考 token 重复计价，导致月度限额过早用尽。
    @Test void responsesBillsReasoningOnceAndLeavesMonthlyAllowance() {
        useResponses();
        properties.setMonthlyBudgetYuan(new BigDecimal("2.50"));
        server.expect(requestTo("https://model.example/v1/responses"))
                .andRespond(withSuccess(RESPONSES_ANSWER, MediaType.APPLICATION_JSON));
        try (var question = client.beginQuestion("member")) {
            var result = question.complete(request());
            assertEquals(new AssistantClient.Usage(400000, 600000, 100000), result.usage());
            assertEquals(0, new BigDecimal("2.20").compareTo(question.totalCostYuan()));
        }
        assertEquals(0, new BigDecimal("2.20").compareTo(new AssistantUsageStore(directory, clock).spentYuan()));
        assertTrue(client.state("member").available());
        server.verify();
    }

    // 抓住保存密钥或测试连接时使用旧接口或发错验证参数。
    @Test void responsesVerifiesAndSavesCandidateUsingConfiguredEndpoint() {
        useResponses();
        properties.setThinking("low");
        for (int i = 0; i < 2; i++) {
            server.expect(requestTo("https://model.example/v1/responses"))
                    .andExpect(method(org.springframework.http.HttpMethod.POST))
                    .andExpect(header("Authorization", "Bearer replacement-test-secret"))
                    .andExpect(jsonPath("$.input[0].content").value("回复好"))
                    .andExpect(jsonPath("$.max_output_tokens").value(256))
                    .andExpect(jsonPath("$.reasoning.effort").value("low"))
                    .andExpect(jsonPath("$.tool_choice").value("none"))
                    .andExpect(jsonPath("$.max_tokens").doesNotExist())
                    .andExpect(jsonPath("$.temperature").doesNotExist())
                    .andRespond(withSuccess(RESPONSES_ANSWER, MediaType.APPLICATION_JSON));
        }
        client.validateAndSaveKey("admin", "replacement-test-secret");
        client.testSettings("admin");
        assertTrue(client.status().questionsToday().isEmpty());
        server.verify();
    }

    // 抓住验证沿用最高思考强度和回答上限，超时后无法保存密钥。
    @Test void responsesSavesKeyEvenWhenQuestionsUseMaxThinking() {
        useResponses();
        properties.setThinking("max");
        properties.setAnswerLimit(2000);
        String candidate = "new-test-secret";
        server.expect(requestTo("https://model.example/v1/responses"))
                .andExpect(header("Authorization", "Bearer " + candidate))
                .andRespond(outgoing -> {
                    var body = JSON.parseObject(((MockClientHttpRequest) outgoing).getBodyAsString());
                    if (!JSONObject.of("effort", "low").equals(body.getJSONObject("reasoning"))
                            || body.getIntValue("max_output_tokens") > 256) {
                        throw new SocketTimeoutException("probe exceeded its short output budget");
                    }
                    return withSuccess(RESPONSES_ANSWER, MediaType.APPLICATION_JSON).createResponse(outgoing);
                });
        client.validateAndSaveKey("admin", candidate);
        var headers = new org.springframework.http.HttpHeaders();
        new AssistantKeyStore(directory).authorize(headers);
        assertEquals("Bearer " + candidate, headers.getFirst("Authorization"));
        server.verify();
    }

    // 抓住服务商错误原文或回显的凭据进入诊断日志。
    @Test void responsesLogsOnlySafeErrorFields() {
        useResponses();
        server.expect(requestTo("https://model.example/v1/responses")).andRespond(withBadRequest()
                .contentType(MediaType.APPLICATION_JSON).body(JSONObject.of("error", JSONObject.of(
                        "type", "invalid_request_error", "code", "unsupported_parameter",
                        "param", SECRET, "message", "private provider message " + SECRET)).toJSONString()));
        try (var logs = new AssistantLogCapture(); var question = client.beginQuestion("member")) {
            var error = assertThrows(AssistantException.class, () -> question.complete(request()));
            assertEquals("provider_error", error.reason());
            logs.assertContains("type=invalid_request_error", "code=unsupported_parameter", "param=[已遮掩]");
            logs.assertNoSecret(SECRET);
            logs.assertNoSecret("private provider message");
        }
        server.verify();
    }

    // 抓住新版请求漏到默认 chat 上时，旧部署收到变化的字段。
    @Test void defaultChatRequestKeepsEveryField() {
        var messages = List.of(JSONObject.of("role", "system", "content", "规则"), request().messages().get(0));
        var tools = List.of(JSONObject.of("type", "function", "function", JSONObject.of("name", "lookup")));
        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andExpect(outgoing -> assertEquals(JSONObject.of("model", properties.getModel(), "messages", messages,
                                "tools", tools, "tool_choice", "auto", "max_tokens", 800, "temperature", new BigDecimal("0.2"),
                                "stream", false, "thinking", JSONObject.of("type", "disabled")),
                        JSON.parseObject(((MockClientHttpRequest) outgoing).getBodyAsString())))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"好\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":1}}",
                        MediaType.APPLICATION_JSON));
        try (var question = client.beginQuestion("member")) {
            assertEquals("好", question.complete(new AssistantClient.Request(messages, tools, null, 0.2)).content());
        }
        server.verify();
    }

    static java.util.stream.Stream<Arguments> thinkingRequests() {
        return Map.of("https://api.deepseek.com", true, "https://API.DEEPSEEK.COM/v1/", true,
                        "https://example.com/v1", false, "https://api.deepseek.com.example.com/v1", false,
                        "https://example.com/api.deepseek.com", false).entrySet().stream()
                .flatMap(endpoint -> java.util.stream.Stream.of("question", "save", "test")
                        .flatMap(operation -> java.util.stream.Stream.of("off", "low", "high", "max")
                                .map(thinking -> Arguments.of(endpoint.getKey(), endpoint.getValue(), operation, thinking))));
    }

    @ParameterizedTest @MethodSource("thinkingRequests")
    void thinkingOptionIsScopedToDeepSeekForQuestionsAndProbes(String baseUrl, boolean deepSeek, String operation, String thinking) {
        properties.setBaseUrl(baseUrl);
        properties.setAnswerLimit(1234);
        new Binder(new MapConfigurationPropertySource(Map.of("novabot.assistant.thinking", thinking)))
                .bind("novabot.assistant", Bindable.ofInstance(properties));
        for (int i = 0; i < ("question".equals(operation) ? 2 : 1); i++) {
            server.expect(requestTo(baseUrl.replaceAll("/+$", "") + "/chat/completions"))
                    .andExpect(request -> {
                        JSONObject body = JSON.parseObject(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
                        boolean enabled = deepSeek && "question".equals(operation) && !"off".equals(thinking);
                        if (deepSeek) {
                            assertNotNull(body.getJSONObject("thinking"));
                            assertEquals(enabled ? "enabled" : "disabled", body.getJSONObject("thinking").getString("type"));
                        } else assertFalse(body.containsKey("thinking"));
                        int allowance = 0;
                        if (enabled) {
                            assertEquals(thinking, body.getString("reasoning_effort"));
                            allowance = switch (thinking) {
                                case "low" -> 4000;
                                case "high" -> 16000;
                                case "max" -> 32000;
                                default -> throw new AssertionError(thinking);
                            };
                        } else assertFalse(body.containsKey("reasoning_effort"));
                        assertEquals("question".equals(operation) ? 1234 + allowance : 8, body.getIntValue("max_tokens"));
                    })
                    .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"好\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":1}}", MediaType.APPLICATION_JSON));
        }
        switch (operation) {
            case "save" -> client.validateAndSaveKey("admin", SECRET);
            case "test" -> client.testSettings("admin");
            case "question" -> {
                try (var question = client.beginQuestion("member")) {
                    question.complete(request());
                    question.complete(request());
                }
            }
            default -> fail("Unknown operation: " + operation);
        }
        server.verify();
    }

    @Test void reasoningIsRedactedAndKeptOutOfLogsAndUsage() throws Exception {
        String reasoning = "private provider reasoning " + SECRET;
        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andRespond(withSuccess(JSONObject.of("choices", List.of(JSONObject.of("message",
                                JSONObject.of("content", "好", "reasoning_content", reasoning))),
                        "usage", JSONObject.of("prompt_tokens", 10, "completion_tokens", 1)).toJSONString(), MediaType.APPLICATION_JSON));
        reply("{\"prompt_tokens\":10,\"completion_tokens\":1}");
        try (var logs = new AssistantLogCapture(); var question = client.beginQuestion("member")) {
            var response = question.complete(request());
            assertEquals("private provider reasoning [已遮掩]", response.reasoningContent());
            question.complete(new AssistantClient.Request(List.of(JSONObject.of("role", "assistant", "content", response.content(),
                    "reasoning_content", response.reasoningContent())), List.of(), "none", 0.3));
            logs.assertNoSecret("private provider reasoning");
            logs.assertNoSecret(SECRET);
        }
        try (var files = Files.list(directory)) {
            for (Path file : files.toList()) assertFalse(Files.readString(file).contains("private provider reasoning"));
        }
        server.verify();
    }

    // 抓住发送状态把仅通过可用性检查的提问当作已经发出。
    @Test void transmissionStateChangesOnlyWhenRequestIsSent() {
        properties.setBaseUrl("https://model.example/v1");
        server.expect(requestTo("https://model.example/v1/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"好\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":1}}", MediaType.APPLICATION_JSON));
        try (var question = client.beginQuestion("member")) {
            assertFalse(question.hasSent());
            assertThrows(AssistantException.class, () -> question.complete(request(), java.time.Duration.ZERO));
            assertFalse(question.hasSent());
            question.complete(request());
            assertTrue(question.hasSent());
        }
        server.verify();
    }

    // 抓住剩余时间不足一毫秒被网络工厂取整为无限等待，或超时后仍发送请求。
    @Test void subMillisecondDeadlineStopsBeforeSending() {
        try (var question = client.beginQuestion("first")) {
            assertThrows(AssistantException.class, () -> question.complete(request(), java.time.Duration.ofNanos(1)));
        }
        assertEquals(0, usage.questionsToday().getOrDefault("first", 0));
        server.verify();
    }

    // 抓住月金额达到上限后仍向模型发请求的花费失控。
    @Test void monthlyBudgetStopsBeforeNetwork() {
        usage.addCost(new BigDecimal("30"));
        assertEquals("本月智能助手额度已用完", assertThrows(AssistantException.class,
                () -> client.beginQuestion("first")).getMessage());
        server.verify();
    }

    // 抓住单账号刷过每日上限或错误地封住其他账号。
    @Test void dailyLimitIsPerAccount() {
        properties.setDailyQuestionsPerAccount(1);
        reply("{\"prompt_tokens\":10,\"completion_tokens\":1}");
        reply("{\"prompt_tokens\":10,\"completion_tokens\":1}");
        try (var question = client.beginQuestion("first")) { question.complete(request()); }
        assertEquals("今天的提问次数用完了", assertThrows(AssistantException.class,
                () -> client.beginQuestion("first")).getMessage());
        try (var question = client.beginQuestion("second")) { assertEquals("好", question.complete(request()).content()); }
        server.verify();
    }

    // 抓住接口、日志、异常或盘上文件泄露模型密钥。
    @Test void secretNeverAppearsInStatusLogsErrorsOrFiles() throws Exception {
        try (var logs = new AssistantLogCapture()) {
            reply("{\"prompt_tokens\":10,\"completion_tokens\":1}");
            reply("{\"prompt_tokens\":10,\"completion_tokens\":1}");
            server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                    .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("provider response " + SECRET));
            server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                    .andRespond(withServerError().body("provider response " + SECRET));
            server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                    .andRespond(withException(new SocketTimeoutException("transport detail " + SECRET)));
            server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                    .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED).body("provider response " + SECRET));
            keys.clear();
            client.validateAndSaveKey("admin", SECRET);
            client.testSettings("admin");
            for (int i = 0; i < 4; i++) {
                AssistantException error = assertThrows(AssistantException.class, () -> client.testSettings("admin"));
                assertSafeError(error);
            }
            assertFalse(JSON.toJSONString(client.status()).contains(SECRET));
            assertEquals("test-s…90", client.status().maskedKey());
            try (var files = Files.list(directory)) {
                for (Path file : files.toList()) {
                    assertFalse(Files.readString(file).contains(SECRET));
                    assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
                }
            }
            assertTrue(new AssistantKeyStore(directory).isSet());
            logs.assertNoSecret(SECRET);
        }
        server.verify();
    }

    // 抓住试调失败仍保存错误密钥并显示可用。
    @Test void failedProbeDoesNotSaveKey() {
        keys.clear();
        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body(SECRET));
        assertThrows(AssistantException.class, () -> client.validateAndSaveKey("admin", SECRET));
        assertFalse(keys.isSet());
        assertFalse(client.status().available());
        assertTrue(usage.questionsToday().isEmpty());
        server.verify();
    }

    // 抓住总开关、密钥或当前版本确认缺失时仍把数据发出去。
    @Test void readinessRequiresEnabledKeyAndCurrentAcknowledgement() {
        keys.clear();
        assertTrue(assertThrows(AssistantException.class, () -> client.beginQuestion("first")).getMessage().contains("密钥"));
        keys.save(SECRET);
        var emptyUsage = new AssistantUsageStore(directory.resolve("other"), clock);
        var unconfirmed = new AssistantClient(properties, keys, emptyUsage, template);
        assertTrue(assertThrows(AssistantException.class, () -> unconfirmed.beginQuestion("first")).getMessage().contains("确认"));
        assertTrue(assertThrows(AssistantException.class, () -> unconfirmed.testSettings("admin")).getMessage().contains("确认"));
        usage.acknowledge("admin", "previous-version");
        assertTrue(assertThrows(AssistantException.class, () -> client.beginQuestion("first")).getMessage().contains("确认"));
        client.acknowledge("admin");
        properties.setEnabled(false);
        assertTrue(assertThrows(AssistantException.class, () -> client.beginQuestion("first")).getMessage().contains("关闭"));
        assertTrue(assertThrows(AssistantException.class, () -> client.testSettings("admin")).getMessage().contains("关闭"));
        server.verify();
    }

    // 抓住重启后密钥无法解密，或模型回显鉴权内容时再次泄露密钥。
    @Test void restartedClientDecryptsKeyAndRedactsEchoes() {
        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andExpect(header("Authorization", "Bearer " + SECRET))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"" + SECRET
                        + "\",\"tool_calls\":[{\"function\":{\"arguments\":\"" + SECRET
                        + "\"}}]}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":1}}", MediaType.APPLICATION_JSON));
        var restarted = new AssistantClient(properties, new AssistantKeyStore(directory), new AssistantUsageStore(directory, clock), template);
        try (var question = restarted.beginQuestion("first")) {
            assertFalse(JSON.toJSONString(question.complete(request())).contains(SECRET));
        }
        server.verify();
    }

    // 抓住达到月上限后，同一问的后续工具轮次仍继续产生花费。
    @Test void subsequentRoundRechecksBudgetAndCountsQuestionOnce() {
        properties.setMonthlyBudgetYuan(new BigDecimal("0.000001"));
        reply("{\"prompt_tokens\":10,\"completion_tokens\":1}");
        try (var question = client.beginQuestion("first")) {
            question.complete(request());
            assertEquals("本月智能助手额度已用完", assertThrows(AssistantException.class,
                    () -> question.complete(request())).getMessage());
        }
        assertEquals(1, usage.questionsToday().get("first"));
        server.verify();
    }

    // 抓住超时或服务端错误后自动重试造成的重复花费。
    @Test void timeoutAndServerErrorSendOnlyOnce() {
        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andRespond(withException(new SocketTimeoutException("transport detail " + SECRET)));
        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andRespond(withServerError().body("provider response " + SECRET));
        AssistantException timeout = assertThrows(AssistantException.class, () -> client.testSettings("admin"));
        AssistantException serverError = assertThrows(AssistantException.class, () -> client.testSettings("admin"));
        assertTrue(timeout.getMessage().contains("超时"));
        assertTrue(serverError.getMessage().contains("出错"));
        assertSafeError(timeout);
        assertSafeError(serverError);
        server.verify();
    }

    private static void assertSafeError(AssistantException error) {
        assertFalse(error.toString().contains(SECRET));
        assertFalse(error.getMessage().contains("provider response"));
        assertFalse(error.getMessage().contains("transport detail"));
        assertNull(error.getCause());
    }

    // 抓住网络请求持锁，导致提问在途时设置页、确认和删除密钥被阻塞。
    @Test void settingsReturnWhileQuestionIsInFlight() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        server.expect(requestTo("https://api.deepseek.com/chat/completions")).andRespond(request -> {
            entered.countDown();
            try {
                if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("response was not released");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError(error);
            }
            return withSuccess("{\"choices\":[{\"message\":{\"content\":\"好\"}}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":1}}", MediaType.APPLICATION_JSON)
                    .createResponse(request);
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var response = executor.submit(() -> {
                try (var question = client.beginQuestion("first")) { return question.complete(request()); }
            });
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var settings = executor.submit(() -> {
                assertTrue(client.status().available());
                client.acknowledge("second-admin");
                client.deleteKey();
            });
            settings.get(1, TimeUnit.SECONDS);
            assertFalse(keys.isSet());
            assertEquals("second-admin", usage.acknowledgement().actor());
            release.countDown();
            assertEquals("好", response.get(10, TimeUnit.SECONDS).content());
            assertTrue(usage.spentYuan().signum() > 0);
        } finally {
            release.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        server.verify();
    }

    // 抓住试调被提问额度拦住或占用管理员每日次数，同时确保试调花费仍记账。
    @Test void diagnosticsBypassQuestionLimitsButRecordCosts() {
        properties.setDailyQuestionsPerAccount(1);
        usage.startQuestion("admin", properties);
        usage.addCost(new BigDecimal("30"));
        reply("{\"prompt_tokens\":10,\"completion_tokens\":1}");
        reply("{\"prompt_tokens\":10,\"completion_tokens\":1}");
        client.validateAndSaveKey("admin", SECRET);
        client.testSettings("admin");
        assertEquals(java.util.Map.of("admin", 1), usage.questionsToday());
        assertEquals(0, new BigDecimal("30.0000612").compareTo(usage.spentYuan()));
        assertEquals(0, usage.spentYuan().compareTo(new AssistantUsageStore(directory, clock).spentYuan()));
        server.verify();
    }

    // 抓住主密钥丢失但旧密文仍在时，重新设置密钥仍然失败。
    @Test void savingKeyRecoversFromMissingMaster() throws Exception {
        Files.delete(directory.resolve("assistant-master.key"));
        assertFalse(keys.isSet());
        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andExpect(request -> {
                    assertTrue(Files.exists(directory.resolve("assistant-master.key")));
                    assertFalse(Files.exists(directory.resolve("assistant-key.enc")));
                })
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"好\"}}],"
                        + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":1}}", MediaType.APPLICATION_JSON));
        reply("{\"prompt_tokens\":10,\"completion_tokens\":1}");
        client.validateAndSaveKey("admin", SECRET);
        var restarted = new AssistantClient(properties, new AssistantKeyStore(directory),
                new AssistantUsageStore(directory, clock), template);
        assertTrue(restarted.status().available());
        restarted.testSettings("admin");
        server.verify();
    }

    // 抓住用量文件损坏导致启动失败，或悄悄清零后继续发送请求。
    @Test void corruptUsageAllowsStartupButBlocksCalls() throws Exception {
        Path path = directory.resolve("assistant-usage.json");
        String broken = "{broken usage";
        Files.writeString(path, broken);
        var damagedUsage = assertDoesNotThrow(() -> new AssistantUsageStore(directory, clock));
        var damaged = new AssistantClient(properties, keys, damagedUsage, template);
        assertFalse(damaged.status().available());
        assertTrue(String.join("；", damaged.status().missing()).contains("用量文件无法读取"));
        assertNull(damaged.status().spentYuan());
        assertTrue(assertThrows(AssistantException.class, () -> damaged.beginQuestion("first"))
                .getMessage().contains("用量文件无法读取"));
        assertThrows(AssistantException.class, () -> damaged.testSettings("admin"));
        assertThrows(AssistantException.class, () -> damaged.validateAndSaveKey("admin", SECRET));
        assertThrows(AssistantException.class, () -> damaged.acknowledge("admin"));
        properties.setEnabled(false);
        assertFalse(damaged.status().available());
        assertDoesNotThrow(() -> new AssistantUsageStore(directory, clock));
        assertEquals(broken, Files.readString(path));
        server.verify();
    }

    // 抓住缓存命中、未命中或输出计价错误导致金额上限失准。
    @Test void cacheUsageHasExactYuanCost() {
        reply("{\"prompt_tokens\":1000000,\"prompt_cache_hit_tokens\":400000,\"prompt_cache_miss_tokens\":600000,\"completion_tokens\":100000}");
        try (var question = client.beginQuestion("first")) {
            assertEquals(0, new BigDecimal("2.20").compareTo(question.complete(request()).costYuan()));
            assertEquals(0, new BigDecimal("2.20").compareTo(question.totalCostYuan()));
        }
        assertEquals(0, new BigDecimal("2.20").compareTo(client.status().spentYuan()));
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"100, 40, 100, 0.00086", "100, 160, 260, 0.002236", "100, 100, 100, 0.00086"})
    void reasoningTokensAreBilledOnce(long completion, long reasoning, long expectedOutput, String expectedCost) {
        reply(JSONObject.of("prompt_tokens", 0, "completion_tokens", completion,
                "completion_tokens_details", JSONObject.of("reasoning_tokens", reasoning)).toJSONString());
        try (var question = client.beginQuestion("member")) {
            var response = question.complete(request());
            assertEquals(expectedOutput, response.usage().outputTokens());
            assertEquals(0, new BigDecimal(expectedCost).compareTo(response.costYuan()));
            assertEquals(0, response.costYuan().compareTo(question.totalCostYuan()));
        }
        assertEquals(0, new BigDecimal(expectedCost).compareTo(new AssistantUsageStore(directory, clock).spentYuan()));
        server.verify();
    }

    // 抓住另一种缓存字段和无缓存字段时使用了错误单价。
    @Test void nestedCacheAndUncachedUsage() {
        reply("{\"prompt_tokens\":1000000,\"prompt_tokens_details\":{\"cached_tokens\":400000},\"completion_tokens\":100000}");
        reply("{\"prompt_tokens\":1000000,\"completion_tokens\":100000}");
        try (var question = client.beginQuestion("first")) {
            assertEquals(0, new BigDecimal("2.20").compareTo(question.complete(request()).costYuan()));
            assertEquals(0, new BigDecimal("3.06").compareTo(question.complete(request()).costYuan()));
        }
        assertEquals(1, client.status().questionsToday().get("first"));
        assertEquals(0, new BigDecimal("5.26").compareTo(new AssistantUsageStore(directory, clock).spentYuan()));
        server.verify();
    }

    // 抓住工具定义、选择方式、输出限额丢失或工具调用无法读取。
    @Test void toolRequestAndResponseArePreserved() {
        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andExpect(jsonPath("$.max_tokens").value(800))
                .andExpect(jsonPath("$.temperature").value(0.2))
                .andExpect(jsonPath("$.tools[0].function.name").value("lookup"))
                .andExpect(jsonPath("$.tool_choice").value("auto"))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{}\"}}]}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":1}}", MediaType.APPLICATION_JSON));
        try (var question = client.beginQuestion("first")) {
            var result = question.complete(new AssistantClient.Request(request().messages(),
                    List.of(JSONObject.of("type", "function", "function", JSONObject.of("name", "lookup"))), "auto", 0.2));
            assertEquals("lookup", result.toolCalls().get(0).getJSONObject("function").getString("name"));
        }
        server.verify();
    }

    // 抓住自然月、自然日没有按东八区重置或确认记录随重启丢失。
    @Test void usageResetsAtShanghaiBoundariesAndAcknowledgementPersists() {
        usage.startQuestion("first", properties);
        usage.addCost(BigDecimal.ONE);
        var nextDay = new AssistantUsageStore(directory, Clock.fixed(Instant.parse("2026-10-24T16:00:00Z"), ZoneOffset.UTC));
        assertTrue(nextDay.questionsToday().isEmpty());
        assertEquals(0, BigDecimal.ONE.compareTo(nextDay.spentYuan()));
        var nextMonth = new AssistantUsageStore(directory, Clock.fixed(Instant.parse("2026-10-31T16:00:00Z"), ZoneOffset.UTC));
        assertEquals(0, BigDecimal.ZERO.compareTo(nextMonth.spentYuan()));
        assertEquals(AssistantClient.ACKNOWLEDGEMENT_VERSION, nextMonth.acknowledgement().version());
        assertEquals("admin", nextMonth.acknowledgement().actor());
    }
}
