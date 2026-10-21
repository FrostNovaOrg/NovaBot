package org.frostnova.nova.core.config.ui;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccountStore;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.config.ui.auth.OidcLoginThrottle;
import org.frostnova.nova.core.config.ui.auth.oidc.OidcClient;
import org.frostnova.nova.core.config.ui.auth.oidc.OidcException;
import org.frostnova.nova.core.lang.SecureToken;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.ResponseEntity;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Browser OIDC flow; provider response text and tokens never enter the page. */
@Slf4j
@RestController
@RequestMapping(ConfigUiController.BASE_PATH + "/api")
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class ConfigUiOidcController {
    public static final String COOKIE = "nova_oidc_browser";
    private static final int MAX_PENDING = 1024;
    private final Map<String, Pending> pending = new HashMap<>();
    private final OidcClient client;
    private final ConfigUiAuthService auth;
    private final ConfigUiAccountStore accounts;
    private final OidcLoginThrottle throttle = new OidcLoginThrottle();
    private final NovaCoreProperties.ConfigUi.Agreement agreement;

    public ConfigUiOidcController(OidcClient client, ConfigUiAuthService auth, ConfigUiAccountStore accounts,
                                  NovaCoreProperties properties) {
        this.client = client;
        this.auth = auth;
        this.accounts = accounts;
        this.agreement = properties.getConfigUi().getAgreement();
    }

    @GetMapping("/oidc/login")
    public void login(HttpServletRequest request, HttpServletResponse response) throws IOException {
        start(request, response, null);
    }

    @PostMapping("/accounts/local/bind")
    public void bind(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!auth.oidcEnabled()) { response.setStatus(404); return; }
        var actor = ConfigUiAccess.admin(request);
        ConfigUiAccess.requireFreshLocal(request);
        if (!ConfigUiAccount.LOCAL_ID.equals(actor.id())) {
            response.sendError(403, "只有本地管理员可以绑定身份");
            return;
        }
        start(request, response, ConfigUiAccess.session(request).getId());
    }

    private void start(HttpServletRequest request, HttpServletResponse response, String sessionId) throws IOException {
        if (!auth.oidcEnabled()) { response.setStatus(404); return; }
        if (!acquire(request)) { result(request, response, "busy"); return; }
        try {
            var authorization = client.startLogin(request);
            String correlation = SecureToken.generate();
            synchronized (pending) {
                sweep();
                if (pending.size() >= MAX_PENDING) {
                    client.cancelLogin(authorization.state());
                    result(request, response, "busy");
                    return;
                }
                pending.put(authorization.state(), new Pending(correlation, sessionId, authorization.expiresAt()));
            }
            response.addHeader(HttpHeaders.SET_COOKIE, correlation(correlation, request, 300));
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            if (sessionId == null) {
                response.sendRedirect(authorization.uri().toASCIIString());
            } else {
                JSONObject result = new JSONObject();
                result.put("success", true);
                result.put("authorizationUrl", authorization.uri().toASCIIString());
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(result.toJSONString());
            }
        } catch (OidcException error) {
            result(request, response, errorResult(error));
        } finally {
            throttle.releaseSlot();
        }
    }

    @GetMapping("/oidc/callback")
    public void callback(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!auth.oidcEnabled()) { response.setStatus(404); return; }
        if (!acquire(request)) { result(request, response, "busy"); return; }
        try {
            String state = request.getParameter("state");
            Pending transaction;
            synchronized (pending) {
                sweep();
                transaction = pending.get(state);
                if (transaction == null || !SecureToken.verify(transaction.correlation(), cookie(request))) {
                    result(request, response, "failed");
                    return;
                }
                pending.remove(state);
            }
            response.addHeader(HttpHeaders.SET_COOKIE, correlation("", request, 0));
            if (request.getParameter("error") != null) {
                client.cancelLogin(state);
                result(request, response, switch (request.getParameter("error")) {
                    case "access_denied" -> "denied";
                    case "invalid_client", "unauthorized_client" -> "configuration";
                    case "temporarily_unavailable", "server_error" -> "busy";
                    default -> "failed";
                });
                return;
            }
            if (transaction.sessionId() != null) {
                var initiating = auth.validate(transaction.sessionId()).orElse(null);
                if (!auth.freshLocal(initiating) || !ConfigUiAccount.LOCAL_ID.equals(initiating.getAccountId())) {
                    client.cancelLogin(state);
                    result(request, response, "local_required");
                    return;
                }
            }
            var login = client.completeLogin(state, request.getParameter("code"), request.getParameter("iss"));
            synchronized (accounts) {
                if (transaction.sessionId() != null) {
                    // Recheck after provider I/O; a concurrent logout must invalidate the binding operation.
                    var initiating = auth.validate(transaction.sessionId()).orElse(null);
                    if (!auth.freshLocal(initiating)) {
                        result(request, response, "local_required");
                        return;
                    }
                    try {
                        accounts.bind(login.identity());
                    } catch (IllegalArgumentException error) {
                        result(request, response, "identity_in_use");
                        return;
                    }
                    auth.logoutOidcAccount(ConfigUiAccount.LOCAL_ID);
                    log.info("配置界面账号绑定: 本地管理员 → 本地管理员");
                    result(request, response, "bound");
                    return;
                }
                ConfigUiAccount account = accounts.register(login.identity(), Instant.now());
                if (account.status() == ConfigUiAccount.Status.PENDING) {
                    result(request, response, "pending");
                    return;
                }
                if (account.status() == ConfigUiAccount.Status.DISABLED) {
                    result(request, response, "disabled");
                    return;
                }
                if (account.role() == ConfigUiAccount.Role.MEMBER && ConfigUiAgreement.required(agreement)) {
                    result(request, response, "agreement");
                    return;
                }
                accounts.loggedIn(account.id(), Instant.now());
                var session = auth.issueForOidc(account.id(), request.getRemoteAddr(), login);
                response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(ConfigUiSecurityFilter.SESSION_COOKIE, session.getId())
                        .httpOnly(true).secure(request.isSecure()).sameSite("Strict")
                        .path(ConfigUiPrefix.consolePath(request)).build().toString());
            }
            // Commit the Strict cookie on a same-origin document before the next top-level navigation.
            String target = HtmlUtils.htmlEscape(ConfigUiPrefix.consolePath(request));
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setHeader("Referrer-Policy", "no-referrer");
            response.setHeader("Content-Security-Policy", "default-src 'none'; base-uri 'none'; frame-ancestors 'none'");
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().write("<!doctype html><html lang=\"zh-CN\"><meta charset=\"UTF-8\">"
                    + "<meta http-equiv=\"refresh\" content=\"0;url=" + target + "\"><title>登录成功</title>"
                    + "<a href=\"" + target + "\">进入控制台</a></html>");
        } catch (OidcException error) {
            result(request, response, errorResult(error));
        } catch (IllegalStateException error) {
            result(request, response, "busy");
        } finally {
            throttle.releaseSlot();
        }
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<JSONObject> accessFailure(ResponseStatusException error) {
        JSONObject result = new JSONObject();
        result.put("success", false);
        result.put("message", error.getReason());
        return ResponseEntity.status(error.getStatusCode()).body(result);
    }

    private boolean acquire(HttpServletRequest request) {
        return throttle.tryAcquire(request.getRemoteAddr(), Instant.now());
    }

    private void sweep() {
        pending.entrySet().removeIf(entry -> {
            if (Instant.now().isBefore(entry.getValue().expiresAt())) return false;
            client.cancelLogin(entry.getKey());
            return true;
        });
    }

    private static String cookie(HttpServletRequest request) {
        return request.getCookies() == null ? null : Arrays.stream(request.getCookies())
                .filter(c -> COOKIE.equals(c.getName())).map(Cookie::getValue).findFirst().orElse(null);
    }

    private static String correlation(String value, HttpServletRequest request, int maxAge) {
        return ResponseCookie.from(COOKIE, value).path(ConfigUiPrefix.consolePath(request))
                .httpOnly(true).secure(request.isSecure()).sameSite("Lax").maxAge(Duration.ofSeconds(maxAge)).build().toString();
    }

    private static String errorResult(OidcException error) {
        return switch (error.kind()) {
            case CONFIGURATION -> "configuration";
            case PROVIDER_UNAVAILABLE -> "busy";
            default -> "failed";
        };
    }

    private static void result(HttpServletRequest request, HttpServletResponse response, String result) throws IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.sendRedirect(ConfigUiPrefix.consolePath(request) + "?oidc=" + result);
    }

    private record Pending(String correlation, String sessionId, Instant expiresAt) {
        @Override public String toString() { return "OIDC browser transaction [redacted]"; }
    }
}
