package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.util.IpMatcher;
import com.starlwr.bot.core.util.SecureToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * 配置界面安全过滤器
 * <p>
 * 配置界面可以修改推送目标、查看运行状态，权限高于推送接口，因此同样要求来源 IP 白名单与令牌校验。
 * 令牌可通过地址栏参数、Cookie 或请求头携带：首次以地址栏参数访问成功后会写入 Cookie，
 * 之后浏览器内的操作无需再带令牌，同时避免令牌长期停留在地址栏中。
 */
@Slf4j
public class ConfigUiSecurityFilter extends OncePerRequestFilter {
    /**
     * 保存令牌的 Cookie 名
     */
    private static final String COOKIE_NAME = "starbot_config_token";

    private static final String BEARER_PREFIX = "Bearer ";

    private final StarBotCoreProperties.ConfigUi properties;

    private final String token;

    private final IpMatcher ipMatcher;

    public ConfigUiSecurityFilter(StarBotCoreProperties.ConfigUi properties, String token, IpMatcher ipMatcher) {
        this.properties = properties;
        this.token = token;
        this.ipMatcher = ipMatcher;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain chain)
            throws ServletException, IOException {
        String clientIp = request.getRemoteAddr();

        if (!ipMatcher.matches(clientIp)) {
            log.warn("配置界面拒绝了来自 {} 的访问: 来源 IP 不在白名单内", clientIp);
            reject(request, response, HttpStatus.FORBIDDEN, "来源 IP 不在白名单内");
            return;
        }

        String presented = extractToken(request);
        if (!SecureToken.verify(token, presented)) {
            log.warn("配置界面拒绝了来自 {} 的访问: 令牌校验失败", clientIp);
            reject(request, response, HttpStatus.UNAUTHORIZED, "访问令牌不正确，请使用启动日志中输出的地址访问");
            return;
        }

        // 地址栏参数校验通过后写入 Cookie，后续请求无需再带令牌
        if (request.getParameter("token") != null) {
            Cookie cookie = new Cookie(COOKIE_NAME, token);
            cookie.setHttpOnly(true);
            cookie.setPath("/");
            response.addCookie(cookie);
        }

        chain.doFilter(request, response);
    }

    /**
     * 从请求中提取令牌
     * @param request 请求
     * @return 令牌，不存在时返回 null
     */
    private String extractToken(HttpServletRequest request) {
        String parameter = request.getParameter("token");
        if (parameter != null && !parameter.isBlank()) {
            return parameter.strip();
        }

        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return authorization.substring(BEARER_PREFIX.length()).strip();
        }

        return Optional.ofNullable(request.getCookies())
                .flatMap(cookies -> Arrays.stream(cookies).filter(c -> COOKIE_NAME.equals(c.getName())).findFirst())
                .map(Cookie::getValue)
                .orElse(null);
    }

    /**
     * 输出拒绝响应
     * <p>
     * 浏览器直接访问时返回一段可读的 HTML，接口调用时返回 JSON。
     */
    private void reject(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        if (request.getRequestURI().contains("/api/")) {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);

            JSONObject body = new JSONObject();
            body.put("success", false);
            body.put("message", message);
            response.getWriter().write(body.toJSONString());
            return;
        }

        response.setContentType(MediaType.TEXT_HTML_VALUE);
        response.getWriter().write("""
                <!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>StarBot 配置</title>
                <style>body{font-family:system-ui,-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;
                display:flex;align-items:center;justify-content:center;height:100vh;margin:0;background:#f6f7f9;color:#333}
                div{text-align:center}h1{font-size:20px;margin:0 0 12px}p{color:#888;font-size:14px;margin:0}</style>
                </head><body><div><h1>无法访问配置界面</h1><p>%s</p></div></body></html>
                """.formatted(message));
    }
}
