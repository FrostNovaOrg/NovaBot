package com.starlwr.bot.adapter.onebot.security;

import com.starlwr.bot.core.util.IpMatcher;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.adapter.onebot.config.OneBotAdapterPluginProperties;
import com.starlwr.bot.adapter.onebot.enums.ResultCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
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

/**
 * 推送接口安全过滤器
 * <p>
 * 依次执行来源 IP 白名单校验、Token 鉴权与频率限制三道检查，任意一道未通过即中断请求。
 * 仅对已注册的推送接口路径生效，其余路径直接放行，不影响插件自行注册的其他接口。
 */
@Slf4j
public class PushApiSecurityFilter extends OncePerRequestFilter {
    private static final String BEARER_PREFIX = "Bearer ";

    private final OneBotAdapterPluginProperties.Security properties;

    private final PushApiTokenStore tokenStore;

    private final IpMatcher ipMatcher;

    private final RateLimiter rateLimiter;

    public PushApiSecurityFilter(OneBotAdapterPluginProperties.Security properties, PushApiTokenStore tokenStore, IpMatcher ipMatcher, RateLimiter rateLimiter) {
        this.properties = properties;
        this.tokenStore = tokenStore;
        this.ipMatcher = ipMatcher;
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();

        // 非推送接口路径不做拦截, 交由后续处理链自行响应
        if (!tokenStore.isProtected(path)) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = resolveClientIp(request);

        if (!ipMatcher.matches(clientIp)) {
            audit(path, clientIp, "来源 IP 不在白名单内");
            reject(response, HttpStatus.FORBIDDEN, ResultCode.FORBIDDEN_ADDRESS);
            return;
        }

        if (!tokenStore.verify(path, extractToken(request))) {
            audit(path, clientIp, "Token 校验失败");
            reject(response, HttpStatus.UNAUTHORIZED, ResultCode.UNAUTHORIZED);
            return;
        }

        if (properties.getRateLimit().isEnabled() && !rateLimiter.tryAcquire(path + '@' + clientIp)) {
            audit(path, clientIp, "请求频率超出限制");
            reject(response, HttpStatus.TOO_MANY_REQUESTS, ResultCode.RATE_LIMITED);
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * 解析客户端真实 IP
     * <p>
     * 默认直接采用 TCP 连接的对端地址。仅当明确配置信任反向代理时才解析 X-Forwarded-For，
     * 因为该请求头可由客户端任意伪造，在未经过代理的部署中信任它等同于让 IP 白名单失效。
     * @param request 请求
     * @return 客户端 IP
     */
    private String resolveClientIp(HttpServletRequest request) {
        if (properties.isTrustProxy()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                // X-Forwarded-For 形如 client, proxy1, proxy2, 取最左侧的客户端地址
                return forwarded.split(",")[0].trim();
            }

            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) {
                return realIp.trim();
            }
        }

        return request.getRemoteAddr();
    }

    /**
     * 从请求中提取 Token，优先取 Authorization 请求头，其次兼容部分 OneBot 实现使用的 access_token 头
     * @param request 请求
     * @return Token，不存在时返回 null
     */
    private String extractToken(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && !authorization.isBlank()) {
            String value = authorization.strip();
            if (value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
                return value.substring(BEARER_PREFIX.length()).strip();
            }
            return value;
        }

        String accessToken = request.getHeader("X-Access-Token");
        return accessToken == null || accessToken.isBlank() ? null : accessToken.strip();
    }

    /**
     * 输出审计日志
     * @param path 接口路径
     * @param clientIp 客户端 IP
     * @param reason 拒绝原因
     */
    private void audit(String path, String clientIp, String reason) {
        if (properties.isAuditLog()) {
            log.warn("推送接口 {} 拒绝了来自 {} 的请求: {}", path, clientIp, reason);
        }
    }

    /**
     * 输出拒绝响应
     * @param response 响应
     * @param status HTTP 状态码
     * @param code 业务错误码
     * @throws IOException 写出响应失败时抛出
     */
    private void reject(HttpServletResponse response, HttpStatus status, ResultCode code) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        JSONObject body = new JSONObject();
        body.put("code", code.getCode());
        body.put("msg", code.getMsg());

        response.getWriter().write(body.toJSONString());
    }
}
