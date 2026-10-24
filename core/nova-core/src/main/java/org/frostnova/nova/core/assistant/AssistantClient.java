package org.frostnova.nova.core.assistant;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 非流式模型客户端，不重试。调用方用 try-with-resources 为一问建立一个 Question，
 * 多轮工具调用复用它；每日次数只占一次，totalCostYuan 给出这一问的累计花费。
 * 每轮返回的用量立即落盘，包括后续消息解析失败的情况，避免中途退出漏记。
 */
public final class AssistantClient {
    public static final String ACKNOWLEDGEMENT_VERSION = "2026-10-24-v1";
    public static final String ACKNOWLEDGEMENT_TEXT = "开启后，每次有人提问，NovaBot 会把这位主播的直播数据发给上面配置的模型服务商，可能包括汇总数字、截断的弹幕原话、观众昵称和金额。数据发出后由该服务商按它的条款处理，开启和配置智能助手的人对此负责。";

    public record Request(List<JSONObject> messages, List<JSONObject> tools, Object toolChoice, double temperature) { }
    public record Usage(long cachedInputTokens, long uncachedInputTokens, long outputTokens) { }
    public record Response(String content, List<JSONObject> toolCalls, Usage usage, BigDecimal costYuan) { }
    public record Status(boolean enabled, boolean available, List<String> missing, boolean keySet, String maskedKey,
                         String baseUrl, String model, BigDecimal spentYuan, BigDecimal monthlyBudgetYuan,
                         int dailyQuestionsPerAccount, Map<String, Integer> questionsToday,
                         AssistantUsageStore.Acknowledgement acknowledgement,
                         String acknowledgementVersion, String acknowledgementText) { }

    private final AssistantProperties properties;
    private final AssistantKeyStore keys;
    private final AssistantUsageStore usage;
    private final RestTemplate template;

    public AssistantClient(AssistantProperties properties, AssistantKeyStore keys, AssistantUsageStore usage, RestTemplate template) {
        this.properties = properties;
        this.keys = keys;
        this.usage = usage;
        this.template = template;
    }

    public synchronized Status status() {
        List<String> missing = missing();
        return new Status(properties.isEnabled(), missing.isEmpty(), missing, keys.isSet(), keys.masked(),
                properties.getBaseUrl(), properties.getModel(), usage.spentYuan(), properties.getMonthlyBudgetYuan(),
                properties.getDailyQuestionsPerAccount(), usage.questionsToday(), usage.acknowledgement(),
                ACKNOWLEDGEMENT_VERSION, ACKNOWLEDGEMENT_TEXT);
    }

    public synchronized void acknowledge(String actor) { usage.acknowledge(actor, ACKNOWLEDGEMENT_VERSION); }

    public synchronized void deleteKey() { keys.clear(); }

    /** 管理员的连接诊断只发送固定短句，可在总开关关闭或尚未确认时配置密钥，也受额度限制。 */
    public synchronized void validateAndSaveKey(String account, String candidate) {
        AssistantKeyStore.validate(candidate);
        probe(account, candidate);
        keys.save(candidate);
    }

    /** 使用当前设置做连接诊断，同样要求开启、设置密钥和确认；花费和次数照常记账。 */
    public synchronized void testSettings(String account) {
        requireAvailable();
        probe(account, null);
    }

    public synchronized Question beginQuestion(String account) {
        requireAvailable();
        usage.checkQuestion(account, properties);
        return new Question(account);
    }

    public final class Question implements AutoCloseable {
        private final String account;
        private boolean started;
        private boolean closed;
        private BigDecimal total = BigDecimal.ZERO;

        private Question(String account) { this.account = account; }

        public Response complete(Request request) {
            synchronized (AssistantClient.this) {
                if (closed) throw new AssistantException("这一问已经结束");
                requireAvailable();
                validate(request);
                usage.checkBudget(properties);
                if (!started) { usage.startQuestion(account, properties); started = true; }
                return send(request, properties.getMaxAnswerTokens(), null, this);
            }
        }

        public BigDecimal totalCostYuan() { synchronized (AssistantClient.this) { return total; } }

        @Override public void close() { synchronized (AssistantClient.this) { closed = true; } }
    }

    private void probe(String account, String candidate) {
        if (candidate == null && !keys.isSet()) throw new AssistantException("尚未设置智能助手模型密钥");
        usage.startQuestion(account, properties);
        send(new Request(List.of(JSONObject.of("role", "user", "content", "回复好")), List.of(), "none", 0),
                Math.min(8, properties.getMaxAnswerTokens()), candidate, null);
    }

    private List<String> missing() {
        List<String> missing = new ArrayList<>();
        if (!properties.isEnabled()) missing.add("智能助手总开关已关闭");
        if (!keys.isSet()) missing.add("尚未设置智能助手模型密钥");
        var accepted = usage.acknowledgement();
        if (accepted == null || !ACKNOWLEDGEMENT_VERSION.equals(accepted.version())
                || accepted.actor() == null || accepted.actor().isBlank() || accepted.at() == null || accepted.at().isBlank()) {
            missing.add("尚未确认当前版本的智能助手配置者说明");
        }
        return List.copyOf(missing);
    }

    private void requireAvailable() {
        List<String> missing = missing();
        if (!missing.isEmpty()) throw new AssistantException(String.join("；", missing));
    }

    private static void validate(Request request) {
        if (request == null || request.messages() == null || request.messages().isEmpty()
                || !Double.isFinite(request.temperature()) || request.temperature() < 0 || request.temperature() > 2) {
            throw new AssistantException("智能助手请求参数不完整");
        }
    }

    private Response send(Request request, int maxTokens, String candidate, Question question) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (candidate == null) keys.authorize(headers); else headers.setBearerAuth(candidate);
        try {
            JSONObject body = JSONObject.of("model", properties.getModel(), "messages", request.messages(),
                    "tools", request.tools() == null ? List.of() : request.tools(),
                    "tool_choice", request.toolChoice() == null ? "auto" : request.toolChoice(),
                    "max_tokens", maxTokens, "temperature", request.temperature(), "stream", false);
            byte[] bytes = template.exchange(properties.getBaseUrl().replaceAll("/+$", "") + "/chat/completions",
                    HttpMethod.POST, new HttpEntity<>(body.toJSONString(), headers), byte[].class).getBody();
            if (bytes == null) throw new AssistantException("大模型服务返回了空响应");
            JSONObject json = JSON.parseObject(new String(bytes, StandardCharsets.UTF_8));
            Usage tokens = parseUsage(json.getJSONObject("usage"));
            BigDecimal cost = cost(tokens);
            if (question != null) question.total = question.total.add(cost);
            usage.addCost(cost);
            JSONObject message = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
            String secret = headers.getFirst(HttpHeaders.AUTHORIZATION).substring(7);
            // 服务端即使回显鉴权内容，也不能从公开结果中带回密钥。
            redact(message, secret);
            JSONArray calls = message.getJSONArray("tool_calls");
            List<JSONObject> tools = calls == null ? List.of() : calls.toJavaList(JSONObject.class);
            return new Response(message.getString("content"), List.copyOf(tools), tokens, cost);
        } catch (HttpStatusCodeException error) {
            int status = error.getStatusCode().value();
            if (status == 401 || status == 403) throw new AssistantException("模型密钥不正确或没有访问权限");
            if (status == 402) throw new AssistantException("模型服务余额不足，请检查服务商账户");
            if (status == 429) throw new AssistantException("模型服务额度不足或请求过多，请稍后再试");
            throw new AssistantException("大模型服务出错，请稍后再试");
        } catch (ResourceAccessException error) {
            throw new AssistantException("连接大模型服务超时或网络不可用，请稍后再试");
        } catch (RestClientException error) {
            throw new AssistantException("大模型服务出错，请稍后再试");
        } catch (AssistantException error) { throw error; }
        catch (RuntimeException error) { throw new AssistantException("大模型服务返回的数据不完整，请稍后再试"); }
        finally { headers.remove(HttpHeaders.AUTHORIZATION); }
    }

    private static void redact(Object value, String secret) {
        if (value instanceof JSONObject object) {
            for (String key : List.copyOf(object.keySet())) {
                Object child = object.get(key);
                if (child instanceof String text) object.put(key, text.replace(secret, "[已遮掩]"));
                else redact(child, secret);
            }
        } else if (value instanceof JSONArray array) {
            for (int i = 0; i < array.size(); i++) {
                Object child = array.get(i);
                if (child instanceof String text) array.set(i, text.replace(secret, "[已遮掩]"));
                else redact(child, secret);
            }
        }
    }

    private static Usage parseUsage(JSONObject json) {
        if (json == null) throw new AssistantException("大模型服务未返回用量，无法计算花费");
        long output = count(json, "completion_tokens");
        long hit;
        long miss;
        if (json.containsKey("prompt_cache_hit_tokens") && json.containsKey("prompt_cache_miss_tokens")) {
            hit = count(json, "prompt_cache_hit_tokens");
            miss = count(json, "prompt_cache_miss_tokens");
        } else {
            long prompt = count(json, "prompt_tokens");
            JSONObject details = json.getJSONObject("prompt_tokens_details");
            hit = details != null && details.containsKey("cached_tokens") ? count(details, "cached_tokens") : 0;
            if (hit > prompt) throw new AssistantException("大模型服务返回的用量不正确");
            miss = prompt - hit;
        }
        return new Usage(hit, miss, output);
    }

    private static long count(JSONObject json, String field) {
        BigDecimal value = json.getBigDecimal(field);
        if (value == null || value.signum() < 0) throw new AssistantException("大模型服务返回的用量不完整");
        return value.longValueExact();
    }

    private BigDecimal cost(Usage tokens) {
        return properties.getPriceCachedInputYuanPerMillion().multiply(BigDecimal.valueOf(tokens.cachedInputTokens()))
                .add(properties.getPriceInputYuanPerMillion().multiply(BigDecimal.valueOf(tokens.uncachedInputTokens())))
                .add(properties.getPriceOutputYuanPerMillion().multiply(BigDecimal.valueOf(tokens.outputTokens())))
                .movePointLeft(6);
    }
}
