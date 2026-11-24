package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.*;

/** 在权限过滤器之外收口，保证被拒请求和成功请求各写一次。 */
public class ConfigUiOperationFilter extends OncePerRequestFilter {
    static final String TARGET = "nova.console.audit.target";
    static final String ACTION = "nova.console.audit.action";
    static final String REASON = "nova.console.audit.reason";
    private final ConfigUiOperationStore store;
    private final List<ConfigUiOperationCatalog.Entry> pluginEntries;

    public ConfigUiOperationFilter(ConfigUiOperationStore store) {
        this(store, List.of());
    }

    public ConfigUiOperationFilter(ConfigUiOperationStore store, List<ConfigUiOperationProvider> operations) {
        this.store = store;
        this.pluginEntries = ConfigUiOperationCatalog.pluginEntries(operations);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean api = request.getRequestURI().substring(request.getContextPath().length()).startsWith("/config/api/");
        var target = new JSONObject();
        request.setAttribute(TARGET, target);
        if (api) {
            var action = ConfigUiOperationCatalog.find(request, pluginEntries);
            if (action != null) request.setAttribute(ConfigUiOperationCatalog.REGISTERED_ACTION, action);
        }
        boolean failed = true;
        try {
            chain.doFilter(request, response);
            failed = false;
        } finally {
            if (request.getAttribute(ConfigUiOperationCatalog.REGISTERED_ACTION) == null && api) {
                var action = ConfigUiOperationCatalog.find(request, pluginEntries);
                if (action != null) request.setAttribute(ConfigUiOperationCatalog.REGISTERED_ACTION, action);
            }
            var selected = request.getAttribute(ACTION) instanceof ConfigUiOperationCatalog.Action explicit
                    ? explicit : ConfigUiOperationCatalog.action(request);
            boolean rejectedRequest = failed || response.getStatus() >= 400 || request.getAttribute(REASON) != null;
            boolean write = Set.of("POST", "PUT", "PATCH", "DELETE").contains(request.getMethod());
            boolean login = Set.of("LOGIN", "OPERATOR_LOGIN", "OIDC_LOGIN_START", "OIDC_LOGIN", "ACCOUNT_BIND_COMPLETE",
                    "PASSKEY_LOGIN_START", "PASSKEY_LOGIN").contains(selected.name());
            if (login || api && (write || !rejectedRequest && ConfigUiOperationCatalog.operation(request))) {
                try {
                    var action = selected;
                    Object recognized = request.getAttribute(ConfigUiAccess.ACCOUNT);
                    JSONObject actor = recognized instanceof ConfigUiAccount account
                            ? JSONObject.of("id", ConfigUiAudit.safe(account.id()),
                                    "displayName", ConfigUiAudit.safe(account.displayName()), "role", account.role().name())
                            : JSONObject.of("id", null, "displayName", "未识别", "role", null);
                    // 换取登录会话与随后的写操作是两次操作，各自保留结果。
                    if (action.name().equals("OPERATOR_LOGIN") && api
                            && (write || !rejectedRequest && ConfigUiOperationCatalog.operation(request))) {
                        store.append(JSONObject.of("actor", actor, "action", action.name(), "description", action.description(),
                                "target", new JSONObject(), "result", "SUCCESS"));
                        action = ConfigUiOperationCatalog.action(request);
                    }
                    if (action.actionOnly()) {
                        target.clear();
                    } else {
                        ConfigUiOperationAdvice.identify(target, action.variables());
                        ConfigUiOperationAdvice.query(request, target);
                    }
                    boolean rejected = !action.name().equals("OPERATOR_LOGIN")
                            && (failed || response.getStatus() >= 400 || request.getAttribute(REASON) != null);
                    var row = JSONObject.of("actor", actor, "action", action.name(), "description", action.description(),
                            "target", target, "result", rejected ? "REJECTED" : "SUCCESS");
                    if (rejected) row.put("reason", request.getAttribute(REASON) instanceof String reason ? reason
                            : reason(failed ? 500 : response.getStatus()));
                    store.append(row);
                } catch (Exception error) {
                    logger.warn("控制台操作记录生成失败，操作结果不受影响");
                }
            }
        }
    }

    static String reason(int status) {
        return switch (status) {
            case 401 -> "尚未登录或登录校验未通过";
            case 403 -> "权限不足或请求安全校验未通过";
            case 404 -> "对象不存在或不在可访问范围内";
            case 409 -> "对象状态已变化";
            case 429 -> "操作过于频繁";
            default -> status >= 500 ? "操作执行失败" : "操作未通过校验";
        };
    }
}
