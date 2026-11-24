package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.*;
import java.lang.reflect.Type;
import java.util.*;

/** 仅投影对象身份；请求正文、设置值、错误回包都不进入操作记录。 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ConfigUiOperationAdvice extends RequestBodyAdviceAdapter implements ResponseBodyAdvice<Object> {
    @Override
    public boolean supports(MethodParameter parameter, Type type, Class<? extends HttpMessageConverter<?>> converter) {
        return true;
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage input, MethodParameter parameter, Type type,
                                Class<? extends HttpMessageConverter<?>> converter) {
        var request = ConfigUiAccess.request();
        if (request != null && request.getAttribute(ConfigUiOperationFilter.TARGET) instanceof JSONObject target) {
            Map<?, ?> values = body instanceof Map<?, ?> map ? map
                    : body instanceof ConfigUiAccountController.StreamerChange change
                    ? Collections.singletonMap("streamers", change.streamers()) : Map.of();
            String action = ConfigUiOperationCatalog.action(request).name();
            if (action.equals("SETTINGS_SAVE")) {
                target.put("keys", values.keySet().stream().filter(String.class::isInstance)
                        .map(String.class::cast).map(ConfigUiAudit::safe).sorted().toList());
            } else {
                if (action.equals("SUBSCRIPTION_REMOVE")) {
                    Map<Object, Object> session = new LinkedHashMap<>(values);
                    session.put("type", 1);
                    identify(target, session);
                    if ("live".equals(values.get("type")) || "dynamic".equals(values.get("type"))) target.put("subscriptionType", values.get("type"));
                    String userUid = decimal(values.get("userUid"));
                    if (userUid != null) target.put("userUid", userUid);
                } else identify(target, values);
                List<String> commands = new ArrayList<>();
                String command = commandName(values.get("command"));
                if (command != null) commands.add(command);
                if (values.get("commands") instanceof Collection<?> names)
                    for (Object name : names) { String item = commandName(name); if (item != null) commands.add(item); }
                if (!commands.isEmpty()) target.put("commands", commands);
                if (action.equals("TEMPLATE_SAVE") && values.get("className") instanceof String name
                        && name.matches("[a-zA-Z0-9_.$]+")) target.put("template", ConfigUiAudit.safe(name));
                if (action.equals("ACCOUNT_STREAMERS") && values.get("streamers") instanceof Collection<?> streamers)
                    for (Object streamer : streamers) {
                        if (streamer instanceof String id && id.matches("[a-zA-Z0-9_-]+:[0-9]+")) {
                            String[] parts = id.split(":", 2);
                            add(target, "streamers", JSONObject.of("platform", parts[0], "uid", parts[1]));
                        }
                    }
                if (values.get("user") instanceof Map<?, ?> user) identify(target, user);
                if (action.equals("DATASOURCE_SAVE") && values.get("content") instanceof String content) {
                    try {
                        JSONArray users = JSONArray.parseArray(content);
                        if (users != null) for (Object user : users) if (user instanceof Map<?, ?> map) identify(target, map);
                    } catch (RuntimeException ignored) { /* 非法正文交给原接口拒绝。 */ }
                }
            }
        }
        return body;
    }

    static void query(HttpServletRequest request, JSONObject target) {
        // 不枚举所有参数，尤其不读取 token、code、state 或任何登录身份。
        Map<String, Object> values = new LinkedHashMap<>();
        for (String key : List.of("platform", "uid", "pushPlatform", "type", "num", "streamerPlatform", "streamerUid")) {
            String[] candidates = request.getParameterValues(key);
            if (candidates != null && candidates.length == 1) values.put(key, candidates[0]);
        }
        identify(target, values);
        var variables = ConfigUiOperationCatalog.action(request).variables();
        if (ConfigUiOperationCatalog.action(request).name().startsWith("ACCOUNT_") && variables.containsKey("id"))
            target.put("accountId", ConfigUiAudit.safe(variables.get("id")));
        if (ConfigUiOperationCatalog.action(request).name().equals("ALERT_TEST")) {
            String channel = identifier(request.getParameter("channel"));
            if (channel != null) target.put("alertChannel", channel);
        }
    }

    static void identify(JSONObject target, Map<?, ?> values) {
        String platform = identifier(values.get("platform"));
        String uid = decimal(values.get("uid"));
        if (platform != null && uid != null) add(target, "streamers", JSONObject.of("platform", platform, "uid", uid));
        String streamerPlatform = identifier(values.get("streamerPlatform"));
        String streamerUid = decimal(values.get("streamerUid"));
        if (streamerPlatform != null && streamerUid != null) add(target, "streamers", JSONObject.of("platform", streamerPlatform, "uid", streamerUid));
        String num = decimal(values.get("num"));
        String pushPlatform = identifier(values.get("pushPlatform"));
        String channelPlatform = pushPlatform == null ? platform : pushPlatform;
        String channelType = identifier(values.get("type"));
        if (channelPlatform != null && num != null && channelType != null)
            add(target, "channels", JSONObject.of("platform", channelPlatform, "type", channelType, "num", num));
        if (values.get("targets") instanceof Collection<?> targets)
            for (Object channel : targets) if (channel instanceof Map<?, ?> map) identify(target, map);
    }

    private static String identifier(Object value) {
        String text = value instanceof String || value instanceof Number ? value.toString() : null;
        return text != null && text.matches("[a-zA-Z0-9_-]{1,128}") ? ConfigUiAudit.safe(text) : null;
    }

    private static String commandName(Object value) {
        return value instanceof String text && text.matches("[\\p{L}\\p{N}_-]{1,128}") ? ConfigUiAudit.safe(text) : null;
    }

    private static String decimal(Object value) {
        String text = identifier(value);
        return text != null && text.matches("[0-9]+") ? text : null;
    }

    /** 由只持有主播号码的操作补齐实际的平台。 */
    public static void streamer(String platform, Long uid) {
        var request = ConfigUiAccess.request();
        if (request != null && request.getAttribute(ConfigUiOperationFilter.TARGET) instanceof JSONObject target)
            identify(target, Map.of("platform", platform, "uid", uid));
    }

    static void session(String platform, int type, long num) {
        var request = ConfigUiAccess.request();
        if (request != null && request.getAttribute(ConfigUiOperationFilter.TARGET) instanceof JSONObject target)
            identify(target, Map.of("platform", platform, "type", type, "num", num));
    }

    /** 用变更前后对象补齐被移除的主播与通道，只提取发生变化的对象身份。 */
    public static void datasourceChanged(JSONArray before, JSONArray after) {
        var request = ConfigUiAccess.request();
        if (request == null || !(request.getAttribute(ConfigUiOperationFilter.TARGET) instanceof JSONObject target)) return;
        try {
            var changed = ConfigurationValidator.changedUsers(before, after);
            changed.addAll(ConfigurationValidator.changedUsers(after, before));
            Map<String, JSONObject> oldUsers = users(before);
            Map<String, JSONObject> newUsers = users(after);
            target.remove("streamers");
            target.remove("channels");
            for (String id : new TreeSet<>(changed)) {
                JSONObject old = oldUsers.get(id);
                JSONObject next = newUsers.get(id);
                JSONObject user = next == null ? old : next;
                Map<String, Object> identity = new LinkedHashMap<>();
                identity.put("platform", user.get("platform"));
                identity.put("uid", user.get("uid"));
                identify(target, identity);
                JSONArray previous = old == null ? null : old.getJSONArray("targets");
                JSONArray current = next == null ? null : next.getJSONArray("targets");
                changedChannels(target, previous, current);
                changedChannels(target, current, previous);
            }
        } catch (RuntimeException error) {
            org.slf4j.LoggerFactory.getLogger(ConfigUiOperationAdvice.class)
                    .warn("控制台操作对象记录失败，操作结果不受影响");
        }
    }

    private static Map<String, JSONObject> users(JSONArray rows) {
        Map<String, JSONObject> result = new LinkedHashMap<>();
        for (Object row : rows) if (row instanceof JSONObject user)
            result.put(user.getString("platform") + ":" + user.getString("uid"), user);
        return result;
    }

    private static void changedChannels(JSONObject target, JSONArray rows, JSONArray other) {
        if (rows == null) return;
        for (Object row : rows) if ((other == null || !other.contains(row)) && row instanceof Map<?, ?> channel)
            identify(target, channel);
    }

    private static void add(JSONObject target, String key, JSONObject object) {
        JSONArray items = target.getJSONArray(key);
        if (items == null) { items = new JSONArray(); target.put(key, items); }
        if (!items.contains(object)) items.add(object);
    }

    /** 写盘服务返回的实际变更键覆盖提交键，仍不接收任何设置值。 */
    public static void changedKeys(Collection<String> keys) {
        var request = ConfigUiAccess.request();
        if (request != null && request.getAttribute(ConfigUiOperationFilter.TARGET) instanceof JSONObject target) {
            Set<String> changed = new TreeSet<>();
            if (request.getAttribute("nova.console.audit.changed-keys") instanceof Collection<?> previous)
                for (Object key : previous) if (key instanceof String text) changed.add(text);
            keys.stream().map(ConfigUiAudit::safe).forEach(changed::add);
            request.setAttribute("nova.console.audit.changed-keys", changed);
            target.put("keys", List.copyOf(changed));
        }
    }

    private static String refusal(Map<?, ?> values) {
        // 只用回包分类，不复制可能拼接了设置值、令牌或远端错误正文的文案。
        if (values.get("issues") instanceof Collection<?>) return "配置项校验未通过";
        String message = values.get("message") instanceof String text ? text : "";
        if (Set.of(ConfigUiAccess.LOCAL_REQUIRED, ConfigUiApiInterceptor.MEMBER_DENIED,
                ConfigUiMemberBodyAdvice.MISSING_STREAMER, "尚未登录").contains(message)) return message;
        if (message.contains("权限") || message.contains("只能")) return "权限不足";
        if (message.contains("频繁") || message.contains("次数过多") || message.contains("正忙")) return "操作限流或暂时不可用";
        if (message.contains("密码") || message.contains("口令") || message.contains("验证码")) return "登录或身份复核未通过";
        if (message.contains("未找到") || message.contains("不存在") || message.contains("没有")) return "对象不存在或不可用";
        if (message.contains("配置")) return "配置不完整或未通过校验";
        if (message.contains("发送")) return "消息发送失败";
        return "操作未通过校验";
    }

    @Override
    public boolean supports(MethodParameter parameter, Class<? extends HttpMessageConverter<?>> converter) { return true; }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter parameter, MediaType mediaType,
                                  Class<? extends HttpMessageConverter<?>> converter,
                                  ServerHttpRequest input, ServerHttpResponse output) {
        if (input instanceof ServletServerHttpRequest servlet) {
            var request = servlet.getServletRequest();
            if (request.getAttribute(ConfigUiOperationFilter.TARGET) != null) {
                Object result = body;
                if (body instanceof String text && MediaType.APPLICATION_JSON.isCompatibleWith(mediaType)) {
                    try { result = JSONObject.parseObject(text); } catch (RuntimeException ignored) { }
                }
                if (result instanceof Map<?, ?> values && Boolean.FALSE.equals(values.get("success")))
                    if (request.getAttribute(ConfigUiOperationFilter.REASON) == null)
                        request.setAttribute(ConfigUiOperationFilter.REASON, refusal(values));
            }
        }
        return body;
    }
}
