package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.config.ui.auth.ConfigUiStreamerId;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.datasource.DataSourceServiceRegistry;
import org.frostnova.nova.core.service.DatasourceChannelStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Authorize the handler Spring actually matched, including decoded request paths. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class ConfigUiApiInterceptor implements HandlerInterceptor, WebMvcConfigurer {
    public static final String MEMBER_DENIED = "成员不能使用这一项";
    private final ConfigUiAuthService auth;
    private final NovaCoreProperties properties;
    private final DataSourceServiceRegistry platforms;

    public ConfigUiApiInterceptor(ConfigUiAuthService auth) {
        this(auth, null, new DataSourceServiceRegistry(java.util.List.of()));
    }

    @Autowired
    public ConfigUiApiInterceptor(ConfigUiAuthService auth, NovaCoreProperties properties, DataSourceServiceRegistry platforms) {
        this.auth = auth;
        this.properties = properties;
        this.platforms = platforms;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = ConfigUiPermissions.pattern(request);
        if (!path.equals(ConfigUiController.BASE_PATH) && !path.startsWith(ConfigUiController.BASE_PATH + "/")) return true;
        request.setAttribute(ConfigUiAccess.PLATFORMS, platforms);
        var rule = ConfigUiPermissions.rule(request);
        if (auth.isEnabled() && ConfigUiSecurityFilter.PUBLIC_API.contains(path)
                && rule.access() == ConfigUiPermissions.Access.EVERYONE) return true;
        if (!(request.getAttribute(ConfigUiAccess.ACCOUNT) instanceof ConfigUiAccount account)) {
            return reject(response, 401, "尚未登录");
        }
        if (account.role() != ConfigUiAccount.Role.MEMBER) return true;
        if (rule.access() == ConfigUiPermissions.Access.ADMIN) {
            return reject(response, 403, MEMBER_DENIED);
        }
        if (rule.access() != ConfigUiPermissions.Access.STREAMER) return true;
        String platform;
        Long uid;
        if (rule.subject() == ConfigUiPermissions.Subject.PATH) {
            @SuppressWarnings("unchecked")
            var variables = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
            platform = variables.get("platform");
            // Datasource handlers read strings; the other handlers bind uid as a Long using Spring conversion.
            uid = path.startsWith(ConfigUiController.BASE_PATH + "/api/datasource/")
                    ? ConfigUiStreamerId.positiveDecimal(variables.get("uid")) : mvcLong(variables.get("uid"));
        } else if (rule.subject() == ConfigUiPermissions.Subject.QUERY) {
            platform = mvcParameter(request, "platform", String.class);
            uid = mvcParameter(request, "uid", Long.class);
        } else return true; // Body subjects are checked after HTTP message conversion.
        if (!ConfigUiStreamerId.owns(account, platform, uid, platforms.platforms())) {
            return reject(response, 404, ConfigUiMemberBodyAdvice.MISSING_STREAMER);
        }
        if (path.equals(ConfigUiController.BASE_PATH + "/api/weekly-report/{platform}/{uid}/trial")
                && !ownTarget(platform, uid, request)) {
            return reject(response, 403, "只能试发到这位主播自己的通道");
        }
        return true;
    }

    private static Long mvcLong(String raw) {
        try { return DefaultConversionService.getSharedInstance().convert(raw, Long.class); }
        catch (RuntimeException malformed) { return null; }
    }

    private static <T> T mvcParameter(HttpServletRequest request, String name, Class<T> type) {
        String[] values = request.getParameterValues(name);
        Object raw = values == null ? null : values.length == 1 ? values[0] : values;
        try { return DefaultConversionService.getSharedInstance().convert(raw, type); }
        catch (RuntimeException malformed) { return null; }
    }

    /** Read configured targets, including disabled streamers and targets omitted by the live datasource. */
    private boolean ownTarget(String platform, Long uid, HttpServletRequest request) {
        synchronized (DatasourceChannelStore.LOCK) {
            if (properties == null) return false;
            try {
                String content = Files.readString(Path.of(properties.getDatasource().getJsonPath()));
                if (content.startsWith("\uFEFF")) content = content.substring(1);
                JSONArray users = JSONArray.parseArray(content.isBlank() ? "[]" : content);
                String pushPlatform = mvcParameter(request, "pushPlatform", String.class);
                Long num = mvcParameter(request, "num", Long.class);
                Integer code = mvcParameter(request, "type", Integer.class);
                PushTargetType type = code == null ? null : PushTargetType.fromCode(code);
                if (pushPlatform == null || num == null || type == null || users == null) return false;
                for (Object entry : users) {
                    var user = (JSONObject) entry;
                    if (!Objects.equals(platform, user.getString("platform")) || !Objects.equals(uid, user.getLong("uid"))) continue;
                    JSONArray targets = user.getJSONArray("targets");
                    if (targets == null) continue;
                    for (Object value : targets) {
                        var target = (JSONObject) value;
                        if (Objects.equals(pushPlatform, target.getString("platform"))
                                && Objects.equals(type.getCode(), target.getInteger("type"))
                                && Objects.equals(num, target.getLong("num"))) return true;
                    }
                }
            } catch (java.io.IOException | RuntimeException malformed) {
                return false;
            }
            return false;
        }
    }

    private boolean reject(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        JSONObject result = new JSONObject();
        result.put("success", false);
        result.put("message", message);
        response.getWriter().write(result.toJSONString());
        return false;
    }
}
