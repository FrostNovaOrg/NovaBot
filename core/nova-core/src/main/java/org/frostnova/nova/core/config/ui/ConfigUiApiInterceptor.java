package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Authorize the API Spring actually matched, including decoded request paths. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class ConfigUiApiInterceptor implements HandlerInterceptor, WebMvcConfigurer {
    private final ConfigUiAuthService auth;

    public ConfigUiApiInterceptor(ConfigUiAuthService auth) {
        this.auth = auth;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        Object matched = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (matched == null || !matched.toString().startsWith(ConfigUiController.BASE_PATH + "/api/")) return true;
        String path = matched.toString();
        if (auth.isEnabled() && ConfigUiSecurityFilter.PUBLIC_API.contains(path)) return true;
        if (!(request.getAttribute(ConfigUiAccess.ACCOUNT) instanceof ConfigUiAccount account)) {
            return reject(response, 401, "尚未登录");
        }
        if (account.role() == ConfigUiAccount.Role.MEMBER
                && !path.equals(ConfigUiController.BASE_PATH + "/api/auth/logout")) {
            return reject(response, 403, "成员暂时不能使用这一项");
        }
        return true;
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
