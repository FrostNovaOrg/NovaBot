package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSession;
import org.frostnova.nova.core.util.IpMatcher;
import org.frostnova.nova.core.lang.SecureToken;
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
     * 请求走到后面那一端时，这个属性里放着它是从哪条通道进来的
     * <p>
     * 「同意使用协议」那一笔要记下通道，而只有这一层才知道来人是输了口令、还是拿着启动令牌。
     * 用请求属性交出去，而不是让控制器自己再判一次：判两遍就会有两个答案，
     * 其中一个迟早与实际放行的依据对不上。
     */
    public static final String CHANNEL_ATTRIBUTE = "starbot.config-ui.channel";

    /**
     * 无需通过身份校验即可访问的接口
     * <p>
     * 只有登录页自己要用的这两条：问一句「要不要登录、要不要验证码」，以及登录本身。
     * <p>
     * 🔴 使用协议那几条<b>不在这里</b>。它们曾经在，而那正是一处缺陷：「同意」是一个签字动作，
     * 签字的人必须先被认出来。放在身份校验之前，任何能连上这个端口的程序都替使用者签得下去，
     * 而那行记录一旦写下，使用者本人就再也不会被问第二次。
     * <p>
     * 🔴 <b>这张名单只在口令形态下生效。</b>未配口令时过滤器走 {@link #filterWithToken}，
     * 令牌本身就是凭据，不会查阅这张表——名单里的路径与其它接口一样，没带令牌一律 401。
     * 口令形态才需要这几条公开：登录页要先问「要不要登录」再提交口令，这两步都发生在
     * 认出人之前。令牌形态没有登录页，公开它们等于把进门钥匙从地址栏里拿掉。
     */
    private static final Set<String> PUBLIC_API = Set.of(
            ConfigUiController.BASE_PATH + "/api/auth/state",
            ConfigUiController.BASE_PATH + "/api/auth/login",
            // 通行密钥登录那两条与口令登录并列：它们本身就是进门的那一步，
            // 要求先登录才能调用等于把这条路整个封死。
            // 🔴 <b>只有 login 那两条</b>——登记那两条不在此列，理由见 ConfigUiPasskeyController
            ConfigUiController.BASE_PATH + "/api/auth/passkey/login/options",
            ConfigUiController.BASE_PATH + "/api/auth/passkey/login/verify");

    /**
     * 身份已经认出来、但协议还没同意时仍然放行的接口
     * <p>
     * 就是协议面板自己要用的三条：问状态、取文案、点同意。它们要是也被拦下，面板就成了一张死页。
     * 控制台里的东西一条也不在此列——这道闸关的正是控制台。
     */
    private static final Set<String> AGREEMENT_API = Set.of(
            ConfigUiController.BASE_PATH + "/api/auth/state",
            ConfigUiController.BASE_PATH + "/api/auth/agreement",
            ConfigUiController.BASE_PATH + "/api/auth/agreement/accept",
            // 退出登录不是控制台里的东西：登了却不想同意的人要能走，否则只能等会话过期
            ConfigUiController.BASE_PATH + "/api/auth/logout");

    /**
     * 不改变状态、因而不要求 CSRF 令牌的方法
     */
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final String token;

    private final IpMatcher ipMatcher;

    private final ConfigUiAuthService authService;

    /**
     * 口令登录那一节的配置，本过滤器只读其中的「留不留启动令牌通道」这一位
     * <p>
     * 🔴 <b>拿的是配置对象本体，不是构造时抄下来的那个布尔。</b>抄一份的写法在
     * 「设下第一把口令就自动关掉这条后门」这件事上会静静失效：配置改了、日志也写了，
     * 而这道门读的还是启动那一刻抄下来的值——<b>门开着，账上写着关。</b>
     * 与 {@link #agreement} 同理，那一处的理由也是同一条。
     */
    private final NovaCoreProperties.ConfigUi.Auth auth;

    /**
     * 使用协议的同意记录
     * <p>
     * 与登录接口拿的是同一个对象，不是它的副本：使用者点下「同意并继续」之后要<b>当场</b>能进，
     * 各留一份的话，这一侧要等到下次重启才知道人已经同意过了。
     */
    private final NovaCoreProperties.ConfigUi.Agreement agreement;

    public ConfigUiSecurityFilter(String token, IpMatcher ipMatcher, ConfigUiAuthService authService,
                                  NovaCoreProperties.ConfigUi.Auth auth,
                                  NovaCoreProperties.ConfigUi.Agreement agreement) {
        this.token = token;
        this.ipMatcher = ipMatcher;
        this.authService = authService;
        this.auth = auth;
        this.agreement = agreement;
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
        boolean redeemed = false;

        // 没有会话时看看是不是拿着启动令牌来的运维通道
        if (session.isEmpty()) {
            session = redeemOperatorToken(request, response, clientIp);
            if (session.isEmpty()) {
                unauthenticated(request, response);
                return;
            }

            redeemed = true;
        }

        // 令牌只能从地址栏或请求头带来，跨站页面拿不到它，因此换会话的那一趟不必再查 CSRF
        if (!redeemed && !SAFE_METHODS.contains(request.getMethod())
                && !SecureToken.verify(session.get().getCsrfToken(), request.getHeader(CSRF_HEADER))) {
            log.warn("配置界面拒绝了来自 {} 的请求: 缺少或错误的 CSRF 令牌", clientIp);
            reject(request, response, HttpStatus.FORBIDDEN, "请求校验失败，请刷新页面后重试");
            return;
        }

        // 协议这道闸<b>排在身份校验之后</b>：先认出是谁，再问他同不同意。
        // 反过来的话，「同意」就成了一个谁都替使用者按得下去的按钮
        if (ConfigUiAgreement.required(agreement) && !AGREEMENT_API.contains(path(request))) {
            agreementNotAccepted(request, response);
            return;
        }

        request.setAttribute(CHANNEL_ATTRIBUTE, session.get().getChannel().wire());
        chain.doFilter(request, response);
    }

    /**
     * 用启动令牌换一个会话
     * <p>
     * <b>这是一条刻意留下的运维通道</b>：口令与二次验证都改过之后，仍然要有办法从服务器上进得来。
     * 令牌就在启动日志里，而能看到启动日志的人本来就对这台机器有完全控制权，
     * 因此它不构成额外的权限泄漏；反过来，没有这条通道，忘记口令就只能改配置重启，
     * 而重启会断开全部直播间长连接。
     * <p>
     * 只认地址栏参数与 {@code Authorization} 头，<b>不认 Cookie</b>：
     * Cookie 是浏览器自动附上的，认它就等于把这条通道也变成一个 CSRF 面。
     * 反之，跨站页面既读不到令牌也设不了自定义头，所以凭令牌来的请求本身就不可能是跨站伪造的。
     * <p>
     * 换到会话之后浏览器就照常走会话那一套，地址栏里的令牌不必再出现第二次。
     * @return 换得的会话，令牌不正确时为空
     */
    private Optional<ConfigUiSession> redeemOperatorToken(HttpServletRequest request, HttpServletResponse response, String clientIp) {
        // 🔴 关掉之后这条路整个不存在：不看令牌、不比对、不记失败。
        // 之所以在最前面就返回而不是「比一比再拒」，是因为「比过了但不放行」
        // 仍然会因为耗时差异透露出令牌对不对，而这条路关掉之后本就不该有任何反馈
        if (!auth.isOperatorToken()) {
            return Optional.empty();
        }

        String presented = request.getParameter("token");
        if (presented == null || presented.isBlank()) {
            String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (authorization != null && authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
                presented = authorization.substring(BEARER_PREFIX.length()).strip();
            }
        }

        if (presented == null || presented.isBlank() || !SecureToken.verify(token, presented.strip())) {
            return Optional.empty();
        }

        // 使用协议这道闸不在这里：会话在协议同意之前什么也打不开（那道闸在下一步），
        // 而<b>不发会话反倒使人无从同意</b>——点同意得先被认出来是谁，认出来的凭据正是这把会话
        ConfigUiSession session = authService.issueForOperator(clientIp);
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(session.getId(), request));

        // 这条通道绕过了口令与二次验证，每次使用都要写审计记录，否则被人拿到令牌也看不出来
        log.warn("配置界面: 来自 {} 的访问以启动令牌换取了会话, 已绕过口令与二次验证", clientIp);

        return Optional.of(session);
    }

    /**
     * 构造会话 Cookie
     * <p>
     * 与 {@link ConfigUiAuthController} 中的那份保持一致：{@code HttpOnly} 挡住脚本读取，
     * {@code SameSite=Strict} 让跨站请求根本带不上它，{@code Secure} 跟随当前连接是否为 https。
     */
    private String sessionCookie(String value, HttpServletRequest request) {
        return "%s=%s; Path=%s; HttpOnly; SameSite=Strict%s".formatted(
                SESSION_COOKIE, value, ConfigUiController.BASE_PATH, request.isSecure() ? "; Secure" : "");
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

        // 地址栏参数校验通过后写入 Cookie，后续请求无需再带令牌。
        // 这一步<b>必须排在协议那道闸之前</b>：同意之后浏览器回到 /config 时地址栏里已经没有令牌了，
        // 此刻 Cookie 若还没写下，人就被自己刚点过的同意关在了门外，只能回头去启动日志里再抄一次地址
        if (request.getParameter("token") != null) {
            Cookie cookie = new Cookie(TOKEN_COOKIE, token);
            cookie.setHttpOnly(true);
            cookie.setPath("/");
            // 令牌存在 Cookie 里，浏览器就会把它自动附到跨站请求上。这一形态没有 CSRF 令牌可查，
            // 只能靠 SameSite 把跨站请求整个挡掉——现代浏览器的默认值已是 Lax，这里显式写死不指望默认
            cookie.setAttribute("SameSite", "Strict");
            response.addCookie(cookie);
        }

        // 这一形态里没有口令，令牌本身就是凭据：验过令牌就等于认出了人，
        // 与口令形态那一侧「凭启动令牌换会话」走的是同一条路，因此记的也是同一条通道
        request.setAttribute(CHANNEL_ATTRIBUTE, ConfigUiSession.Channel.OPERATOR_TOKEN.wire());

        // 使用协议这道闸对令牌形态同样有效。未配口令时，凭令牌进来就是这套面板的「登录」，
        // 只拦口令那一形态等于绝大多数单机使用者一次协议也看不到。
        // 白名单那几条是协议面板自己要用的（问状态、取文案、点同意），放它们过去
        if (ConfigUiAgreement.required(agreement) && !AGREEMENT_API.contains(path(request))) {
            agreementNotAccepted(request, response);
            return;
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
        if (!isHome(request)) {
            reject(request, response, HttpStatus.UNAUTHORIZED, "尚未登录");
            return;
        }

        loginPage(response);
    }

    /**
     * 尚未同意使用协议时的响应
     * <p>
     * 与未登录那一副形状相同：只有面板首页给页面（协议面板就在 login.html 上），其余一律拒绝，
     * 并说清是卡在哪一步——「访问令牌不正确」与「还没同意协议」要去做的事完全不同。
     * <p>
     * 静态资源不必单开口子：login.html 的样式与脚本都写在它自己里面，不取任何外部资源。
     */
    private void agreementNotAccepted(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (isHome(request)) {
            loginPage(response);
            return;
        }

        reject(request, response, HttpStatus.FORBIDDEN, "请先阅读并同意使用协议");
    }

    /**
     * 是否为面板首页
     */
    private boolean isHome(HttpServletRequest request) {
        String path = path(request);
        return ConfigUiController.BASE_PATH.equals(path) || (ConfigUiController.BASE_PATH + "/").equals(path);
    }

    /**
     * 吐出登录页
     * <p>
     * 页面上那几个判定（摆什么、折什么、禁什么）由 {@link ConfigUiLoginPage} 拼进来，
     * 见那里的说明：这张页面不取任何外部资源，而那段判定又不该在页面里再抄一份。
     */
    private void loginPage(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(MediaType.TEXT_HTML_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(ConfigUiLoginPage.html());
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
