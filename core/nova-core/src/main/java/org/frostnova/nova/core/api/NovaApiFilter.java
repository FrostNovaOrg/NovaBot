package org.frostnova.nova.core.api;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccountStore;
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
 *   <li>路径写法：带分号、反斜杠、编码过的斜杠、{@code .}／{@code ..} 段的一律 400，与控制台同一组判据；
 *       各段解码之后再查一遍，是 {@code .}、{@code ..}，或含斜杠、反斜杠、控制字符的，也是 400；</li>
 *   <li>来源地址：先判出来访者（{@link NovaApiVisitor}：对端不是本机回环就是对端；是本机回环又带了转发头的，
 *       取转发头里离 NovaBot 最近的那一段）。只分两种：<b>本机</b>（对端是回环、请求原本不带任何转发头，
 *       {@link NovaApiVisitor.Kind#LOCAL}；开着 {@code server.forward-headers-strategy=framework} 时按转发头过滤器
 *       记下的到达原样判）直接过；别的来访者——直连的别的机器、经反向代理进来的，
 *       转发头里写的是回环地址也一样——只有请求的这组接口在「对任何地址开放的接口组」里
 *       （{@link NovaApiOpenGroups}，每个请求现读）才接着往下走，否则 403。
 *       控制台「允许访问的来源 IP」名单不参与：那份名单只管控制台能从哪里打开。
 *       开放只免这一道，下面几道一道不少；</li>
 *   <li>令牌：<b>只认</b> {@code Authorization: Bearer <令牌>}。会话 Cookie、地址栏 {@code ?token=} 一概不看——
 *       Cookie 是浏览器自动附上的，认它就把这个前缀变成跨站伪造的靶子；地址栏里的东西会进代理日志与浏览器历史；</li>
 *   <li>令牌状态与账号状态：吊销、过期、账号停用或删除都是 401；成员此刻登不进控制台（{@link NovaApiMemberSignIn}）
 *       也是 401，开回来后下一个请求恢复。账号<b>每个请求现取</b>，
 *       管理员改了成员名下的主播、改了角色，下一个请求就按新的算；</li>
 *   <li>无效令牌按来访者限速（{@link NovaApiThrottle}），超了回 429；</li>
 *   <li>找接口、核范围（{@link NovaApiScope#adminOnly() 仅管理员}的范围再按账号此刻的角色核一次），
 *       交给处理器，把结果包成 {@code {"ok":true,"data":...}}。处理器出任何问题（含 {@link Error}、
 *       返回值转不成回包）都回固定文字的 500，细节只进服务端日志。</li>
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

    /** 账号此刻不能用令牌时的那句话：停用、删除、成员此刻登不进控制台，回包一个样 */
    static final String ACCOUNT_REFUSED = "令牌所属的账号已停用、已删除，或此刻登录不了控制台";

    private final NovaApiTokenStore tokens;

    private final ConfigUiAccountStore accounts;

    private final NovaApiRegistry registry;

    private final NovaApiThrottle throttle;

    private final Supplier<List<String>> platforms;

    private final NovaApiMe me;

    private final NovaApiMemberSignIn memberSignIn;

    private final NovaApiOpenGroups openGroups;

    /**
     * 一组也不对本机以外开放的那一种
     */
    public NovaApiFilter(NovaApiTokenStore tokens, ConfigUiAccountStore accounts, NovaApiMemberSignIn memberSignIn,
                         NovaApiRegistry registry, NovaApiThrottle throttle, Supplier<List<String>> platforms,
                         Supplier<List<NovaApiStreamer>> roster) {
        this(NovaApiOpenGroups.none(registry), tokens, accounts, memberSignIn, registry, throttle, platforms, roster);
    }

    /**
     * @param openGroups 对本机以外的来访者也开放的接口组，每个请求现取
     */
    public NovaApiFilter(NovaApiOpenGroups openGroups, NovaApiTokenStore tokens, ConfigUiAccountStore accounts,
                         NovaApiMemberSignIn memberSignIn, NovaApiRegistry registry, NovaApiThrottle throttle,
                         Supplier<List<String>> platforms, Supplier<List<NovaApiStreamer>> roster) {
        this.openGroups = openGroups;
        this.tokens = tokens;
        this.accounts = accounts;
        this.memberSignIn = memberSignIn;
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
                || Arrays.stream(raw.split("/", -1)).anyMatch(part -> part.equals(".") || part.equals(".."))
                || !decodedSegmentsAreClean(request.getRequestURI())) {
            error(response, 400, NovaApiErrors.BAD_REQUEST, "请求路径不正确");
            return;
        }

        NovaApiVisitor visitor = NovaApiVisitor.of(request);
        String source = visitor.describe();
        // 只有本机直连（对端回环、不带转发头）免开关；经代理进来的，转发头写着回环也不算本机
        if (visitor.kind() != NovaApiVisitor.Kind.LOCAL) {
            String group = group(request);
            if (!openGroups.opens(group)) {
                log.warn("统一接口拒绝了来自 {} 的访问: 不是本机直接发来的请求, 请求的这组接口也没有对本机以外的地址开放",
                        source);
                error(response, 403, NovaApiErrors.SOURCE_NOT_ALLOWED, notOpen(group, visitor));
                return;
            }
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
            account = accounts.find(matched.get().accountId()).filter(this::usable).orElse(null);
            if (account == null) {
                // 停用、删除与「成员此刻登不进控制台」同一句话：都是这个账号此刻不能用令牌
                refusal = ACCOUNT_REFUSED;
            }
        }
        if (refusal != null) {
            NovaApiThrottle.Verdict verdict = throttle.failure(visitor.throttleKey());
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
        NovaApiCaller caller = new NovaApiCaller(account, token, platforms.get(), registry::adminOnly);
        dispatch(request, response, caller);
    }

    /**
     * 请求的是哪一组接口：{@code /nova/api/v1/} 之后的第一段
     * @return 组名；不在 v1 之下、或第一段之后什么也没有时为 null
     */
    private static String group(HttpServletRequest request) {
        List<String> parts = segments(request);
        return parts == null || parts.isEmpty() ? null : parts.get(0);
    }

    /**
     * 本机以外的来访者、请求的这组又没开放时那句话
     * <p>
     * 要说清去哪里打开：使用者多半是照着控制台的地址从另一台机器来调的，只回一句「来源不许」，
     * 他不知道还有「按组开放」这一条路，也不知道这件事要管理员去做。组名只在是已登记的组时才写出来，
     * 请求路径里别的什么都不回显。
     */
    private String notOpen(String group, NovaApiVisitor visitor) {
        String which = group != null && !NovaApiRegistry.RESERVED.contains(group)
                && registry.segments().contains(group) ? "这组接口（" + group + "）" : "这组接口";
        String who = visitor.kind() == NovaApiVisitor.Kind.UNKNOWN
                ? "经反向代理进来、但转发头里认不出来访地址的请求，当作本机以外的来源。"
                : "这个请求不是从本机直接发来的（别的机器直连，或经反向代理转发进来）。";
        return who + which + "没有对本机以外的地址开放；管理员可在控制台「访问令牌」页的「从别的机器访问」里打开";
    }

    /**
     * 令牌所属的账号此刻能不能用令牌
     * <p>
     * 启用着；是成员的，还得此刻登得进控制台（{@link NovaApiMemberSignIn}，每个请求现问）。
     * 角色按此刻的算。管理员的令牌不看成员登录开关——经单点登录进来的管理员也一样。
     */
    private boolean usable(ConfigUiAccount account) {
        return account.status() == ConfigUiAccount.Status.ENABLED
                && (account.role() == ConfigUiAccount.Role.ADMIN || memberSignIn.open());
    }

    /**
     * 路径各段解码之后再查一遍
     * <p>
     * 字面的 {@code .}、{@code ..} 前面已经拦下；编码过的（{@code %2e%2e}）要解码之后才看得出来，
     * 放过去就会作为一段路径变量交到处理器手里。解码出斜杠、反斜杠、控制字符的段同样不收：
     * 它们在处理器拼文件名、拼日志时都是隐患。百分号写法本身不对的也算路径写错。
     * @param uri 原样的请求路径
     * @return 每一段都干净时为 true
     */
    static boolean decodedSegmentsAreClean(String uri) {
        for (String part : uri.split("/", -1)) {
            String value;
            try {
                value = UriUtils.decode(part, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException malformed) {
                return false;
            }
            if (value.equals(".") || value.equals("..") || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
                    || value.chars().anyMatch(Character::isISOControl)) {
                return false;
            }
        }
        return true;
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
            Optional<NovaApiScope> scope = registry.scope(route.scope());
            String name = scope.map(NovaApiScope::name).orElse(route.scope());
            if (!caller.scopes().contains(route.scope())) {
                error(response, 403, NovaApiErrors.INSUFFICIENT_SCOPE,
                        "这个令牌没有勾选「" + name + "」（" + route.scope() + "）范围");
                return;
            }
            // 签发时只有管理员勾得了；这里按账号此刻的角色再核一次，降成成员之后以前签的令牌就用不了它
            if (scope.map(NovaApiScope::adminOnly).orElse(false) && !caller.admin()) {
                error(response, 403, NovaApiErrors.FORBIDDEN,
                        "「" + name + "」（" + route.scope() + "）范围只有管理员能用，这把令牌所属的账号此刻不是管理员");
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
            if (refused.retryAfterSeconds() > 0) {
                response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(refused.retryAfterSeconds()));
            }
            error(response, refused.status(), refused.code(), refused.getMessage());
            return;
        } catch (Throwable failure) {
            // Error 也在这里收：交给容器的话，回包会变成一页带堆栈的 HTML，格式与细节都失控
            log.error("统一接口 {} 处理出错", label, failure);
            internalError(response);
            return;
        }
        if (data instanceof NovaApiBytes binary) {
            headers(response, HttpServletResponse.SC_OK, binary.contentType().toString());
            response.setContentLength(binary.bytes().length);
            response.getOutputStream().write(binary.bytes());
            return;
        }
        JSONObject body = new JSONObject();
        body.put("ok", true);
        body.put("data", data == null ? new JSONObject() : data);
        String text;
        try {
            text = JSON.toJSONString(body, JSONWriter.Feature.WriteNulls);
        } catch (Throwable unserializable) {
            // 先转成文字再写：转到一半出错时响应里还什么都没有，回得了统一格式
            log.error("统一接口 {} 的返回值转不成回包", label, unserializable);
            internalError(response);
            return;
        }
        write(response, 200, text);
    }

    private static void internalError(HttpServletResponse response) throws IOException {
        error(response, 500, NovaApiErrors.INTERNAL_ERROR, "处理这个请求时出错了，详情见服务端日志");
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
        write(response, status, JSON.toJSONString(body, JSONWriter.Feature.WriteNulls));
    }

    private static void write(HttpServletResponse response, int status, String text) throws IOException {
        headers(response, status, MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(text);
    }

    private static void headers(HttpServletResponse response, int status, String contentType) {
        response.setStatus(status);
        response.setContentType(contentType);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
    }
}
