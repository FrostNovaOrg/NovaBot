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
import java.time.Duration;
import java.util.function.Function;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 非流式模型客户端，不重试。调用方用 try-with-resources 为一问建立一个 Question，
 * 多轮工具调用复用它；每日次数只占一次，totalCostYuan 给出这一问的累计花费。
 * 每轮返回的用量立即落盘，包括后续消息解析失败的情况，避免中途退出漏记。
 */
public final class AssistantClient {
    public static final String ACKNOWLEDGEMENT_VERSION = "2026-10-24-v2";
    public static final String ACKNOWLEDGEMENT_TEXT = """
            开启后，每次有人提问，NovaBot 会把提问和回答要用到的直播数据，经你填的密钥发给所配置的模型服务商（{接口地址}）处理，适用它自己的协议和隐私政策。默认只发汇总数字、礼物与打赏的汇总金额、贡献者代号及占比和少量截断的弹幕原话；观众昵称换成代号，观众的账号编号不发。
            发不发、发给谁由你决定，也由你负责；调用费用由你和模型服务商结算。
            智能助手的回答可能出错，只供参考，数字以报告里的原始数据为准。
            如需在国内办理备案、登记等手续，由你自己办；回答旁的「由人工智能生成」提示请保留。""";

    public record Request(List<JSONObject> messages, List<JSONObject> tools, Object toolChoice, double temperature) { }
    public record Usage(long cachedInputTokens, long uncachedInputTokens, long outputTokens) { }
    public record Response(String content, List<JSONObject> toolCalls, Usage usage, BigDecimal costYuan) { }
    public record Status(boolean enabled, boolean available, List<String> missing, boolean keySet, String maskedKey,
                         String baseUrl, String model, BigDecimal spentYuan, BigDecimal monthlyBudgetYuan,
                         int dailyQuestionsPerAccount, Map<String, Integer> questionsToday,
                         AssistantUsageStore.Acknowledgement acknowledgement,
                         String acknowledgementVersion, String acknowledgementText) { }

    public record State(boolean enabled, boolean available, List<String> reasons, int questionsLeftToday) { }

    private final AssistantProperties properties;
    private final AssistantKeyStore keys;
    private final AssistantUsageStore usage;
    private final Function<Duration, RestTemplate> templates;

    public AssistantClient(AssistantProperties properties, AssistantKeyStore keys, AssistantUsageStore usage, RestTemplate template) {
        this(properties, keys, usage, timeout -> template);
    }

    public AssistantClient(AssistantProperties properties, AssistantKeyStore keys, AssistantUsageStore usage,
                           Function<Duration, RestTemplate> templates) {
        this.properties = properties;
        this.keys = keys;
        this.usage = usage;
        this.templates = templates;
    }

    public synchronized Status status() {
        List<String> missing = unavailable().stream().map(AssistantException::getMessage).toList();
        return new Status(properties.isEnabled(), missing.isEmpty(), missing, keys.isSet(), keys.masked(),
                properties.getBaseUrl(), properties.getModel(), usage.spentYuan(), properties.getMonthlyBudgetYuan(),
                properties.getDailyQuestionsPerAccount(), usage.questionsToday(), usage.acknowledgement(),
                ACKNOWLEDGEMENT_VERSION, ACKNOWLEDGEMENT_TEXT.replace("{接口地址}", properties.getBaseUrl()));
    }

    /** 只读的账号可用状态，不发送请求，也不占用提问次数。 */
    public synchronized State state(String account) {
        var reasons = new ArrayList<>(unavailable().stream().map(AssistantException::reason).toList());
        int left = usage.problem() == null
                ? Math.max(0, properties.getDailyQuestionsPerAccount() - usage.questionsToday().getOrDefault(account, 0)) : 0;
        if (usage.problem() == null) {
            if (left == 0) reasons.add("daily_limit");
            if (usage.spentYuan().compareTo(properties.getMonthlyBudgetYuan()) >= 0) reasons.add("monthly_budget");
        }
        return new State(properties.isEnabled(), reasons.isEmpty(), List.copyOf(reasons), left);
    }

    public synchronized void acknowledge(String actor) { usage.acknowledge(actor, ACKNOWLEDGEMENT_VERSION); }

    public synchronized void deleteKey() { keys.clear(); }

    /** 管理员的连接诊断只发送固定短句，不占提问次数，不受提问额度限制，花费照常记账。 */
    public void validateAndSaveKey(String account, String candidate) {
        AssistantKeyStore.validate(candidate);
        usage.requireReadable();
        keys.prepareForSave();
        probe(candidate);
        keys.save(candidate);
    }

    /** 使用当前设置做连接诊断，同样要求开启、设置密钥和确认；只记花费，不占提问次数。 */
    public void testSettings(String account) {
        requireAvailable();
        probe(null);
    }

    public synchronized Question beginQuestion(String account) {
        requireAvailable();
        usage.checkQuestion(account, properties);
        return new Question(account);
    }

    public final class Question implements AutoCloseable {
        private final String account;
        private boolean started;
        private boolean sent;
        private boolean closed;
        private boolean inFlight;
        private BigDecimal total = BigDecimal.ZERO;

        private Question(String account) { this.account = account; }

        public Response complete(Request request) { return complete(request, Duration.ofSeconds(90)); }

        /** 调用方把整问的剩余时间传入，网络等待不能越过整问截止时刻。 */
        public Response complete(Request request, Duration timeout) {
            if (timeout == null || timeout.compareTo(Duration.ofMillis(1)) < 0)
                throw new AssistantException("timeout", "智能助手本次提问已超时，请问得具体一点");
            synchronized (AssistantClient.this) {
                if (closed) throw new AssistantException("这一问已经结束");
                if (inFlight) throw new AssistantException("这一问正在请求大模型服务，请等待返回");
                requireAvailable();
                validate(request);
                usage.checkBudget(properties);
                if (!started) { usage.startQuestion(account, properties); started = true; }
                inFlight = true;
            }
            try {
                return send(request, properties.getMaxAnswerTokens(), null, this, timeout);
            } finally {
                synchronized (AssistantClient.this) { inFlight = false; }
            }
        }

        public boolean hasSent() { synchronized (AssistantClient.this) { return sent; } }

        public BigDecimal totalCostYuan() { synchronized (AssistantClient.this) { return total; } }

        @Override public void close() { synchronized (AssistantClient.this) { closed = true; } }
    }

    private void probe(String candidate) {
        if (candidate == null && !keys.isSet()) throw new AssistantException("no_key", "尚未设置智能助手模型密钥");
        usage.requireReadable();
        send(new Request(List.of(JSONObject.of("role", "user", "content", "回复好")), List.of(), "none", 0),
                Math.min(8, properties.getMaxAnswerTokens()), candidate, null, Duration.ofSeconds(20));
    }

    private List<AssistantException> unavailable() {
        var missing = new ArrayList<AssistantException>();
        if (!properties.isEnabled()) missing.add(new AssistantException("disabled", "智能助手总开关已关闭"));
        if (!keys.isSet()) missing.add(new AssistantException("no_key", "尚未设置智能助手模型密钥"));
        if (usage.problem() != null) {
            missing.add(new AssistantException("usage_unreadable", usage.problem()));
        } else {
            var accepted = usage.acknowledgement();
            if (accepted == null || !ACKNOWLEDGEMENT_VERSION.equals(accepted.version())
                    || accepted.actor() == null || accepted.actor().isBlank() || accepted.at() == null || accepted.at().isBlank()) {
                missing.add(new AssistantException("not_acknowledged", "尚未确认当前版本的智能助手配置者说明"));
            }
        }
        return List.copyOf(missing);
    }

    private void requireAvailable() {
        var missing = unavailable();
        if (!missing.isEmpty()) throw new AssistantException(missing.get(0).reason(),
                String.join("；", missing.stream().map(AssistantException::getMessage).toList()));
    }

    private static void validate(Request request) {
        if (request == null || request.messages() == null || request.messages().isEmpty()
                || !Double.isFinite(request.temperature()) || request.temperature() < 0 || request.temperature() > 2) {
            throw new AssistantException("智能助手请求参数不完整");
        }
    }

    private Response send(Request request, int maxTokens, String candidate, Question question, Duration timeout) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (candidate == null) keys.authorize(headers); else headers.setBearerAuth(candidate);
        try {
            JSONObject body = JSONObject.of("model", properties.getModel(), "messages", request.messages(),
                    "tools", request.tools() == null ? List.of() : request.tools(),
                    "tool_choice", request.toolChoice() == null ? "auto" : request.toolChoice(),
                    "max_tokens", maxTokens, "temperature", request.temperature(), "stream", false);
            RestTemplate template = templates.apply(timeout);
            if (question != null) {
                synchronized (AssistantClient.this) { question.sent = true; }
            }
            byte[] bytes = template.exchange(properties.getBaseUrl().replaceAll("/+$", "") + "/chat/completions",
                    HttpMethod.POST, new HttpEntity<>(body.toJSONString(), headers), byte[].class).getBody();
            if (bytes == null) throw new AssistantException("大模型服务返回了空响应");
            JSONObject json = JSON.parseObject(new String(bytes, StandardCharsets.UTF_8));
            Usage tokens = parseUsage(json.getJSONObject("usage"));
            BigDecimal cost = cost(tokens);
            if (question != null) {
                synchronized (AssistantClient.this) { question.total = question.total.add(cost); }
            }
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
            if (status == 401 || status == 403) throw new AssistantException("key_rejected", "模型密钥不正确或没有访问权限");
            if (status == 402) throw new AssistantException("provider_balance", "模型服务余额不足，请检查服务商账户");
            if (status == 429) throw new AssistantException("provider_busy", "模型服务额度不足或请求过多，请稍后再试");
            throw new AssistantException("大模型服务出错，请稍后再试");
        } catch (ResourceAccessException error) {
            throw new AssistantException("network", "连接大模型服务超时或网络不可用，请稍后再试");
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
