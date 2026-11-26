package org.frostnova.nova.core.api;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.config.ui.ConfigUiSecurityFilter;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccountStore;
import org.frostnova.nova.core.util.IpMatcher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 统一接口面 {@code /nova/api/v1/} 的门与分发
 *
 * <h2>挂在哪一环</h2>
 * 一道 servlet 过滤器，罩着整个 {@code /nova/api} 前缀，排在安全响应头那道之后、
 * 与控制台安全过滤器同一档（见 {@code NovaApiConfiguration}）。它自己把响应写完，
 * <b>从不把请求交给后面的处理链</b>：这个前缀下没有 Spring MVC 控制器，
 * 插件登记的接口由这里直接调用，返回格式与错误包装因此只有这一份。
 *
 * <h2>依次做的事</h2>
 * <ol>
 *   <li>路径写法：带分号、反斜杠、编码过的斜杠、{@code .}／{@code ..} 段的一律 400，与控制台同一组判据；</li>
 *   <li>来源地址：控制台「允许访问的来源 IP」名单，取来源与判名单都调控制台那两段代码，名单每个请求现读；</li>
 *   <li>令牌：<b>只认</b> {@code Authorization: Bearer <令牌>}。会话 Cookie、地址栏 {@code ?token=} 一概不看——
 *       Cookie 是浏览器自动附上的，认它就把这个前缀变成跨站伪造的靶子；地址栏里的东西会进代理日志与浏览器历史；</li>
 *   <li>令牌状态与账号状态：吊销、过期、账号停用或删除都是 401；账号<b>每个请求现取</b>，
 *       管理员改了成员名下的主播，下一个请求就按新名单；</li>
 *   <li>无效令牌按来源限速（{@link NovaApiThrottle}），超了回 429；</li>
 *   <li>找接口、核范围，交给处理器，把结果包成 {@code {"ok":true,"data":...}}。</li>
 * </ol>
 *
 * <h2>不做的事</h2>
 * 不加任何跨域放行头；控制台关着时照常服务（这道过滤器不随控制台开关装配）。
 */
@Slf4j
public class NovaApiFilter extends OncePerRequestFilter {
    /** 整个前缀，过滤器罩着它 */
    public static final String PREFIX = "/nova/api";

    /** 当前版本的前缀 */
    public static final String V1 = PREFIX + "/v1";

    private static final String BEARER = "Bearer ";

    private final Supplier<IpMatcher> allowList;

    private final NovaApiTokenStore tokens;

    private final ConfigUiAccountStore accounts;

    private final NovaApiRegistry registry;

    private final NovaApiThrottle throttle;

    private final Supplier<List<String>> platforms;

    private final NovaApiMe me;

    public NovaApiFilter(Supplier<IpMatcher> allowList, NovaApiTokenStore tokens, ConfigUiAccountStore accounts,
                         NovaApiRegistry registry, NovaApiThrottle throttle, Supplier<List<String>> platforms,
                         Supplier<List<NovaApiStreamer>> roster) {
        this.allowList = allowList;
        this.tokens = tokens;
        this.accounts = accounts;
        this.registry = registry;
        this.throttle = throttle;
        this.platforms = platforms;
        this.me = new NovaApiMe(roster);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws IOException {
        String raw = request.getRequestURI().toLowerCase(Locale.ROOT);
        if (raw.contains(";") || raw.contains("\\") || raw.contains("%2f") || raw.contains("%5c") || raw.contains("//")
                || Arrays.stream(raw.split("/", -1)).anyMatch(part -> part.equals(".") || part.equals(".."))) {
            error(response, 400, NovaApiErrors.BAD_REQUEST, "请求路径不正确");
            return;
        }

        String source = ConfigUiSecurityFilter.clientAddress(request);
        if (!ConfigUiSecurityFilter.admits(allowList.get(), request)) {
            log.warn("统一接口拒绝了来自 {} 的访问: 来源 IP 不在控制台允许访问的来源 IP 名单内", source);
            error(response, 403, NovaApiErrors.SOURCE_NOT_ALLOWED,
                    "来源地址不在控制台「允许访问的来源 IP」名单里");
            return;
        }

        String presented = bearer(request);
        if (presented == null) {
            unauthenticated(response, "缺少访问令牌：请在请求头里带上 Authorization: Bearer <令牌>");
            return;
        }

        Instant now = tokens.now();
        Optional<NovaApiToken> matched = tokens.match(presented);
        String refusal = null;
        ConfigUiAccount account = null;
        if (matched.isEmpty() || matched.get().revoked()) {
            refusal = "访问令牌无效或已吊销";
        } else if (matched.get().expired(now)) {
            refusal = "访问令牌已过期，请在控制台重新签发";
        } else {
            account = accounts.find(matched.get().accountId())
                    .filter(found -> found.status() == ConfigUiAccount.Status.ENABLED).orElse(null);
            if (account == null) {
                refusal = "令牌所属的账号已停用或已删除";
            }
        }
        if (refusal != null) {
            NovaApiThrottle.Verdict verdict = throttle.failure(source);
            if (verdict.limited()) {
                if (verdict.justLimited()) {
                    log.warn("统一接口: 来自 {} 的无效令牌过多, 暂停受理这个来源的无效令牌 {} 秒",
                            source, verdict.retryAfterSeconds());
                }
                response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(verdict.retryAfterSeconds()));
                error(response, 429, NovaApiErrors.TOO_MANY_REQUESTS,
                        "这个来源连续出示无效令牌的次数太多，请 " + verdict.retryAfterSeconds() + " 秒后再试");
                return;
            }
            if (verdict.first()) {
                // 一个窗口里只记第一次：写错令牌的脚本每秒来一次，不该每次都占一行日志
                log.warn("统一接口拒绝了来自 {} 的访问: {}", source, refusal);
            }
            unauthenticated(response, refusal);
            return;
        }

        NovaApiToken token = matched.get();
        tokens.used(token);
        NovaApiCaller caller = new NovaApiCaller(account, token, platforms.get());
        dispatch(request, response, caller);
    }

    private void dispatch(HttpServletRequest request, HttpServletResponse response, NovaApiCaller caller)
            throws IOException {
        List<String> segments = segments(request);
        if (segments == null) {
            error(response, 404, NovaApiErrors.NOT_FOUND, "没有这个接口");
            return;
        }

        String method = request.getMethod();
        NovaApiRoute.Handler handler;
        Map<String, String> variables;
        String label;
        if (segments.size() == 1 && segments.get(0).equals("me")) {
            if (!method.equals("GET")) {
                error(response, 405, NovaApiErrors.METHOD_NOT_ALLOWED, "这个接口只接受 GET");
                return;
            }
            handler = me::handle;
            variables = Map.of();
            label = "GET me";
        } else {
            NovaApiRegistry.Match match = registry.match(method, segments);
            if (match instanceof NovaApiRegistry.WrongMethod) {
                error(response, 405, NovaApiErrors.METHOD_NOT_ALLOWED, "这个接口不接受 " + method);
                return;
            }
            if (!(match instanceof NovaApiRegistry.Found found)) {
                error(response, 404, NovaApiErrors.NOT_FOUND, "没有这个接口");
                return;
            }
            NovaApiRoute route = found.route();
            if (!caller.hasScope(route.scope())) {
                String name = registry.scope(route.scope()).map(NovaApiScope::name).orElse(route.scope());
                error(response, 403, NovaApiErrors.INSUFFICIENT_SCOPE,
                        "这个令牌没有勾选「" + name + "」（" + route.scope() + "）范围");
                return;
            }
            handler = route.handler();
            variables = found.variables();
            label = route.method() + " " + segments.get(0) + "/" + route.path();
        }

        Object data;
        try {
            data = handler.handle(new NovaApiRequest(request, caller, variables));
        } catch (NovaApiException refused) {
            error(response, refused.status(), refused.code(), refused.getMessage());
            return;
        } catch (Exception failure) {
            log.error("统一接口 {} 处理出错", label, failure);
            error(response, 500, NovaApiErrors.INTERNAL_ERROR, "处理这个请求时出错了，详情见服务端日志");
            return;
        }
        JSONObject body = new JSONObject();
        body.put("ok", true);
        body.put("data", data == null ? new JSONObject() : data);
        write(response, 200, body);
    }

    /**
     * {@code /nova/api/v1/} 之后的各段，已解码
     * @return 各段；不在 v1 之下时为 null
     */
    private static List<String> segments(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        String path = context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
        if (!path.startsWith(V1 + "/") || path.endsWith("/")) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (String part : path.substring(V1.length() + 1).split("/")) {
            try {
                parts.add(UriUtils.decode(part, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException malformed) {
                return null;
            }
        }
        return parts;
    }

    /**
     * 取 {@code Authorization: Bearer} 里的令牌
     * <p>
     * 只认这一处。不是 Bearer 的（Basic 之类）、空的，都当没带。
     */
    static String bearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return null;
        }
        String value = header.substring(BEARER.length()).strip();
        return value.isEmpty() ? null : value;
    }

    private static void unauthenticated(HttpServletResponse response, String message) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"NovaBot\"");
        error(response, 401, NovaApiErrors.UNAUTHENTICATED, message);
    }

    static void error(HttpServletResponse response, int status, String code, String message) throws IOException {
        JSONObject error = new JSONObject();
        error.put("code", code);
        error.put("message", message);
        JSONObject body = new JSONObject();
        body.put("ok", false);
        body.put("error", error);
        write(response, status, body);
    }

    private static void write(HttpServletResponse response, int status, JSONObject body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // 安全响应头那道已经按 /nova 前缀写过，这里再写一遍：这个前缀的响应里可能有账号与主播名单
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.getWriter().write(JSON.toJSONString(body, JSONWriter.Feature.WriteNulls));
    }
}
