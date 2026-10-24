package org.frostnova.nova.core.assistant;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level previous = root.getLevel();
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs = new ListAppender<>();
        logs.start(); root.addAppender(logs); root.setLevel(Level.TRACE);
        try {
            server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                    .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body(SECRET));
            AssistantException error = assertThrows(AssistantException.class, () -> client.testSettings("admin"));
            assertFalse(error.toString().contains(SECRET));
            assertNull(error.getCause());
            assertFalse(JSON.toJSONString(client.status()).contains(SECRET));
            assertEquals("test-s…90", client.status().maskedKey());
            try (var files = Files.list(directory)) {
                for (Path file : files.toList()) {
                    assertFalse(Files.readString(file).contains(SECRET));
                    assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
                }
            }
            assertTrue(new AssistantKeyStore(directory).isSet());
            assertTrue(logs.list.stream().noneMatch(event -> event.getFormattedMessage().contains(SECRET)));
        } finally { root.setLevel(previous); root.detachAppender(logs); logs.stop(); }
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
                .andRespond(withException(new SocketTimeoutException(SECRET)));
        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andRespond(withServerError().body(SECRET));
        assertTrue(assertThrows(AssistantException.class, () -> client.testSettings("admin")).getMessage().contains("超时"));
        assertTrue(assertThrows(AssistantException.class, () -> client.testSettings("admin")).getMessage().contains("出错"));
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
