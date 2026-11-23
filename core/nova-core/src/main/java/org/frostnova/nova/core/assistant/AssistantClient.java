package org.frostnova.nova.core.assistant;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import org.frostnova.nova.core.assistant.AssistantUsageStore.Streamer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 非流式模型客户端。调用方用 try-with-resources 为一问建立一个 Question，
 * 多轮工具调用复用它，totalCostYuan 给出这一问的累计花费。
 * 每轮返回的用量立即落盘，包括后续消息解析失败的情况，避免中途退出漏记。
 */
public final class AssistantClient {
    public static final String ACKNOWLEDGEMENT_VERSION = "2026-11-22-v3";
    public static final String ACKNOWLEDGEMENT_TEXT = """
            开启后，NovaBot 会经你填的接口将提问和查到的直播数据发给所配置的模型服务商（{接口地址}）处理，适用它自己的协议和隐私政策。
            查到的资料可包括汇总数字和金额、场次时间、标题和分区、PK 结果。按观众代号可发到场、送礼、上舰、醒目留言、关注和弹幕的记录，包括发生时刻、统计日和该场开播时间，以及按时段或种类的统计。还可发礼物、盲盒和开出物的名称，付费个数、条数和金额（到手与实扣）、上舰等级和新开或续费、按实扣统计的付费情况和贡献金额占比，以及弹幕字数、在本机按指定词匹配出的弹幕条数、首次留档的统计日和新老观众分类。QQ 查数按通道的金额可见设置处理。发给模型的数据不附归档里的弹幕和醒目留言原话。
            追问会带上最近的问答，QQ 还可能带上之前查过哪些数据的说明。有效的助手备忘会连同记录日期和已填的截止日随该主播的提问发送。观众明细和备忘中能识别的观众昵称会换成代号。软件不附观众账号编号或提问人的 QQ 号，提问、备忘等文字里自己写入的个人信息仍可能发出。
            发不发、发给谁由你决定，也由你负责；调用费用由你和模型服务商结算。
            智能助手的回答可能出错，只供参考，数字以报告里的原始数据为准。
            如需在国内办理备案、登记等手续，由你自己办；回答旁的「由人工智能生成」提示请保留。""";

    public record Request(List<JSONObject> messages, List<JSONObject> tools, Object toolChoice, double temperature) { }
    public record Usage(long cachedInputTokens, long uncachedInputTokens, long outputTokens) { }
    public record Response(String content, String reasoningContent, List<JSONObject> toolCalls, Usage usage,
                           BigDecimal costYuan, List<JSONObject> continuation) {
        public Response(String content, String reasoningContent, List<JSONObject> toolCalls, Usage usage, BigDecimal costYuan) {
            this(content, reasoningContent, toolCalls, usage, costYuan, List.of());
        }
    }
    public record Status(boolean enabled, boolean available, List<String> missing, boolean keySet, String maskedKey,
                         String baseUrl, String model, BigDecimal spentYuan, BigDecimal weeklyBudgetYuan,
                         List<StreamerUsage> streamerUsage,
                         AssistantUsageStore.Acknowledgement acknowledgement,
                         String acknowledgementVersion, String acknowledgementText) { }

    public record StreamerUsage(String platform, String uid, String nickname, BigDecimal todaySpentYuan, BigDecimal weekSpentYuan) { }
    public record StreamerQuota(Integer dailyPercent, Integer weeklyPercent) { }
    public record State(boolean enabled, boolean available, List<String> reasons, StreamerQuota streamerQuota) { }
    public record BudgetStatus(State state, BigDecimal spentYuan, BigDecimal weeklyBudgetYuan, String notice) { }

    private enum CallMode { ANSWER, ANALYSIS, CLASSIFY, PROBE }
    private record Attempt(Response response, boolean outputLimitReached) { }
    private static final Logger LOG = LoggerFactory.getLogger(AssistantClient.class);
    private static final String OUTPUT_LIMIT = "max_output_tokens";
    private static final String ERROR_MASK = "[已遮掩]";
    private static final List<String> THINKING_LEVELS = List.of("off", "low", "medium", "high", "max");

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

    public Status status() {
        return status((platform, uid) -> null);
    }

    public Status status(BiFunction<String, Long, String> nickname) {
        List<String> missing = unavailable().stream().map(AssistantException::getMessage).toList();
        var snapshot = usage.snapshot();
        var byStreamer = snapshot.streamers().entrySet().stream().map(entry -> {
            Streamer streamer = entry.getKey();
            var used = entry.getValue();
            String name = nickname.apply(streamer.platform(), streamer.uid());
            return new StreamerUsage(streamer.platform(), Long.toString(streamer.uid()), name == null || name.isBlank() ? null : name,
                    used.todaySpentYuan(), used.weekSpentYuan());
        }).toList();
        return new Status(properties.isEnabled(), missing.isEmpty(), missing, keys.isSet(), keys.masked(),
                properties.getBaseUrl(), properties.getModel(), snapshot.spentYuan(), properties.getWeeklyBudgetYuan(),
                byStreamer, snapshot.acknowledgement(),
                ACKNOWLEDGEMENT_VERSION, ACKNOWLEDGEMENT_TEXT.replace("{接口地址}", properties.getBaseUrl()));
    }

    /** 主播的可用状态和剩余额度，不发送模型请求。 */
    public synchronized BudgetStatus budgetStatus(String platform, long uid) {
        var reasons = new ArrayList<>(unavailable().stream().map(AssistantException::reason).toList());
        var quota = usage.quota(new Streamer(platform, uid), properties);
        var exhausted = quota == null ? null : quota.exhausted();
        if (exhausted != null) reasons.add(exhausted.reason);
        var streamerQuota = quota == null ? null : new StreamerQuota(quota.daily().percent(), quota.weekly().percent());
        var state = new State(properties.isEnabled(), reasons.isEmpty(), List.copyOf(reasons), streamerQuota);
        return new BudgetStatus(state, quota == null ? null : quota.spentYuan(),
                properties.getWeeklyBudgetYuan(), quota == null ? null : quota.notice());
    }

    public State state(String platform, long uid) {
        return budgetStatus(platform, uid).state();
    }

    public synchronized void acknowledge(String actor) { usage.acknowledge(actor, ACKNOWLEDGEMENT_VERSION); }

    public synchronized void deleteKey() { keys.clear(); }

    /** 菜单与命令接收只看总开关和密钥，额度与配置者确认由提问时检查。 */
    public boolean isConfigured() { return properties.isEnabled() && keys.isSet(); }

    /** 管理员的连接诊断只发送固定短句，不查额度，花费计入每周总额。 */
    public void validateAndSaveKey(String account, String candidate) {
        AssistantKeyStore.validate(candidate);
        usage.requireReadable();
        keys.prepareForSave();
        probe(candidate);
        keys.save(candidate);
    }

    /** 使用当前设置做连接诊断，同样要求开启、设置密钥和确认。 */
    public void testSettings(String account) {
        requireAvailable();
        probe(null);
    }

    public synchronized Question beginQuestion(String platform, long uid) {
        requireAvailable();
        var streamer = new Streamer(platform, uid);
        usage.checkBudget(streamer, properties);
        return new Question(streamer);
    }

    public final class Question implements AutoCloseable {
        private final Streamer streamer;
        private String sentEndpoint;
        private boolean closed;
        private boolean inFlight;
        private BigDecimal total = BigDecimal.ZERO;
        private int modelCalls;

        private Question(Streamer streamer) {
            this.streamer = streamer;
        }

        public Response complete(Request request) { return complete(request, Duration.ofSeconds(90)); }

        /** 调用方把整问的剩余时间传入，网络等待不能越过整问截止时刻。 */
        public Response complete(Request request, Duration timeout) {
            return complete(request, timeout, CallMode.ANSWER);
        }

        public Response completeAnalysis(Request request, Duration timeout) {
            return complete(request, timeout, CallMode.ANALYSIS);
        }

        /** 难度判断与回答共用一问的用量记录。 */
        public Response classifyDifficulty(Request request) {
            return complete(request, Duration.ofSeconds(5), CallMode.CLASSIFY);
        }

        private Response complete(Request request, Duration timeout, CallMode mode) {
            if (timeout == null || timeout.compareTo(Duration.ofMillis(1)) < 0)
                throw new AssistantException("timeout", "智能助手本次提问已超时，请问得具体一点");
            synchronized (AssistantClient.this) {
                if (closed) throw new AssistantException("这一问已经结束");
                if (inFlight) throw new AssistantException("这一问正在请求大模型服务，请等待返回");
                requireAvailable();
                validate(request);
                usage.checkBudget(streamer, properties);
                inFlight = true;
            }
            try {
                return send(request, null, this, timeout, mode);
            } finally {
                synchronized (AssistantClient.this) { inFlight = false; }
            }
        }

        public boolean hasSent() { return sentEndpoint() != null; }

        public String sentEndpoint() { synchronized (AssistantClient.this) { return sentEndpoint; } }

        public int modelCalls() { synchronized (AssistantClient.this) { return modelCalls; } }

        public BigDecimal totalCostYuan() { synchronized (AssistantClient.this) { return total; } }

        @Override public void close() { synchronized (AssistantClient.this) { closed = true; } }
    }

    private void probe(String candidate) {
        if (candidate == null && !keys.isSet()) throw new AssistantException("no_key", "尚未设置智能助手模型密钥");
        usage.requireReadable();
        send(new Request(List.of(JSONObject.of("role", "user", "content", "回复好")), List.of(), "none", 0),
                candidate, null, Duration.ofSeconds(20), CallMode.PROBE);
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

    private Response send(Request request, String candidate, Question question, Duration timeout, CallMode mode) {
        long startedAt = System.nanoTime();
        String baseUrl = properties.getBaseUrl();
        boolean responses = "responses".equals(properties.getApiFormat());
        boolean shortRequest = mode == CallMode.PROBE || mode == CallMode.CLASSIFY;
        String model = properties.getModel();
        if (mode == CallMode.CLASSIFY && !properties.getDifficultyModel().isBlank())
            model = properties.getDifficultyModel();
        String thinking = switch (mode) {
            case ANSWER -> properties.getThinking();
            case ANALYSIS -> thinkingForHardQuestion();
            case CLASSIFY, PROBE -> responses ? "low" : "off";
        };
        int maxTokens = switch (mode) {
            case CLASSIFY -> 256;
            case PROBE -> Math.min(8, properties.getAnswerLimit());
            default -> properties.getAnswerLimit();
        };
        String endpoint = baseUrl.replaceAll("/+$", "") + (responses ? "/responses" : "/chat/completions");
        try {
            if (!responses) {
                JSONObject body = chatBody(request, maxTokens, baseUrl, model, thinking);
                return exchange(endpoint, body, false, candidate, question, timeout, mode).response();
            }
            int allowance = reasoningAllowance(thinking);
            long maxOutput = switch (mode) {
                case CLASSIFY -> 1024;
                case PROBE -> 256;
                default -> (long) maxTokens + allowance;
            };
            JSONObject body = responsesBody(request, maxOutput, model, thinking);
            Attempt first = exchange(endpoint, body, true, candidate, question, timeout, mode);
            if (shortRequest || !first.outputLimitReached()) return first.response();

            usage.checkBudget(question.streamer, properties);
            Duration remaining = timeout.minusNanos(System.nanoTime() - startedAt);
            if (remaining.compareTo(Duration.ofMillis(1)) < 0) throw outputLimitFailure();
            body.put(OUTPUT_LIMIT, maxOutput + allowance);
            Attempt retry = exchange(endpoint, body, true, candidate, question, remaining, mode);
            if (retry.outputLimitReached()) throw outputLimitFailure();
            Response response = retry.response();
            Usage initial = first.response().usage();
            Usage retried = response.usage();
            Usage combined = new Usage(Math.addExact(initial.cachedInputTokens(), retried.cachedInputTokens()),
                    Math.addExact(initial.uncachedInputTokens(), retried.uncachedInputTokens()),
                    Math.addExact(initial.outputTokens(), retried.outputTokens()));
            return new Response(response.content(), response.reasoningContent(), response.toolCalls(), combined,
                    first.response().costYuan().add(response.costYuan()), response.continuation());
        } catch (AssistantException error) { throw error; }
        catch (RuntimeException error) { throw incompleteResponse(); }
    }

    private static AssistantException incompleteResponse() {
        return new AssistantException("大模型服务返回的数据不完整，请稍后再试");
    }

    private static AssistantException outputLimitFailure() {
        return new AssistantException(OUTPUT_LIMIT, "大模型服务达到输出上限，未能生成回答，请稍后再试");
    }

    private String thinkingForHardQuestion() {
        String normal = properties.getThinking();
        String hard = properties.getHardThinking();
        return THINKING_LEVELS.indexOf(hard) > THINKING_LEVELS.indexOf(normal) ? hard : normal;
    }

    private static int reasoningAllowance(String thinking) {
        return switch (thinking) {
            case "medium" -> 8000;
            case "high" -> 16000;
            case "max" -> 32000;
            // 使用模型默认强度时，留出与低档相同的空间。
            default -> 4000;
        };
    }

    private JSONObject chatBody(Request request, int maxTokens, String baseUrl, String model, String thinking) {
        JSONObject body = JSONObject.of("model", model, "messages", request.messages(),
                "tools", request.tools() == null ? List.of() : request.tools(),
                "tool_choice", request.toolChoice() == null ? "auto" : request.toolChoice(),
                "max_tokens", maxTokens, "temperature", request.temperature(), "stream", false);
        if ("api.deepseek.com".equalsIgnoreCase(URI.create(baseUrl).getHost())) {
            boolean enabled = !"off".equals(thinking);
            body.put("thinking", JSONObject.of("type", enabled ? "enabled" : "disabled"));
            if (enabled) {
                String effort = "medium".equals(thinking) ? "low" : thinking;
                body.put("reasoning_effort", effort);
                body.put("max_tokens", (long) maxTokens + reasoningAllowance(effort));
            }
        }
        return body;
    }

    private JSONObject responsesBody(Request request, long maxOutput, String model, String thinking) {
        var input = new ArrayList<JSONObject>();
        for (JSONObject item : request.messages()) {
            if (item.containsKey("type")) {
                input.add(item);
                continue;
            }
            input.add(switch (Objects.requireNonNullElse(item.getString("role"), "")) {
                case "system" -> JSONObject.of("role", "developer", "content", item.get("content"));
                case "tool" -> JSONObject.of("type", "function_call_output", "call_id", item.get("tool_call_id"),
                        "output", item.get("content"));
                default -> item;
            });
        }
        var tools = new ArrayList<JSONObject>();
        if (request.tools() != null) for (JSONObject tool : request.tools()) {
            var definition = JSONObject.of("type", "function");
            definition.putAll(tool.getJSONObject("function"));
            definition.put("strict", false);
            tools.add(definition);
        }
        Object choice = request.toolChoice() == null ? "auto" : request.toolChoice();
        if (choice instanceof Map<?, ?> named && "function".equals(named.get("type"))
                && named.get("function") instanceof Map<?, ?> function) {
            choice = JSONObject.of("type", "function", "name", function.get("name"));
        }
        JSONObject body = JSONObject.of("model", model, "input", input, "tools", tools,
                "tool_choice", choice, OUTPUT_LIMIT, maxOutput, "store", false,
                "include", List.of("reasoning.encrypted_content"));
        if (!"off".equals(thinking)) body.put("reasoning", JSONObject.of("effort", thinking));
        return body;
    }

    private Attempt exchange(String endpoint, JSONObject body, boolean responses, String candidate, Question question,
                             Duration timeout, CallMode mode) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (candidate == null) keys.authorize(headers); else headers.setBearerAuth(candidate);
        String secret = headers.getFirst(HttpHeaders.AUTHORIZATION).substring(7);
        try {
            RestTemplate template = templates.apply(timeout);
            String serialized = responses ? body.toJSONString(JSONWriter.Feature.WriteNulls) : body.toJSONString();
            URI uri = responses ? URI.create(endpoint) : template.getUriTemplateHandler().expand(endpoint);
            if (question != null) {
                synchronized (this) {
                    question.sentEndpoint = uri.toString();
                    question.modelCalls++;
                }
            }
            // 用字节发送，避免 HTTP 客户端诊断日志输出包含思考内容的请求正文。
            var received = template.exchange(uri, HttpMethod.POST,
                    new HttpEntity<>(serialized.getBytes(StandardCharsets.UTF_8), headers), byte[].class);
            byte[] bytes = received.getBody();
            if (bytes == null) throw new AssistantException("大模型服务返回了空响应");
            JSONObject json = JSON.parseObject(new String(bytes, StandardCharsets.UTF_8));
            Usage tokens = parseUsage(json.getJSONObject("usage"), responses);
            BigDecimal cost = cost(tokens);
            if (question != null) {
                synchronized (this) { question.total = question.total.add(cost); }
            }
            usage.addCost(question == null ? null : question.streamer, cost);
            if (responses) return parseResponses(json, secret, tokens, cost, mode, received.getStatusCode().value());
            return new Attempt(parseChat(json, secret, tokens, cost, mode), false);
        } catch (HttpStatusCodeException error) {
            throw providerFailure(error, responses, secret);
        } catch (ResourceAccessException error) {
            throw new AssistantException("network", "连接大模型服务超时或网络不可用，请稍后再试");
        } catch (RestClientException error) {
            throw new AssistantException("大模型服务出错，请稍后再试");
        } finally { headers.remove(HttpHeaders.AUTHORIZATION); }
    }

    private static Response parseChat(JSONObject json, String secret, Usage tokens, BigDecimal cost, CallMode mode) {
        JSONObject choice = json.getJSONArray("choices").getJSONObject(0);
        if (mode == CallMode.CLASSIFY && !"stop".equals(choice.getString("finish_reason"))) throw incompleteResponse();
        JSONObject message = choice.getJSONObject("message");
        // 服务端即使回显鉴权内容，也不能从公开结果中带回密钥。
        redact(message, secret);
        JSONArray calls = message.getJSONArray("tool_calls");
        List<JSONObject> tools = calls == null ? List.of() : calls.toJavaList(JSONObject.class);
        return new Response(message.getString("content"), message.getString("reasoning_content"), List.copyOf(tools), tokens, cost);
    }

    private static Attempt parseResponses(JSONObject json, String secret, Usage tokens, BigDecimal cost,
                                          CallMode mode, int statusCode) {
        String status = json.getString("status");
        if (mode == CallMode.CLASSIFY && !"completed".equals(status)) throw incompleteResponse();
        JSONArray output = json.getJSONArray("output");
        redact(output, secret);
        var text = new StringBuilder();
        var calls = new ArrayList<JSONObject>();
        var continuation = new ArrayList<JSONObject>();
        for (int i = 0; i < output.size(); i++) {
            JSONObject item = output.getJSONObject(i);
            continuation.add(item);
            switch (Objects.requireNonNullElse(item.getString("type"), "")) {
                case "message" -> {
                    for (Object part : item.getJSONArray("content")) {
                        if (part instanceof JSONObject content && "output_text".equals(content.getString("type"))
                                && content.get("text") instanceof String segment) text.append(segment);
                    }
                }
                case "function_call" -> {
                    String id = item.getString("call_id");
                    if (id == null || id.isBlank()) throw new AssistantException("大模型服务返回的工具调用缺少 call_id");
                    calls.add(JSONObject.of("id", id, "type", "function", "function", JSONObject.of(
                            "name", item.get("name"), "arguments", item.get("arguments"))));
                }
            }
        }
        boolean incomplete = "incomplete".equals(status);
        JSONObject details = json.getJSONObject("incomplete_details");
        boolean outputLimitReached = incomplete && details != null && OUTPUT_LIMIT.equals(details.getString("reason"));
        if (mode == CallMode.PROBE && incomplete && (statusCode != 200 || !outputLimitReached)) {
            throw incompleteResponse();
        }
        String content = text.toString();
        var response = new Response(content, null, List.copyOf(calls), tokens, cost, List.copyOf(continuation));
        return new Attempt(response, outputLimitReached && content.isBlank() && calls.isEmpty());
    }

    private static AssistantException providerFailure(HttpStatusCodeException error, boolean responses, String secret) {
        var statusCode = error.getStatusCode();
        int status = statusCode.value();
        boolean rejected = statusCode.is4xxClientError();
        AssistantException.ProviderError details = responses || rejected
                ? logProviderError(status, error.getResponseBodyAsString(), secret) : null;
        if (rejected && details == null) LOG.warn("大模型服务请求失败 status={}", status);
        return switch (status) {
            case 401, 403 -> new AssistantException("key_rejected", "模型密钥不正确或没有访问权限", details);
            case 402 -> new AssistantException("provider_balance", "模型服务余额不足，请检查服务商账户", details);
            case 429 -> new AssistantException("provider_busy", "模型服务额度不足或请求过多，请稍后再试", details);
            default -> new AssistantException("provider_error", rejected
                    ? "模型服务拒绝了这次请求。助手设置可能有误，例如模型名称或接口地址。请管理员查看日志。"
                    : "大模型服务出错，请稍后再试", details);
        };
    }

    private static AssistantException.ProviderError logProviderError(int status, String body, String secret) {
        try {
            JSONObject error = JSON.parseObject(body).getJSONObject("error");
            if (error == null) return null;
            var details = new AssistantException.ProviderError(safeErrorField(error.getString("type"), secret),
                    safeErrorField(error.getString("code"), secret), safeErrorField(error.getString("param"), secret));
            LOG.warn("大模型服务请求失败 status={} type={} code={} param={} message={}",
                    status, details.type(), details.code(), details.param(), safeErrorMessage(error.get("message"), secret));
            return details;
        } catch (RuntimeException ignored) {
            // 错误正文解析失败时保留固定提示。
            return null;
        }
    }

    private static String safeErrorField(String field, String secret) {
        if (field == null) return null;
        return !field.contains(secret) && field.matches("[A-Za-z0-9_./\\[\\]-]{1,128}") ? field : ERROR_MASK;
    }

    private static String safeErrorMessage(Object field, String secret) {
        if (!(field instanceof String message)) return null;
        int end = errorMessageEnd(message);
        // 从原文查找密钥，避免日志截断在密钥中间时露出前半段。
        var masked = new StringBuilder(end);
        int start = 0;
        for (int key = message.indexOf(secret); key >= 0 && key < end; key = message.indexOf(secret, start)) {
            masked.append(message, start, key).append(ERROR_MASK);
            start = key + secret.length();
        }
        if (start < end) masked.append(message, start, end);
        String singleLine = masked.toString().replaceAll("\\R", " ");
        return singleLine.substring(0, errorMessageEnd(singleLine));
    }

    private static int errorMessageEnd(String text) {
        return text.offsetByCodePoints(0, Math.min(300, text.codePointCount(0, text.length())));
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

    private static Usage parseUsage(JSONObject json, boolean responses) {
        if (json == null) throw new AssistantException("大模型服务未返回用量，无法计算花费");
        if (responses) return cachedUsage(json, "input_tokens", "input_tokens_details", count(json, "output_tokens"));
        long output = count(json, "completion_tokens");
        JSONObject completionDetails = json.getJSONObject("completion_tokens_details");
        if (completionDetails != null && completionDetails.containsKey("reasoning_tokens")) {
            long reasoning = count(completionDetails, "reasoning_tokens");
            // completion_tokens 通常已含思考 token；只有思考数大于它时才相加，避免重复计费。
            if (reasoning > output) output = Math.addExact(output, reasoning);
        }
        if (json.containsKey("prompt_cache_hit_tokens") && json.containsKey("prompt_cache_miss_tokens")) {
            return new Usage(count(json, "prompt_cache_hit_tokens"), count(json, "prompt_cache_miss_tokens"), output);
        }
        return cachedUsage(json, "prompt_tokens", "prompt_tokens_details", output);
    }

    private static Usage cachedUsage(JSONObject json, String inputField, String detailsField, long output) {
        long input = count(json, inputField);
        JSONObject details = json.getJSONObject(detailsField);
        long hit = details != null && details.containsKey("cached_tokens") ? count(details, "cached_tokens") : 0;
        if (hit > input) throw new AssistantException("大模型服务返回的用量不正确");
        return new Usage(hit, input - hit, output);
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
