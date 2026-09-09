package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSession;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSessionStore;
import org.frostnova.nova.core.config.ui.auth.LoginThrottle;
import org.frostnova.nova.core.util.IpMatcher;
import jakarta.servlet.Servlet;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制台使用协议这道闸
 * <p>
 * 协议要挡的不是「看不看得到面板」，而是<b>控制台本身</b>：里面有推送目标、有主播名单、有运行数据。
 * 但闸口的位置有讲究——<b>先认出人，再问他同不同意</b>：
 * <ul>
 *   <li>放在登录之前，「同意」就成了一个谁都能替使用者按下去的按钮：
 *       任何能连上这个端口的进程都写得下那行记录，而记录一旦写下就再也不会有人被问第二次</li>
 *   <li>放在登录之后，按下它的必定是刚刚通过口令或启动令牌的那个人，
 *       记录里也就写得出「是从哪条通道来的」</li>
 * </ul>
 * 认人的通道不止一条：口令登录是一处，「忘记口令」的启动令牌是另一处，未配口令时令牌本身就是凭据。
 * <b>三条都要过这道闸</b>——漏掉一条，照着那条路进来的人一次协议也看不到。
 */
@DisplayName("控制台使用协议")
class ConfigUiAgreementGateTest {
    private static final String TOKEN = "operator-token-for-test-0123456789";

    private static final String PASSWORD = "correct horse battery staple";

    /**
     * 协议文案的 SHA-256
     * <p>
     * 钉住的是「发出去的那一份文案」本身。改文案就要连着把 {@link ConfigUiAgreement#VERSION} 加一，
     * 否则已经点过同意的人再也不会看到新的那一份——而那正是这条判据要拦住的事：
     * 悄悄改掉文字，功能上什么都看不出来。
     */
    private static final String TEXT_SHA256 = "c210e0709a699dfcf05435491e84c1e9afba357570ee563cf6a5db456a383b48";

    private static final String ACCEPTED_VERSION_KEY = "novabot.core.config-ui.agreement.accepted-version";

    private static final String ACCEPTED_AT_KEY = "novabot.core.config-ui.agreement.accepted-at";

    private static final String ACCEPTED_BY_KEY = "novabot.core.config-ui.agreement.accepted-by";

    private static final String TEMPLATE = """
            novabot:
              core:
                config-ui:
                  enabled: true
                  auth:
                    password: %s
            """.formatted(PASSWORD);

    @TempDir
    Path dir;

    private Path config;

    private NovaCoreProperties properties;

    private ConfigurationFileService fileService;

    private ConfigUiAuthService authService;

    private ConfigUiAuthController controller;

    @BeforeEach
    void setUp() throws IOException {
        config = dir.resolve("application.yml");
        Files.writeString(config, TEMPLATE, StandardCharsets.UTF_8);
        fileService = new ConfigurationFileService(config);

        properties = new NovaCoreProperties();
        properties.getConfigUi().getAuth().setPassword(PASSWORD);
        // 二次验证与本组用例无关，开着只会让每条登录都要多准备一个验证码
        properties.getConfigUi().getAuth().setTotp(false);

        authService = new ConfigUiAuthService(properties.getConfigUi().getAuth(),
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(5, Duration.ofMinutes(15)), fileService);
        controller = new ConfigUiAuthController(authService, fileService, properties);
    }

    private NovaCoreProperties.ConfigUi.Agreement agreement() {
        return properties.getConfigUi().getAgreement();
    }

    /**
     * 过滤器拿的是配置里那份同意记录的本体，与登录接口写的是同一个对象——
     * 「点了同意就当场能进」靠的正是这一点，各留一份副本的话这里要等到重启才生效
     */
    private ConfigUiSecurityFilter filter() {
        return new ConfigUiSecurityFilter(TOKEN, new IpMatcher(List.of("0.0.0.0/0", "::/0")),
                authService, operatorTokenOn(), agreement());
    }

    /**
     * 开着令牌通道的那一份口令配置
     * <p>
     * 这几组用例问的是协议那道闸，令牌通道得开着才走得到「凭令牌换会话」那一支。
     * 过滤器读的是配置对象本体那一位，因此这里给的是对象不是布尔。
     */
    private NovaCoreProperties.ConfigUi.Auth operatorTokenOn() {
        NovaCoreProperties.ConfigUi.Auth auth = new NovaCoreProperties.ConfigUi.Auth();
        auth.setOperatorToken(true);
        return auth;
    }

    private JSONObject state() {
        return controller.state(new MockHttpServletRequest());
    }

    private ResponseEntity<JSONObject> login() {
        JSONObject body = new JSONObject();
        body.put("password", PASSWORD);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", ConfigUiController.BASE_PATH + "/api/auth/login");
        request.setRemoteAddr("127.0.0.1");

        return controller.login(body, request);
    }

    /**
     * 直接摆出「已经同意过」的状态
     * <p>
     * 用在那些不问「是怎么同意的」的用例上：它们要的只是闸已经开着这个前提。
     * 同意是怎么落笔的另有用例管。
     */
    private void markAccepted() {
        agreement().setAcceptedVersion(ConfigUiAgreement.VERSION);
        agreement().setAcceptedAt(OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString());
        agreement().setAcceptedBy(ConfigUiSession.Channel.PASSWORD.wire());
    }

    /**
     * 拿着启动令牌访问指定路径
     */
    private MockHttpServletResponse visitWithToken(String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setParameter("token", TOKEN);
        request.setRemoteAddr("127.0.0.1");

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter().doFilter(request, response, new MockFilterChain());

        return response;
    }

    /**
     * 拿着会话 Cookie 访问指定路径
     */
    private MockHttpServletResponse visitWithSession(String path, String sessionId) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setCookies(new Cookie(ConfigUiSecurityFilter.SESSION_COOKIE, sessionId));
        request.setRemoteAddr("127.0.0.1");

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter().doFilter(request, response, new MockFilterChain());

        return response;
    }

    /**
     * 从 {@code Set-Cookie} 头里取出会话标识
     */
    private String sessionIdOf(String setCookie) {
        if (setCookie == null) {
            return null;
        }

        String prefix = ConfigUiSecurityFilter.SESSION_COOKIE + "=";
        for (String part : setCookie.split(";")) {
            String piece = part.strip();
            if (piece.startsWith(prefix)) {
                return piece.substring(prefix.length());
            }
        }

        return null;
    }

    /**
     * 取某个会话的 CSRF 令牌，前端也是从这条状态接口上拿的
     */
    private String csrfOf(String sessionId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(ConfigUiSecurityFilter.SESSION_COOKIE, sessionId));

        return controller.state(request).getString("csrfToken");
    }

    /**
     * 接住「点同意」那一端
     * <p>
     * 这一串用例问的正是「谁在点」，而那个身份是过滤器认出来、交给控制器的，
     * 因此不能绕过过滤器直接调控制器——绕过去就测不到这件事本身。
     */
    private Servlet acceptEndpoint(ConfigUiAuthController target) {
        return new HttpServlet() {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
                ResponseEntity<JSONObject> outcome = target.acceptAgreement(request);

                response.setStatus(outcome.getStatusCode().value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.getWriter().write(String.valueOf(outcome.getBody()));
            }
        };
    }

    /**
     * 走完整一趟「点同意」：过滤器认人，控制器落笔
     * @param sessionId 会话标识，不带会话时为 null
     * @param csrf CSRF 令牌，不带时为 null
     */
    private MockHttpServletResponse accept(ConfigUiSecurityFilter filter, ConfigUiAuthController target,
                                           String sessionId, String csrf, String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", ConfigUiController.BASE_PATH + "/api/auth/agreement/accept");
        request.setRemoteAddr("127.0.0.1");

        if (sessionId != null) {
            request.setCookies(new Cookie(ConfigUiSecurityFilter.SESSION_COOKIE, sessionId));
        }
        if (csrf != null) {
            request.addHeader(ConfigUiSecurityFilter.CSRF_HEADER, csrf);
        }
        if (token != null) {
            request.setParameter("token", token);
        }

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain(acceptEndpoint(target)));

        return response;
    }

    /**
     * 接住「点撤回」那一端
     * <p>
     * 与 {@link #acceptEndpoint} 同一副形状：撤回同样是一个签字动作（撤的是自己那一笔签字），
     * 身份由过滤器认出来交给控制器，绕过过滤器直接调控制器就测不到这件事本身。
     */
    private Servlet revokeEndpoint(ConfigUiAuthController target) {
        return new HttpServlet() {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
                ResponseEntity<JSONObject> outcome = target.revokeAgreement(request);

                response.setStatus(outcome.getStatusCode().value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.getWriter().write(String.valueOf(outcome.getBody()));
            }
        };
    }

    /**
     * 走完整一趟「点撤回」：过滤器认人，控制器落笔
     */
    private MockHttpServletResponse revoke(ConfigUiSecurityFilter filter, ConfigUiAuthController target,
                                           String sessionId, String csrf, String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", ConfigUiController.BASE_PATH + "/api/auth/agreement/revoke");
        request.setRemoteAddr("127.0.0.1");

        if (sessionId != null) {
            request.setCookies(new Cookie(ConfigUiSecurityFilter.SESSION_COOKIE, sessionId));
        }
        if (csrf != null) {
            request.addHeader(ConfigUiSecurityFilter.CSRF_HEADER, csrf);
        }
        if (token != null) {
            request.setParameter("token", token);
        }

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain(revokeEndpoint(target)));

        return response;
    }

    /**
     * 已经过了过滤器那一层的一趟撤回请求
     * <p>
     * 用在过滤器会先把请求挡掉、因而走不到控制器的那几种情形上（未签态下的撤回、写盘失败）：
     * 通道属性正是过滤器放行时写下的那一位，这里照写一次，问的就只剩控制器自己那几道门。
     */
    private MockHttpServletRequest revokeRequest(ConfigUiSession.Channel channel) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", ConfigUiController.BASE_PATH + "/api/auth/agreement/revoke");
        request.setRemoteAddr("127.0.0.1");
        if (channel != null) {
            request.setAttribute(ConfigUiSecurityFilter.CHANNEL_ATTRIBUTE, channel.wire());
        }

        return request;
    }

    @Test
    @DisplayName("🔴 撤回同意：这台机器回到未签态，盘上那三行一并抹掉，重读配置文件仍是未签")
    void revokeReturnsTheMachineToTheUnsignedState() throws Exception {
        // 先按正常那条路同意一次：撤回撤的正是这一笔。直接摆状态的话，
        // 测到的是「能不能把三个字段清空」，而不是「撤得回一次真的同意」
        assertEquals(200, accept(tokenFormFilter(), controller, null, null, TOKEN).getStatus(),
                "前提：先得同意过一次");
        assertFalse(ConfigUiAgreement.required(agreement()),
                "前提：此刻是已签态，否则下面那条「撤回后变成未签」说明不了任何事");

        MockHttpServletResponse response = revoke(tokenFormFilter(), controller, null, null, TOKEN);
        assertEquals(200, response.getStatus(), "同意过的人撤得回来");
        assertTrue(ConfigUiAgreement.required(agreement()),
                "撤回之后这台机器就该回到未签态，否则「撤回」只是屏幕上的一句话");

        Map<String, String> saved = fileService.read();
        assertEquals("0", saved.get(ACCEPTED_VERSION_KEY),
                "版本要落回 0：留着旧版本号的话，重启之后这台机器又算同意过了");
        assertNull(saved.get(ACCEPTED_AT_KEY), "时间一并抹掉，撤回之后没有「什么时候同意的」这回事");
        assertNull(saved.get(ACCEPTED_BY_KEY), "身份同样抹掉");

        // 🔴 重读的是配置文件，不是内存里那份：只清内存的话，这台机器重启之后同意又回来了，
        // 而屏幕上撤回那一刻什么异常也看不出来。加载与绑定用启动时真正在跑的那一套
        NovaCoreProperties reloaded = new NovaCoreProperties();
        new Binder(ConfigurationPropertySources.from(
                new YamlPropertySourceLoader().load("撤回之后再读一遍", new FileSystemResource(config.toFile()))))
                .bind("novabot.core", Bindable.ofInstance(reloaded));

        assertTrue(ConfigUiAgreement.required(reloaded.getConfigUi().getAgreement()),
                "重启之后仍要是未签态——这才是「进入未签协议的状态」的全部意思");
    }

    @Test
    @DisplayName("🔴 撤回之后控制台对所有人关上：非白名单接口 403、首页给回协议屏；撤回前同一请求是通的")
    void revokeShutsTheConsoleForEveryone() throws Exception {
        String mine = sessionIdOf(login().getHeaders().getFirst(HttpHeaders.SET_COOKIE));
        String other = sessionIdOf(login().getHeaders().getFirst(HttpHeaders.SET_COOKIE));
        assertNotNull(mine, "前提：口令登录要能拿到会话");
        assertNotNull(other, "前提：另一台设备上那把会话也要拿得到");
        assertEquals(200, accept(filter(), controller, mine, csrfOf(mine), null).getStatus(), "前提：先同意一次");

        // 阳性对照：撤回之前这两把会话都读得到控制台。少了这一步，下面那两条 403
        // 可能只是因为这条请求本来就走不通
        assertEquals(200, visitWithSession(ConfigUiController.BASE_PATH + "/api/status", mine).getStatus(),
                "撤回之前，自己这把会话进得去");
        assertEquals(200, visitWithSession(ConfigUiController.BASE_PATH + "/api/status", other).getStatus(),
                "撤回之前，别处那把会话也进得去");

        assertEquals(200, revoke(filter(), controller, mine, csrfOf(mine), null).getStatus(), "撤回本身要办成");

        assertEquals(401, visitWithSession(ConfigUiController.BASE_PATH + "/api/status", mine).getStatus(),
                "点撤回的那一把会话当场结束：人刚说了「我不同意了」，不该还留着一把开着的钥匙");

        MockHttpServletResponse api = visitWithSession(ConfigUiController.BASE_PATH + "/api/status", other);
        assertEquals(403, api.getStatus(), "别处那把会话还在，但控制台已经关上了");
        assertTrue(api.getContentAsString().contains("请先阅读并同意使用协议"),
                "话要说清是卡在哪一步，否则那边只会以为面板坏了");

        MockHttpServletResponse home = visitWithSession(ConfigUiController.BASE_PATH, other);
        assertEquals(200, home.getStatus(), "面板首页给页面");
        assertTrue(home.getContentAsString().contains("id=\"agreement\""),
                "给的该是带协议面板的那一页——撤回之后所有人都要重新同意一次");

        // 🔴 撤回口自己<b>不在</b>协议白名单里：进了白名单的话，未签态下它照样调得动，
        // 而那时它撤的是一个已经不存在的同意
        MockHttpServletResponse again = revoke(filter(), controller, other, csrfOf(other), null);
        assertEquals(403, again.getStatus(),
                "未签态下撤回口该和控制台里其余接口一样被挡在协议闸外");
    }

    @Test
    @DisplayName("🔴 撤回同样要有身份，而且撤不了一个不存在的同意")
    void revokeNeedsAnIdentityAndAnExistingAcceptance() throws Exception {
        markAccepted();

        MockHttpServletResponse anonymous = revoke(filter(), controller, null, null, null);
        assertEquals(401, anonymous.getStatus(),
                "撤回是签字动作的反面，同样得先认出人：门外的人撤不了别人的签字");

        assertEquals(401, controller.revokeAgreement(revokeRequest(null)).getStatusCode().value(),
                "控制器自己也要拦一道：过滤器那张白名单是会被人改的，这一层是最后一道");

        // 从来没同意过的机器：过滤器此刻会先把这一趟挡在协议闸外（上一组用例量的正是那一条），
        // 因此这里直接问控制器——它不该替一个不存在的同意写下一行「已撤回」
        agreement().setAcceptedVersion(0);
        agreement().setAcceptedAt("");
        agreement().setAcceptedBy("");

        ResponseEntity<JSONObject> outcome = controller.revokeAgreement(revokeRequest(ConfigUiSession.Channel.PASSWORD));
        assertEquals(400, outcome.getStatusCode().value(), "本来就没同意过，撤回无从谈起");
        assertNull(fileService.read().get(ACCEPTED_VERSION_KEY), "被拒的一次调用不该在盘上落下任何一行");
    }

    @Test
    @DisplayName("🔴 写盘失败时撤回要回错：说撤回了却没落盘，重启之后同意又回来了")
    void revokeFailsLoudlyWhenNothingCanBeWritten() throws Exception {
        markAccepted();

        // 父目录是一个已经存在的普通文件，建目录必然失败——这是一次真的写盘失败，
        // 不是「压根没有 fileService」那种缺席
        ConfigurationFileService broken = new ConfigurationFileService(config.resolve("sub").resolve("application.yml"));
        ConfigUiAuthController failing = new ConfigUiAuthController(authService, broken, properties);

        ResponseEntity<JSONObject> outcome = failing.revokeAgreement(revokeRequest(ConfigUiSession.Channel.PASSWORD));

        assertEquals(500, outcome.getStatusCode().value(),
                "写不进去就不能说撤回了：同意那一侧写盘失败照常放行是对的（人确实看过），"
                        + "而撤回反过来——盘上还写着同意，重启之后它就作数");
        assertFalse(ConfigUiAgreement.required(agreement()),
                "内存里那份一个字都不该动：既然回的是「没撤成」，那就真的没撤");
    }

    private String sha256(String text) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 未配口令的那一形态——单机部署的默认样子
     * <p>
     * 这一形态里没有口令可言：凭令牌进来就是这套面板的「登录」，令牌本身就是凭据。
     * 协议若只拦口令那一形态，绝大多数使用者一次协议也看不到。
     */
    private ConfigUiSecurityFilter tokenFormFilter() {
        ConfigUiAuthService noPassword = new ConfigUiAuthService(new NovaCoreProperties.ConfigUi.Auth(),
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(5, Duration.ofMinutes(15)), fileService);

        assertFalse(noPassword.isEnabled(), "这一组问的正是「没配口令」那一形态，前提先自证，免得测成了口令形态");

        return new ConfigUiSecurityFilter(TOKEN, new IpMatcher(List.of("0.0.0.0/0", "::/0")),
                noPassword, operatorTokenOn(), agreement());
    }

    /**
     * 在未配口令那一形态下走一趟过滤器
     * @param carryToken true＝令牌走地址栏（第一次访问的样子），false＝令牌走 Cookie（此后每一次的样子）
     * @param endpoint 过滤器放行后接住请求的那一端，不关心时传 null
     * @return 过滤链，{@code getRequest()} 非空即表示请求确实被放行了
     */
    private MockFilterChain tokenFormVisit(String method, String path, boolean carryToken,
                                           MockHttpServletResponse response, Servlet endpoint) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("127.0.0.1");

        if (carryToken) {
            request.setParameter("token", TOKEN);
        } else {
            // Cookie 名是这一形态的对外契约，测试里照写
            request.setCookies(new Cookie("starbot_config_token", TOKEN));
        }

        MockFilterChain chain = endpoint == null ? new MockFilterChain() : new MockFilterChain(endpoint);
        tokenFormFilter().doFilter(request, response, chain);

        return chain;
    }

    /**
     * 站位控制台首页那个出口
     * <p>
     * 读的是 {@code ConfigUiController#page} 同一个类路径资源。这一考问的是过滤器放不放行，
     * 首页自己怎么渲染另有用例管。
     */
    private Servlet consoleHome() {
        return new HttpServlet() {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
                try (var stream = new ClassPathResource("config-ui/index.html").getInputStream()) {
                    response.setContentType(MediaType.TEXT_HTML_VALUE);
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    response.getWriter().write(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        };
    }

    @Test
    @DisplayName("首次打开：状态里说要先看协议，登录本身照常放行")
    void loginItselfIsNotBlockedByTheAgreement() {
        JSONObject state = state();
        assertTrue(state.getBooleanValue("agreementRequired"), "一次也没同意过，状态里就该说要先看协议");
        assertEquals(ConfigUiAgreement.VERSION, state.getIntValue("agreementVersion"),
                "版本号要一并给出，否则前端说不清自己显示的是哪一版");

        ResponseEntity<JSONObject> response = login();
        assertEquals(200, response.getStatusCode().value(),
                "协议挡的是控制台，不是登录：不先让人登录，就没有任何办法知道点同意的是不是使用者本人");
        assertNotNull(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE),
                "会话照常发——它此刻只够用来看协议，别的什么也打不开");
    }

    @Test
    @DisplayName("🔴 没有身份就点不动同意：来路不明的一次调用不该在盘上留下「使用者已同意」")
    void acceptIsRefusedWithoutAnIdentity() throws Exception {
        MockHttpServletResponse response = accept(filter(), controller, null, null, null);

        assertEquals(401, response.getStatus(),
                "同意是一个签字动作，签字的人得先被认出来：任何能连上这个端口的进程都签得下去的话，"
                        + "那行记录证明不了是使用者本人点的");

        Map<String, String> saved = fileService.read();
        assertNull(saved.get(ACCEPTED_VERSION_KEY), "被拒绝的一次调用不该写下任何东西");
        assertNull(saved.get(ACCEPTED_AT_KEY), "时间同样不该留下");
        assertNull(saved.get(ACCEPTED_BY_KEY), "更不该留下一个身份");
        assertEquals(0, agreement().getAcceptedVersion(), "内存里那份也不该被改动");
    }

    @Test
    @DisplayName("🔴 先登录再同意：登录之后、同意之前，控制台一概不给")
    void theConsoleStaysShutBetweenLoginAndAcceptance() throws Exception {
        ResponseEntity<JSONObject> outcome = login();
        String sessionId = sessionIdOf(outcome.getHeaders().getFirst(HttpHeaders.SET_COOKIE));
        assertNotNull(sessionId, "前提：口令登录要能拿到会话");

        MockHttpServletResponse api = visitWithSession(ConfigUiController.BASE_PATH + "/api/status", sessionId);
        assertEquals(403, api.getStatus(), "会话在手也不行：协议这道闸拦的是控制台里的东西");
        assertTrue(api.getContentAsString().contains("请先阅读并同意使用协议"),
                "话要说清是卡在哪一步：「没登录」与「还没同意」要去做的事完全不同");

        MockHttpServletResponse home = visitWithSession(ConfigUiController.BASE_PATH, sessionId);
        assertEquals(200, home.getStatus(), "面板首页要给页面，协议面板就在那一页上");
        assertTrue(home.getContentAsString().contains("id=\"agreement\""),
                "此刻该给出带协议面板的那一页，而不是控制台本身");

        MockHttpServletResponse accepted = accept(filter(), controller, sessionId, csrfOf(sessionId), null);
        assertEquals(200, accepted.getStatus(), "带着会话与 CSRF 令牌点下的同意才作数");
        assertEquals(ConfigUiSession.Channel.PASSWORD.wire(), agreement().getAcceptedBy(),
                "记录要写下人是从哪条通道进来的，否则日后仍然答不上「这是谁点的」");
        assertFalse(agreement().getAcceptedAt().isBlank(), "时间也要留下");

        assertEquals(200, visitWithSession(ConfigUiController.BASE_PATH + "/api/status", sessionId).getStatus(),
                "阳性对照：这一条不通过，上面那两条「没同意就进不去」说明不了任何事");
    }

    @Test
    @DisplayName("🔴 登了却不想同意的人要能退出：待同意态下退出登录不被协议闸拦")
    void logoutStaysReachableWhileTheAgreementIsPending() throws Exception {
        ResponseEntity<JSONObject> outcome = login();
        String sessionId = sessionIdOf(outcome.getHeaders().getFirst(HttpHeaders.SET_COOKIE));
        assertNotNull(sessionId, "前提：口令登录要能拿到会话");
        assertEquals(403, visitWithSession(ConfigUiController.BASE_PATH + "/api/status", sessionId).getStatus(),
                "前提：此刻协议闸确实关着，否则下面那条说明不了任何事");

        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", ConfigUiController.BASE_PATH + "/api/auth/logout");
        request.setRemoteAddr("127.0.0.1");
        request.setCookies(new Cookie(ConfigUiSecurityFilter.SESSION_COOKIE, sessionId));
        request.addHeader(ConfigUiSecurityFilter.CSRF_HEADER, csrfOf(sessionId));

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter().doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus(),
                "退出登录不是控制台里的东西：不同意也得让人走，否则只能等会话过期");
    }

    @Test
    @DisplayName("🔴 缺 CSRF 令牌的同意不作数：它与其余写接口是同一副形状")
    void acceptRequiresTheCsrfTokenLikeEveryOtherWrite() throws Exception {
        String sessionId = sessionIdOf(login().getHeaders().getFirst(HttpHeaders.SET_COOKIE));

        MockHttpServletResponse response = accept(filter(), controller, sessionId, null, null);

        assertEquals(403, response.getStatus(), "只有 Cookie 而没有自定义头的写请求，正是跨站页面能发出的那一种");
        assertTrue(agreement().getAcceptedBy().isBlank(), "被拒绝的一次调用不该留下身份");
    }

    @Test
    @DisplayName("🔴 启动令牌通道：令牌换到会话，协议照样先出现，同意记在这条通道名下")
    void theOperatorTokenChannelAcceptsUnderItsOwnName() throws Exception {
        MockHttpServletResponse page = visitWithToken(ConfigUiController.BASE_PATH);
        assertEquals(200, page.getStatus(), "面板首页给页面，协议面板就在那一页上");
        assertTrue(page.getContentAsString().contains("id=\"agreement\""), "给的该是带协议面板的那一页");

        String sessionId = sessionIdOf(page.getHeader("Set-Cookie"));
        assertNotNull(sessionId, "令牌验过了就该发会话：先认出是谁，才谈得上问他同不同意");

        assertEquals(403, visitWithSession(ConfigUiController.BASE_PATH + "/api/session-check", sessionId).getStatus(),
                "会话此刻只够用来看协议，控制台里的东西一样不给");

        MockHttpServletResponse accepted = accept(filter(), controller, sessionId, csrfOf(sessionId), null);
        assertEquals(200, accepted.getStatus(), "凭这条通道进来的人同样点得动同意");
        assertEquals(ConfigUiSession.Channel.OPERATOR_TOKEN.wire(), agreement().getAcceptedBy(),
                "这条通道绕过了口令与二次验证，记录里就该写明同意是从它那里来的");

        assertEquals(200, visitWithSession(ConfigUiController.BASE_PATH + "/api/session-check", sessionId).getStatus(),
                "阳性对照：同意之后这条运维通道要照常可用——关的是协议这道闸，不是那条通道");
    }

    @Test
    @DisplayName("🔴 未配口令那一形态：凭令牌进来就算认出了人，同意同样记在令牌这条通道名下")
    void theTokenFormAcceptsUnderTheOperatorTokenName() throws Exception {
        MockHttpServletResponse accepted = accept(tokenFormFilter(), controller, null, null, TOKEN);

        assertEquals(200, accepted.getStatus(), "这一形态里令牌就是凭据，凭它点下的同意作数");
        assertEquals(ConfigUiSession.Channel.OPERATOR_TOKEN.wire(), agreement().getAcceptedBy(),
                "记录里写的是它真正走的那条通道");
    }

    @Test
    @DisplayName("🔴 未配口令那一形态：令牌不对就点不动同意")
    void theTokenFormRefusesToAcceptWithoutTheToken() throws Exception {
        MockHttpServletResponse response = accept(tokenFormFilter(), controller, null, null, "wrong-token");

        assertEquals(401, response.getStatus(), "令牌不对就是没进门，门外的人签不了字");
        assertTrue(agreement().getAcceptedBy().isBlank(), "更不该留下一个身份");
        assertEquals(0, agreement().getAcceptedVersion(), "记录一个字都不该动");
    }

    @Test
    @DisplayName("🔴 只记着版本、记不出是谁点的：这种记录要在登录之后再问一次")
    void aRecordWithoutAnIdentityIsAskedAgain() {
        // 4.4.0 写下的正是这种记录：那一版未登录也点得动同意，因此盘上只有版本与时间
        agreement().setAcceptedVersion(ConfigUiAgreement.VERSION);
        agreement().setAcceptedAt("2026-09-02T18:00:00+08:00");
        agreement().setAcceptedBy("");

        assertTrue(ConfigUiAgreement.required(agreement()),
                "版本对得上也不算数：这行记录证明不了是使用者本人点的，得请他再确认一次");
        assertTrue(state().getBooleanValue("agreementRequired"), "状态接口要照实说，否则前端不会把协议面板摆出来");

        agreement().setAcceptedBy(ConfigUiSession.Channel.PASSWORD.wire());
        assertFalse(ConfigUiAgreement.required(agreement()),
                "阳性对照：补上身份之后就不该再打扰——这条闸认的是「记不出是谁」，不是「记录存在与否」");
    }

    @Test
    @DisplayName("同意记录写回配置文件：重启、换台电脑打开都不必再同意一次")
    void acceptanceIsWrittenBackToTheConfigFile() throws Exception {
        accept(tokenFormFilter(), controller, null, null, TOKEN);

        Map<String, String> saved = fileService.read();
        assertEquals(String.valueOf(ConfigUiAgreement.VERSION), saved.get(ACCEPTED_VERSION_KEY),
                "同意的是哪一版必须落在盘上，只记在内存里等于重启就忘");
        assertEquals(ConfigUiSession.Channel.OPERATOR_TOKEN.wire(), saved.get(ACCEPTED_BY_KEY),
                "从哪条通道同意的同样要落盘：只留在内存里的话，重启后这行记录又说不出是谁点的了");

        String acceptedAt = saved.get(ACCEPTED_AT_KEY);
        assertNotNull(acceptedAt, "时间也要留下：日后问起「什么时候同意的」，答案得在盘上而不是靠人回忆");
        assertDoesNotThrow(() -> OffsetDateTime.parse(acceptedAt),
                "写下的时间要能被解回来，否则这行凭据日后没人读得懂: " + acceptedAt);
    }

    @Test
    @DisplayName("写不进配置文件时也照常放行：人已经看过并点了同意，这件事已经发生了")
    void acceptanceStillWorksWhenNothingCanBeWritten() throws Exception {
        // 与口令哈希写回同一副形状：落盘失败只是下次启动还要再点一次，
        // 不该让一次写盘失败把人整个挡在控制台外面
        ConfigUiAuthController offline = new ConfigUiAuthController(authService, null, properties);

        MockHttpServletResponse response = accept(tokenFormFilter(), offline, null, null, TOKEN);

        assertEquals(200, response.getStatus(), "同意本身与能不能写盘无关");
        assertFalse(ConfigUiAgreement.required(agreement()), "这一次仍要进得去");
    }

    @Test
    @DisplayName("已经签发的会话不受影响：这道闸管的是控制台，不是回收钥匙")
    void alreadyIssuedSessionsAreNotRevoked() throws Exception {
        // 先在同意之后拿一个会话，再把记录改回未同意——文案改版之后正是这副样子
        markAccepted();
        ConfigUiSession session = authService.issueForOperator("127.0.0.1");
        assertEquals(200, visitWithSession(ConfigUiController.BASE_PATH + "/api/session-check", session.getId()).getStatus(),
                "前提：这个会话本来是通的");

        agreement().setAcceptedVersion(0);

        assertEquals(403, visitWithSession(ConfigUiController.BASE_PATH + "/api/session-check", session.getId()).getStatus(),
                "改版之后控制台要重新关上，等人再确认一次");

        markAccepted();
        assertEquals(200, visitWithSession(ConfigUiController.BASE_PATH + "/api/session-check", session.getId()).getStatus(),
                "但会话本身始终有效：把正在用面板的人当场注销掉，不是一份协议该干的事");
    }

    @Test
    @DisplayName("协议改版后，此前的同意即刻失效，得重新确认一次")
    void anOlderAcceptanceExpiresWhenTheTextIsRevised() throws Exception {
        // 直接改记录里的版本号来摆出改版之后的样子：VERSION 是编译期常量改不动，
        // 而「记着的版本比当前版本小」正是文案改版之后的那个状态
        markAccepted();
        assertFalse(state().getBooleanValue("agreementRequired"), "记着的正是当前这一版，不该再打扰");

        agreement().setAcceptedVersion(ConfigUiAgreement.VERSION - 1);
        assertTrue(state().getBooleanValue("agreementRequired"), "记着的是上一版，改版之后必须重新确认");

        String sessionId = sessionIdOf(login().getHeaders().getFirst(HttpHeaders.SET_COOKIE));
        assertNotNull(sessionId, "重新确认期间登录照常：要先认出人，才谈得上问他同不同意");
        assertEquals(403, visitWithSession(ConfigUiController.BASE_PATH + "/api/status", sessionId).getStatus(),
                "但控制台在他确认之前一直关着");
    }

    @Test
    @DisplayName("协议全文原样发出，一个字都不许漂")
    void theAgreementTextIsServedVerbatim() throws Exception {
        JSONObject body = controller.agreement();

        assertTrue(body.getBooleanValue("success"), "文案读不出来，协议面板就只剩一个空框和一个同意按钮");
        assertEquals(ConfigUiAgreement.VERSION, body.getIntValue("version"), "文案与版本号要一同发出，二者是一回事");
        assertEquals(TEXT_SHA256, sha256(body.getString("text")),
                "文案指纹对不上。改文案就要连着把 ConfigUiAgreement.VERSION 加一，"
                        + "否则已经点过同意的人再也看不到新的那一份");
    }

    @Test
    @DisplayName("🔴 未配口令那一形态：凭令牌进来也先看协议，令牌 Cookie 照旧先写下")
    void theTokenFormShowsTheAgreementFirst() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = tokenFormVisit("GET", ConfigUiController.BASE_PATH, true, response, consoleHome());

        assertEquals(200, response.getStatus(), "面板首页给页面，协议面板就在那一页上");
        assertTrue(response.getContentAsString().contains("id=\"agreement\""),
                "首页此刻该给出带协议面板的那一页，而不是控制台本身");
        assertNull(chain.getRequest(), "控制台首页这一趟不该被放行");
        assertNotNull(response.getCookie("starbot_config_token"),
                "令牌 Cookie 必须在这一趟就写下：同意之后浏览器回到 /config 时，地址栏里已经没有令牌了，"
                        + "此刻若还没写，人就被自己刚点过的同意关在门外");
    }

    @Test
    @DisplayName("🔴 未配口令那一形态：没同意协议时接口一律 403，协议自己那几条除外")
    void theTokenFormRefusesApisUntilAccepted() throws Exception {
        MockHttpServletResponse blocked = new MockHttpServletResponse();
        MockFilterChain chain = tokenFormVisit("GET", ConfigUiController.BASE_PATH + "/api/status", true, blocked, null);

        assertEquals(403, blocked.getStatus(), "令牌对不对都不重要，没同意协议就不该读到控制台的任何数据");
        assertTrue(blocked.getContentAsString().contains("请先阅读并同意使用协议"),
                "话要说清是卡在哪一步：「令牌不对」与「还没同意」要去做的事完全不同");
        assertNull(chain.getRequest(), "更不该悄悄放行");

        // 协议面板自己要用的三条：问状态、取文案、点同意。它们要是也被拦下，面板就成了一张死页
        List<String[]> open = List.of(
                new String[]{"GET", ConfigUiController.BASE_PATH + "/api/auth/state"},
                new String[]{"GET", ConfigUiController.BASE_PATH + "/api/auth/agreement"},
                new String[]{"POST", ConfigUiController.BASE_PATH + "/api/auth/agreement/accept"});

        for (String[] endpoint : open) {
            MockFilterChain passed = tokenFormVisit(endpoint[0], endpoint[1], true, new MockHttpServletResponse(), null);
            assertNotNull(passed.getRequest(), endpoint[1] + " 被协议闸挡下了，协议面板将无从显示，也点不动同意");
        }
    }

    @Test
    @DisplayName("🔴 未配口令那一形态：同意之后凭 Cookie 直接进控制台，不必再带一次令牌")
    void theTokenFormEntersTheConsoleOnceAccepted() throws Exception {
        markAccepted();

        // 地址栏里不再带 token，只有上一趟写下的那枚 Cookie——同意之后浏览器回来时就是这副样子
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = tokenFormVisit("GET", ConfigUiController.BASE_PATH, false, response, consoleHome());

        assertNotNull(chain.getRequest(),
                "阳性对照：这一条不通过，上面那两条「没同意就进不去」说明不了任何事");
        assertEquals(200, response.getStatus(), "同意之后就该直接进控制台");
        assertTrue(response.getContentAsString().contains("/config/assets/app.css"),
                "拿到的该是控制台首页（它取外部样式表），不是自足的那张登录页");
        assertFalse(response.getContentAsString().contains("id=\"agreement\""), "协议面板不该再出现");
    }

    @Test
    @DisplayName("🔴 同意时间重启后还是那个时间：ISO 串不会在读回来的路上被改掉样子")
    void theAcceptedTimeSurvivesAReload() throws Exception {
        accept(tokenFormFilter(), controller, null, null, TOKEN);
        String written = agreement().getAcceptedAt();

        // 盘上先得还是原文：这一半与下一半是两回事，一并钉住才说得清「哪一头变了」
        assertTrue(Files.readString(config, StandardCharsets.UTF_8).contains(written),
                "写下去的 ISO 串就该原样躺在配置文件里: " + written);

        // 再用启动时真正在跑的那套加载与绑定重读一次，而不是自己按行解析。
        // 🔴 这一条守的是一件「本来就成立、但极容易被后人改坏」的事：裸写的
        // 2026-09-02T10:11:12+08:00 在通用 YAML 解析器眼里是个时间戳，会被解成日期对象、
        // 再转回字符串时就成了另一副写法。Spring Boot 的属性加载器特意关掉了时间戳这条隐式规则
        // （OriginTrackedYamlLoader.NoTimestampResolver），所以现在是逐字相等的——
        // 哪天换了加载器或自己拿通用解析器去读这份文件，这条会当场红
        NovaCoreProperties reloaded = new NovaCoreProperties();
        new Binder(ConfigurationPropertySources.from(
                new YamlPropertySourceLoader().load("重启后再读一遍", new FileSystemResource(config.toFile()))))
                .bind("novabot.core", Bindable.ofInstance(reloaded));

        assertEquals(written, reloaded.getConfigUi().getAgreement().getAcceptedAt(),
                "盘上那行与重启后内存里的值必须逐字相同。不加引号时 YAML 会把它当日期解掉，"
                        + "写进去的是 ISO 串、读回来的是另一副写法，而这错从界面上完全看不出来");
        assertEquals(ConfigUiAgreement.VERSION, reloaded.getConfigUi().getAgreement().getAcceptedVersion(),
                "版本号同样要读得回来，否则每次重启都要再同意一次");
        assertFalse(ConfigUiAgreement.required(reloaded.getConfigUi().getAgreement()),
                "重启之后这份记录要仍然算数，包括「记得出是谁点的」这一半——"
                        + "少了它，每次重启都会被重新问一遍");
    }
}
