package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiStreamerId;
import org.frostnova.nova.core.datasource.DataSourceServiceRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.lang.reflect.Type;

/** Authorize the converted JSON object that the controller will actually read. */
@RestControllerAdvice
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class ConfigUiMemberBodyAdvice extends RequestBodyAdviceAdapter {
    public static final String MISSING_STREAMER = "没有这位主播";
    private final DataSourceServiceRegistry platforms;

    public ConfigUiMemberBodyAdvice(DataSourceServiceRegistry platforms) {
        this.platforms = platforms;
    }

    @Override
    public boolean supports(MethodParameter parameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return JSONObject.class.equals(parameter.getParameterType());
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage input, MethodParameter parameter, Type targetType,
                                Class<? extends HttpMessageConverter<?>> converterType) {
        var request = ConfigUiAccess.request();
        if (request == null || !ConfigUiAccess.member(request)) return body;
        var rule = ConfigUiPermissions.rule(request);
        if (rule.access() != ConfigUiPermissions.Access.STREAMER) return body;
        var account = (ConfigUiAccount) request.getAttribute(ConfigUiAccess.ACCOUNT);
        var json = (JSONObject) body;
        boolean allowed;
        try {
            allowed = switch (rule.subject()) {
                case LOOKUP -> ConfigUiStreamerId.owns(account, json.getString("platform"),
                        ConfigUiStreamerId.positiveDecimal(json.getString("uid") == null ? null : json.getString("uid").trim()), platforms.platforms());
                case SUBSCRIPTION -> ConfigUiStreamerId.ownsUid(account,
                        ConfigUiStreamerId.positiveDecimal(json.get("streamerUid")), platforms.platforms());
                case PREVIEW -> !json.containsKey("streamerPlatform") && !json.containsKey("streamerUid")
                        || ConfigUiStreamerId.owns(account, json.getString("streamerPlatform"), json.getLong("streamerUid"), platforms.platforms());
                default -> true;
            };
        } catch (RuntimeException malformed) {
            allowed = false;
        }
        if (!allowed) throw new MissingStreamer();
        return body;
    }

    public static final class MissingStreamer extends RuntimeException { }

    @ExceptionHandler(MissingStreamer.class)
    public ResponseEntity<String> missingStreamer() {
        return ResponseEntity.status(404).contentType(MediaType.parseMediaType("application/json;charset=UTF-8"))
                .body(JSONObject.of("success", false, "message", MISSING_STREAMER).toJSONString());
    }
}
