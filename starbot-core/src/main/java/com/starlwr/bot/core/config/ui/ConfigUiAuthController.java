package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.config.ui.auth.ConfigUiAuthService;
import com.starlwr.bot.core.config.ui.auth.ConfigUiSession;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * 配置界面登录接口
 * <p>
 * 只在配置了登录口令时才真正起作用。未配置时 {@code /state} 会如实回报「未启用」，
 * 前端据此不显示登录页。
 */
@Slf4j
@RestController
@RequestMapping(ConfigUiController.BASE_PATH + "/api/auth")
@ConditionalOnProperty(name = "starbot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class ConfigUiAuthController {
    private final ConfigUiAuthService authService;

    private final StarBotCoreProperties.ConfigUi.Auth properties;

    public ConfigUiAuthController(ConfigUiAuthService authService, StarBotCoreProperties properties) {
        this.authService = authService;
        this.properties = properties.getConfigUi().getAuth();
    }

    /**
     * 查询登录状态
     * <p>
     * 前端每次加载都要问一次：要不要登录、有没有登录、要不要二次验证码。
     * @return 登录状态
     */
    @GetMapping("/state")
    public JSONObject state(HttpServletRequest request) {
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("enabled", authService.isEnabled());
        result.put("totpRequired", authService.totpRequired());

        Optional<ConfigUiSession> session = authService.isEnabled()
                ? authService.validate(sessionId(request))
                : Optional.empty();

        result.put("authenticated", session.isPresent());
        // CSRF 令牌只发给已经持有该会话的人，它本身不是秘密，但发给未登录者没有任何意义
        session.ifPresent(value -> result.put("csrfToken", value.getCsrfToken()));

        return result;
    }

    /**
     * 登录
     * @param body 请求体，含 password 与可选的 code
     * @return 登录结果，成功时下发会话 Cookie 与 CSRF 令牌
     */
    @PostMapping("/login")
    public ResponseEntity<JSONObject> login(@RequestBody JSONObject body, HttpServletRequest request) {
        JSONObject result = new JSONObject();

        if (!authService.isEnabled()) {
            result.put("success", false);
            result.put("message", "未启用口令登录");
            return ResponseEntity.badRequest().body(result);
        }

        char[] password = Optional.ofNullable(body.getString("password")).orElse("").toCharArray();
        try {
            ConfigUiAuthService.LoginResult outcome = authService.login(password, body.getString("code"), request.getRemoteAddr());

            if (!outcome.success()) {
                result.put("success", false);
                result.put("message", outcome.message());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(result);
            }

            result.put("success", true);
            result.put("csrfToken", outcome.session().getCsrfToken());

            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, sessionCookie(outcome.session().getId(), request).toString())
                    .body(result);
        } finally {
            // 明文口令用完即抹，不留在堆里等垃圾回收
            Arrays.fill(password, '\0');
        }
    }

    /**
     * 注销
     * @param all 是否注销全部设备上的会话
     */
    @PostMapping("/logout")
    public ResponseEntity<JSONObject> logout(@RequestParam(defaultValue = "false") boolean all, HttpServletRequest request) {
        JSONObject result = new JSONObject();
        result.put("success", true);

        if (all) {
            result.put("count", authService.logoutAll());
        } else {
            authService.logout(sessionId(request));
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expiredCookie(request).toString())
                .body(result);
    }

    private String sessionId(HttpServletRequest request) {
        return Optional.ofNullable(request.getCookies())
                .flatMap(cookies -> Arrays.stream(cookies)
                        .filter(c -> ConfigUiSecurityFilter.SESSION_COOKIE.equals(c.getName()))
                        .findFirst())
                .map(jakarta.servlet.http.Cookie::getValue)
                .orElse(null);
    }

    /**
     * 构造会话 Cookie
     * <p>
     * {@code HttpOnly} 挡住页面脚本读取，{@code SameSite=Strict} 让跨站请求根本带不上它。
     * <p>
     * {@code Secure} 跟随当前连接是否为 https：面板部署在反向代理之后时，
     * 需要配置 {@code server.forward-headers-strategy}，否则这里看到的永远是明文连接，
     * Cookie 就不会带上 {@code Secure}。
     */
    private ResponseCookie sessionCookie(String value, HttpServletRequest request) {
        return ResponseCookie.from(ConfigUiSecurityFilter.SESSION_COOKIE, value)
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Strict")
                .path(ConfigUiController.BASE_PATH)
                .maxAge(Duration.ofHours(Math.max(1, properties.getSessionHours())))
                .build();
    }

    private ResponseCookie expiredCookie(HttpServletRequest request) {
        return ResponseCookie.from(ConfigUiSecurityFilter.SESSION_COOKIE, "")
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Strict")
                .path(ConfigUiController.BASE_PATH)
                .maxAge(0)
                .build();
    }
}
