package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.ui.auth.ConfigUiAuthService;
import com.starlwr.bot.core.config.ui.auth.ConfigUiSession;
import com.starlwr.bot.core.util.IpMatcher;
import com.starlwr.bot.core.util.SecureToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * 配置界面安全过滤器
 * <p>
 * 配置界面可以修改推送目标、查看运行状态，权限高于推送接口，因此始终要求来源 IP 白名单。
 * 白名单之后有两种形态：
 * <ul>
 *   <li><b>未配置登录口令</b>（默认）——沿用访问令牌：地址栏参数、Cookie 或请求头三选一。
 *       配合默认只放行回环地址的白名单，单机使用不必多输一次密码</li>
 *   <li><b>配置了登录口令</b>——改为登录换会话，令牌不再是凭据。
 *       公网地址栏里挂着长期令牌等于把钥匙贴在门上，而会话有期限、可注销、改口令即全部失效</li>
 * </ul>
 */
@Slf4j
public class ConfigUiSecurityFilter extends OncePerRequestFilter {
    /**
     * 保存令牌的 Cookie 名
     */
    private static final String TOKEN_COOKIE = "starbot_config_token";

    /**
     * 保存会话标识的 Cookie 名
     */
    public static final String SESSION_COOKIE = "starbot_config_session";

    /**
     * 携带 CSRF 令牌的请求头
     * <p>
     * 必须是自定义头：跨站的表单提交只能带上浏览器自动附加的 Cookie，加不了自定义头，
     * 因此「这个头存在且值正确」本身就证明请求来自本站页面。
     */
    public static final String CSRF_HEADER = "X-CSRF-Token";

    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * 无需登录即可访问的接口
     */
    private static final Set<String> PUBLIC_API = Set.of(
            ConfigUiController.BASE_PATH + "/api/auth/state",
            ConfigUiController.BASE_PATH + "/api/auth/login");

    /**
     * 不改变状态、因而不要求 CSRF 令牌的方法
     */
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final String token;

    private final IpMatcher ipMatcher;

    private final ConfigUiAuthService authService;

    public ConfigUiSecurityFilter(String token, IpMatcher ipMatcher, ConfigUiAuthService authService) {
        this.token = token;
        this.ipMatcher = ipMatcher;
        this.authService = authService;
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

        if (authService.isEnabled()) {
            filterWithLogin(request, response, chain, clientIp);
        } else {
            filterWithToken(request, response, chain, clientIp);
        }
    }

    /**
     * 口令登录形态下的校验
     */
    private void filterWithLogin(HttpServletRequest request, HttpServletResponse response, FilterChain chain, String clientIp)
            throws ServletException, IOException {
        // 跨站页面发起的写请求即使带上了 Cookie 也要挡住，这一层在会话校验之前
        if (!SAFE_METHODS.contains(request.getMethod()) && !sameOrigin(request)) {
            log.warn("配置界面拒绝了来自 {} 的跨站请求, Origin: {}", clientIp, request.getHeader(HttpHeaders.ORIGIN));
            reject(request, response, HttpStatus.FORBIDDEN, "请求来源不正确");
            return;
        }

        if (PUBLIC_API.contains(path(request))) {
            chain.doFilter(request, response);
            return;
        }

        Optional<ConfigUiSession> session = authService.validate(cookie(request, SESSION_COOKIE));
        if (session.isEmpty()) {
            unauthenticated(request, response);
            return;
        }

        if (!SAFE_METHODS.contains(request.getMethod())
                && !SecureToken.verify(session.get().getCsrfToken(), request.getHeader(CSRF_HEADER))) {
            log.warn("配置界面拒绝了来自 {} 的请求: 缺少或错误的 CSRF 令牌", clientIp);
            reject(request, response, HttpStatus.FORBIDDEN, "请求校验失败，请刷新页面后重试");
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * 访问令牌形态下的校验
     */
    private void filterWithToken(HttpServletRequest request, HttpServletResponse response, FilterChain chain, String clientIp)
            throws ServletException, IOException {
        String presented = extractToken(request);
        if (!SecureToken.verify(token, presented)) {
            log.warn("配置界面拒绝了来自 {} 的访问: 令牌校验失败", clientIp);
            reject(request, response, HttpStatus.UNAUTHORIZED, "访问令牌不正确，请使用启动日志中输出的地址访问");
            return;
        }

        // 地址栏参数校验通过后写入 Cookie，后续请求无需再带令牌
        if (request.getParameter("token") != null) {
            Cookie cookie = new Cookie(TOKEN_COOKIE, token);
            cookie.setHttpOnly(true);
            cookie.setPath("/");
            // 令牌存在 Cookie 里，浏览器就会把它自动附到跨站请求上。这一形态没有 CSRF 令牌可查，
            // 只能靠 SameSite 把跨站请求整个挡掉——现代浏览器的默认值已是 Lax，这里显式写死不指望默认
            cookie.setAttribute("SameSite", "Strict");
            response.addCookie(cookie);
        }

        chain.doFilter(request, response);
    }

    /**
     * 未登录时的响应
     * <p>
     * 只有面板首页给登录页，其余一律 401。接口不重定向到登录页——那会让前端把一段 HTML 当成 JSON 解析，
     * 报出的错与真实原因毫无关系；静态资源同理，一个 200 的 HTML 冒充 CSS 只会让人查错方向。
     */
    private void unauthenticated(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = path(request);
        if (!ConfigUiController.BASE_PATH.equals(path) && !(ConfigUiController.BASE_PATH + "/").equals(path)) {
            reject(request, response, HttpStatus.UNAUTHORIZED, "尚未登录");
            return;
        }

        try (var stream = new ClassPathResource("config-ui/login.html").getInputStream()) {
            response.setStatus(HttpStatus.OK.value());
            response.setContentType(MediaType.TEXT_HTML_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /**
     * 判断请求是否来自本站页面
     * <p>
     * 优先看 {@code Origin}，没有时退回 {@code Referer}。两者都没有时放行——
     * 有些浏览器在同源导航中确实不发这两个头，而真正的防线是 CSRF 令牌，
     * 这一层只是提前挡掉明显的跨站请求。
     */
    private boolean sameOrigin(HttpServletRequest request) {
        String origin = Optional.ofNullable(request.getHeader(HttpHeaders.ORIGIN))
                .orElseGet(() -> request.getHeader(HttpHeaders.REFERER));
        if (origin == null || origin.isBlank()) {
            return true;
        }

        String host = request.getHeader(HttpHeaders.HOST);
        if (host == null) {
            return false;
        }

        try {
            URI uri = URI.create(origin);
            if (uri.getHost() == null) {
                return false;
            }

            // 同主机不同端口也算跨站：同一台机器上的另一个服务同样可能是攻击者的
            String actual = uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
            return host.equalsIgnoreCase(actual);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isApi(HttpServletRequest request) {
        return path(request).contains("/api/");
    }

    /**
     * 取出相对于应用上下文的请求路径
     */
    private String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
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

        return cookie(request, TOKEN_COOKIE);
    }

    /**
     * 读取指定名称的 Cookie
     */
    private String cookie(HttpServletRequest request, String name) {
        return Optional.ofNullable(request.getCookies())
                .flatMap(cookies -> Arrays.stream(cookies).filter(c -> name.equals(c.getName())).findFirst())
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

        if (isApi(request)) {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);

            JSONObject body = new JSONObject();
            body.put("success", false);
            body.put("message", message);
            response.getWriter().write(body.toJSONString());
            return;
        }

        response.setContentType(MediaType.TEXT_HTML_VALUE);
        response.getWriter().write("""
                <!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>NovaBot 控制台</title>
                <style>body{font-family:system-ui,-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;
                display:flex;align-items:center;justify-content:center;height:100vh;margin:0;background:#f6f7f9;color:#333}
                div{text-align:center}h1{font-size:20px;margin:0 0 12px}p{color:#888;font-size:14px;margin:0}</style>
                </head><body><div><h1>无法访问配置界面</h1><p>%s</p></div></body></html>
                """.formatted(message));
    }
}
