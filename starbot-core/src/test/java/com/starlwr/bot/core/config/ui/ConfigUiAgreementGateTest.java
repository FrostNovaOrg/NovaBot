package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.config.ui.auth.ConfigUiAuthService;
import com.starlwr.bot.core.config.ui.auth.ConfigUiSession;
import com.starlwr.bot.core.config.ui.auth.ConfigUiSessionStore;
import com.starlwr.bot.core.config.ui.auth.LoginThrottle;
import com.starlwr.bot.core.util.IpMatcher;
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
 * 协议要挡的不是「看不看得到面板」，而是<b>会话</b>：控制台里有推送目标、有主播名单、有运行数据，
 * 拿到会话就等于进了门。因此判据统一落在「有没有签发会话」上，而签发会话的地方不止一个——
 * 口令登录是一处，「忘记口令」的启动令牌通道是另一处。<b>只堵前一处等于没堵</b>：
 * 启动日志里那个带令牌的地址一直都在，照着它进来的人一次协议也不会看到。
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

    private static final String TEMPLATE = """
            starbot:
              core:
                config-ui:
                  enabled: true
                  auth:
                    password: %s
            """.formatted(PASSWORD);

    @TempDir
    Path dir;

    private Path config;

    private StarBotCoreProperties properties;

    private ConfigurationFileService fileService;

    private ConfigUiAuthService authService;

    private ConfigUiAuthController controller;

    @BeforeEach
    void setUp() throws IOException {
        config = dir.resolve("application.yml");
        Files.writeString(config, TEMPLATE, StandardCharsets.UTF_8);
        fileService = new ConfigurationFileService(config);

        properties = new StarBotCoreProperties();
        properties.getConfigUi().getAuth().setPassword(PASSWORD);
        // 二次验证与本组用例无关，开着只会让每条登录都要多准备一个验证码
        properties.getConfigUi().getAuth().setTotp(false);

        authService = new ConfigUiAuthService(properties.getConfigUi().getAuth(),
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(5, Duration.ofMinutes(15)), fileService);
        controller = new ConfigUiAuthController(authService, fileService, properties);
    }

    private StarBotCoreProperties.ConfigUi.Agreement agreement() {
        return properties.getConfigUi().getAgreement();
    }

    /**
     * 过滤器拿的是配置里那份同意记录的本体，与登录接口写的是同一个对象——
     * 「点了同意就当场能进」靠的正是这一点，各留一份副本的话这里要等到重启才生效
     */
    private ConfigUiSecurityFilter filter() {
        return new ConfigUiSecurityFilter(TOKEN, new IpMatcher(List.of("0.0.0.0/0", "::/0")),
                authService, true, agreement());
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

    private String sha256(String text) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 未配口令的那一形态——单机部署的默认样子
     * <p>
     * 这一形态里没有会话可言：凭令牌直接进，令牌本身就是凭据。
     * 协议若只拦口令那一形态，绝大多数使用者一次协议也看不到。
     */
    private ConfigUiSecurityFilter tokenFormFilter() {
        ConfigUiAuthService noPassword = new ConfigUiAuthService(new StarBotCoreProperties.ConfigUi.Auth(),
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(5, Duration.ofMinutes(15)), fileService);

        assertFalse(noPassword.isEnabled(), "这一组问的正是「没配口令」那一形态，前提先自证，免得测成了口令形态");

        return new ConfigUiSecurityFilter(TOKEN, new IpMatcher(List.of("0.0.0.0/0", "::/0")),
                noPassword, true, agreement());
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
    @DisplayName("首次打开：状态里说要先看协议，口令登录被挡下")
    void loginIsRefusedBeforeTheAgreementIsAccepted() {
        JSONObject state = state();
        assertTrue(state.getBooleanValue("agreementRequired"), "一次也没同意过，状态里就该说要先看协议");
        assertEquals(ConfigUiAgreement.VERSION, state.getIntValue("agreementVersion"),
                "版本号要一并给出，否则前端说不清自己显示的是哪一版");

        ResponseEntity<JSONObject> response = login();
        assertEquals(403, response.getStatusCode().value(), "口令对不对都不重要，没同意协议就不该走到发会话那一步");
        assertEquals("请先阅读并同意使用协议", response.getBody().getString("message"),
                "话要说清是卡在哪里，否则使用者只会以为自己口令记错了");
        assertNull(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE), "更不该顺手把会话发出去");
    }

    @Test
    @DisplayName("🔴 启动令牌通道同样过闸：没同意协议时一个会话也不发")
    void operatorTokenIssuesNoSessionBeforeTheAgreementIsAccepted() throws Exception {
        MockHttpServletResponse page = visitWithToken(ConfigUiController.BASE_PATH);
        assertEquals(200, page.getStatus(), "面板首页按未登录处理：给登录页，好让协议面板先出现");
        assertNull(page.getHeader("Set-Cookie"),
                "这条通道本来就绕过口令与二次验证，它要是再绕过协议，协议就等于没有");

        MockHttpServletResponse api = visitWithToken(ConfigUiController.BASE_PATH + "/api/session-check");
        assertEquals(401, api.getStatus(), "接口不重定向到登录页，未登录就是 401");
        assertNull(api.getHeader("Set-Cookie"), "同上：没同意协议就没有会话");
    }

    @Test
    @DisplayName("协议那两条接口不必先登录，否则协议面板取不到文案、也点不动同意")
    void agreementEndpointsAreReachableWithoutSession() throws Exception {
        Map<String, String> endpoints = Map.of(
                "GET", ConfigUiController.BASE_PATH + "/api/auth/agreement",
                "POST", ConfigUiController.BASE_PATH + "/api/auth/agreement/accept");

        for (Map.Entry<String, String> endpoint : endpoints.entrySet()) {
            MockHttpServletRequest request = new MockHttpServletRequest(endpoint.getKey(), endpoint.getValue());
            request.setRemoteAddr("127.0.0.1");

            MockFilterChain chain = new MockFilterChain();
            filter().doFilter(request, new MockHttpServletResponse(), chain);

            assertNotNull(chain.getRequest(), endpoint.getValue() + " 被挡在了登录之后，协议面板将无从显示");
        }
    }

    @Test
    @DisplayName("同意之后：状态翻转、口令登录放行、令牌通道恢复")
    void everythingIsLetThroughOnceAccepted() throws Exception {
        JSONObject state = controller.acceptAgreement(new MockHttpServletRequest());
        assertFalse(state.getBooleanValue("agreementRequired"),
                "同意的回执就是新的状态，前端拿着它直接换成口令表单，不必再问一次");

        ResponseEntity<JSONObject> response = login();
        assertEquals(200, response.getStatusCode().value(),
                "阳性对照：这一条不通过，上面那条「没同意就进不去」说明不了任何事");
        assertNotNull(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE), "登录成功就该发会话");

        MockHttpServletResponse api = visitWithToken(ConfigUiController.BASE_PATH + "/api/session-check");
        assertEquals(200, api.getStatus(), "同意之后这条运维通道要照常可用——关的是协议这道闸，不是那条通道");
        assertNotNull(api.getHeader("Set-Cookie"), "它换会话的形态不变");
    }

    @Test
    @DisplayName("同意记录写回配置文件：重启、换台电脑打开都不必再同意一次")
    void acceptanceIsWrittenBackToTheConfigFile() throws IOException {
        controller.acceptAgreement(new MockHttpServletRequest());

        Map<String, String> saved = fileService.read();
        assertEquals(String.valueOf(ConfigUiAgreement.VERSION), saved.get("starbot.core.config-ui.agreement.accepted-version"),
                "同意的是哪一版必须落在盘上，只记在内存里等于重启就忘");

        String acceptedAt = saved.get("starbot.core.config-ui.agreement.accepted-at");
        assertNotNull(acceptedAt, "时间也要留下：日后问起「什么时候同意的」，答案得在盘上而不是靠人回忆");
        assertDoesNotThrow(() -> OffsetDateTime.parse(acceptedAt),
                "写下的时间要能被解回来，否则这行凭据日后没人读得懂: " + acceptedAt);
    }

    @Test
    @DisplayName("写不进配置文件时也照常放行：人已经看过并点了同意，这件事已经发生了")
    void acceptanceStillWorksWhenNothingCanBeWritten() {
        // 与口令哈希写回同一副形状：落盘失败只是下次启动还要再点一次，
        // 不该让一次写盘失败把人整个挡在控制台外面
        ConfigUiAuthController offline = new ConfigUiAuthController(authService, null, properties);

        JSONObject state = offline.acceptAgreement(new MockHttpServletRequest());
        assertFalse(state.getBooleanValue("agreementRequired"), "同意本身与能不能写盘无关");
        assertEquals(200, login().getStatusCode().value(), "这一次仍要进得去");
    }

    @Test
    @DisplayName("已经签发的会话不受影响：这道闸管的是发钥匙，不是回收钥匙")
    void alreadyIssuedSessionsAreNotRevoked() throws Exception {
        // 先在同意之后拿一个会话，再把记录改回未同意——文案改版之后正是这副样子
        controller.acceptAgreement(new MockHttpServletRequest());
        ConfigUiSession session = authService.issueForOperator("127.0.0.1");
        agreement().setAcceptedVersion(0);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", ConfigUiController.BASE_PATH + "/api/session-check");
        request.setCookies(new Cookie(ConfigUiSecurityFilter.SESSION_COOKIE, session.getId()));
        request.setRemoteAddr("127.0.0.1");

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter().doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus(), "把正在用面板的人当场踢出去，不是一份协议该干的事");
    }

    @Test
    @DisplayName("协议改版后，此前的同意即刻失效，得重新确认一次")
    void anOlderAcceptanceExpiresWhenTheTextIsRevised() {
        // 直接改记录里的版本号来摆出改版之后的样子：VERSION 是编译期常量改不动，
        // 而「记着的版本比当前版本小」正是文案改版之后的那个状态
        agreement().setAcceptedVersion(ConfigUiAgreement.VERSION);
        assertFalse(state().getBooleanValue("agreementRequired"), "记着的正是当前这一版，不该再打扰");

        agreement().setAcceptedVersion(ConfigUiAgreement.VERSION - 1);
        assertTrue(state().getBooleanValue("agreementRequired"), "记着的是上一版，改版之后必须重新确认");
        assertEquals(403, login().getStatusCode().value(), "重新要求确认期间，登录同样要被挡下");
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
        controller.acceptAgreement(new MockHttpServletRequest());

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
    void theAcceptedTimeSurvivesAReload() throws IOException {
        controller.acceptAgreement(new MockHttpServletRequest());
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
        StarBotCoreProperties reloaded = new StarBotCoreProperties();
        new Binder(ConfigurationPropertySources.from(
                new YamlPropertySourceLoader().load("重启后再读一遍", new FileSystemResource(config.toFile()))))
                .bind("starbot.core", Bindable.ofInstance(reloaded));

        assertEquals(written, reloaded.getConfigUi().getAgreement().getAcceptedAt(),
                "盘上那行与重启后内存里的值必须逐字相同。不加引号时 YAML 会把它当日期解掉，"
                        + "写进去的是 ISO 串、读回来的是另一副写法，而这错从界面上完全看不出来");
        assertEquals(ConfigUiAgreement.VERSION, reloaded.getConfigUi().getAgreement().getAcceptedVersion(),
                "版本号同样要读得回来，否则每次重启都要再同意一次");
    }
}
