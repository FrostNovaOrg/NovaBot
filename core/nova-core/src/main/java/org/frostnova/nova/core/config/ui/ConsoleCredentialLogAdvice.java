package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ConsoleCredentialLogAdvice extends RequestBodyAdviceAdapter {
    private final ConfigurationMetadataService metadata;

    public ConsoleCredentialLogAdvice(ConfigurationMetadataService metadata) {
        this.metadata = metadata;
    }

    @Override
    public boolean supports(MethodParameter parameter, Type type, Class<? extends HttpMessageConverter<?>> converter) {
        return parameter.getParameterType() == JSONObject.class || parameter.getParameterType() == Map.class;
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage input, MethodParameter parameter, Type type,
                                Class<? extends HttpMessageConverter<?>> converter) {
        var scope = ConsoleCredentialLogFilter.currentRequest();
        if (scope == null || !(body instanceof Map<?, ?> values)) {
            return body;
        }

        Map<String, String> types = new LinkedHashMap<>();
        if (scope.path().equals("/config/api/values")) {
            metadata.getKnownTypes().forEach((name, value) -> types.put(ConfigUiAuthService.canonicalName(name), value));
        }
        Object protectedBody = display(values, scope, types);
        if (protectedBody == body) {
            return body;
        }

        // 格式化发生在控制器调用之前，且调试日志会截断结果。让格式化只看到打码后的视图。
        @SuppressWarnings("unchecked")
        Map<String, Object> original = (Map<String, Object>) values;
        @SuppressWarnings("unchecked")
        Map<String, Object> display = (Map<String, Object>) protectedBody;
        if (body instanceof JSONObject) {
            return new JSONObject(original) {
                @Override
                public String toString() {
                    return new JSONObject(display).toJSONString();
                }
            };
        }
        return new LinkedHashMap<>(original) {
            @Override
            public String toString() {
                return display.toString();
            }
        };
    }

    private static Object display(Object value, ConsoleCredentialLogFilter.RequestCredentials scope,
                                  Map<String, String> types) {
        boolean changed = false;
        if (value instanceof Map<?, ?> fields) {
            var copy = new LinkedHashMap<String, Object>();
            for (var entry : fields.entrySet()) {
                String name = (String) entry.getKey();
                Object field = entry.getValue();
                Object rendered;
                String type = types.get(ConfigUiAuthService.canonicalName(name));
                if (field != null && !(field instanceof Boolean) && scope.isSensitive(name, type)) {
                    scope.addField(name, type, field);
                    rendered = SensitiveFields.MASK;
                } else {
                    rendered = display(field, scope, Map.of());
                }
                copy.put(name, rendered);
                changed |= rendered != field;
            }
            return changed ? copy : value;
        }
        if (value instanceof Iterable<?> items) {
            var copy = new ArrayList<>();
            for (Object item : items) {
                Object rendered = display(item, scope, Map.of());
                copy.add(rendered);
                changed |= rendered != item;
            }
            return changed ? copy : value;
        }
        return value;
    }
}
