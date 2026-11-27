package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.NovaCoreApplication;
import org.frostnova.nova.core.api.NovaApiTokenStore;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.web.TrustedProxyForwardedHeaderFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 整个程序开着 {@code server.forward-headers-strategy=framework} 起来时，各道过滤器按正式登记的顺序串在一起
 * <p>
 * 不开监听端口：过滤器取自上下文里正式登记的那些，按登记的顺序与路径挂到 MockMvc 上，请求的对端是 127.0.0.1。
 * <p>
 * 抓的故障：顶替的那一道在整个程序里没装上（Boot 那一道还在、或者两道都装），
 * 或者排在了控制台与程序接口那两道之后，单独核过滤器时看不出来。
 */
@SpringBootTest(classes = NovaCoreApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"spring.config.location=optional:classpath:/forwarded-headers-context-absent.yml",
                "server.forward-headers-strategy=framework",
                "novabot.core.config-ui.token=Hq7mT2vK9pW4sN6xB3cZ8rLd",
                "novabot.core.config-ui.update.enabled=false", "novabot.core.config-ui.auth.totp=false"})
@DirtiesContext
@DisplayName("整个程序开着 framework：经正式登记的过滤器链，客户端自带的 Forwarded 改不了来源")
class ForwardedHeadersFullContextTest {
    @TempDir
    static Path directory;

    @Autowired
    WebApplicationContext context;

    @Autowired
    NovaApiTokenStore tokens;

    @TestBean(methodName = "portContext")
    WebServerApplicationContext serverContext;

    @TestBean(methodName = "configurationFile")
    ConfigurationFileService fileService;

    private MockMvc mvc;

    @DynamicPropertySource
    static void paths(DynamicPropertyRegistry registry) {
        registry.add("novabot.core.live.live-data-path", () -> directory.resolve("live.json").toString());
        registry.add("novabot.core.datasource.json-path", () -> directory.resolve("datasource.json").toString());
        registry.add("logging.file.path", () -> directory.resolve("logs").toString());
    }

    static WebServerApplicationContext portContext() {
        WebServer server = mock(WebServer.class);
        when(server.getPort()).thenReturn(7827);
        WebServerApplicationContext context = mock(WebServerApplicationContext.class);
        when(context.getWebServer()).thenReturn(server);
        return context;
    }

    static ConfigurationFileService configurationFile() {
        return new ConfigurationFileService(directory.resolve("application.yml"));
    }

    @BeforeEach
    void chain() {
        var builder = MockMvcBuilders.webAppContextSetup(context);
        context.getBeansOfType(FilterRegistrationBean.class).values().stream()
                .map(bean -> (FilterRegistrationBean<?>) bean)
                .filter(FilterRegistrationBean::isEnabled)
                .sorted(Comparator.comparingInt(FilterRegistrationBean::getOrder))
                .forEach(bean -> builder.addFilter(bean.getFilter(), bean.getUrlPatterns().toArray(String[]::new)));
        mvc = builder.build();
    }

    @Test
    @DisplayName("只装了一道转发头过滤器，是顶替的那一道，排在最前")
    void exactlyOneForwardedHeaderFilterIsInstalledFirst() {
        List<FilterRegistrationBean<?>> forwarded = context.getBeansOfType(FilterRegistrationBean.class).values().stream()
                .<FilterRegistrationBean<?>>map(bean -> (FilterRegistrationBean<?>) bean)
                .filter(bean -> bean.getFilter() instanceof ForwardedHeaderFilter)
                .toList();
        assertEquals(1, forwarded.size(), forwarded.toString());
        assertInstanceOf(TrustedProxyForwardedHeaderFilter.class, forwarded.get(0).getFilter());
        assertEquals(Ordered.HIGHEST_PRECEDENCE, forwarded.get(0).getOrder());
    }

    @Test
    @DisplayName("控制台：只带 Forwarded 的伪造地址不改来源；可信代理送来的 X-Forwarded-For 照常认")
    void consoleSourceIgnoresForwarded() throws Exception {
        MockHttpServletResponse forged = perform(get("/config/api/status").header("Forwarded", "for=192.0.2.77"));
        MockHttpServletResponse mixed = perform(get("/config/api/status")
                .header("X-Forwarded-For", "127.0.0.1").header("Forwarded", "for=203.0.113.9"));
        MockHttpServletResponse outsider = perform(get("/config/api/status").header("X-Forwarded-For", "203.0.113.9"));
        report("控制台 只带 Forwarded: for=192.0.2.77", forged);
        report("控制台 XFF: 127.0.0.1 + Forwarded: for=203.0.113.9", mixed);
        report("控制台 XFF: 203.0.113.9", outsider);
        assertNotEquals(403, forged.getStatus(), "来源仍是对端 127.0.0.1，在默认名单里");
        assertNotEquals(403, mixed.getStatus(), "来源是可信代理送来的 127.0.0.1，不是 Forwarded 里的地址");
        assertEquals(403, outsider.getStatus(), "可信代理送来的地址不在名单里");
        assertTrue(outsider.getContentAsString().contains("白名单"), outsider.getContentAsString());
    }

    @Test
    @DisplayName("程序接口：令牌有效、组没有对外开放，只有本机直连且原本不带转发头的回 200")
    void apiLocalOnlyForPlainLoopback() throws Exception {
        String token = tokens.issue(ConfigUiAccount.LOCAL_ID, "整程序核对", List.of(), null).plaintext();
        List<Object[]> rows = List.of(
                new Object[]{"覆写 X-Forwarded-For、清掉 Forwarded", 403, new String[]{"X-Forwarded-For", "203.0.113.9"}},
                new Object[]{"只覆写 X-Forwarded-For，客户端自带 Forwarded", 403,
                        new String[]{"X-Forwarded-For", "203.0.113.9", "Forwarded", "for=127.0.0.1"}},
                new Object[]{"原样透传，客户端自带 Forwarded", 403, new String[]{"Forwarded", "for=127.0.0.1"}},
                new Object[]{"X-Forwarded-For 写着 127.0.0.1", 403, new String[]{"X-Forwarded-For", "127.0.0.1"}},
                new Object[]{"本机直连，不带转发头", 200, new String[]{}});
        for (Object[] row : rows) {
            MockHttpServletRequestBuilder request = get("/nova/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            String[] headers = (String[]) row[2];
            for (int i = 0; i < headers.length; i += 2) {
                request.header(headers[i], headers[i + 1]);
            }
            MockHttpServletResponse response = perform(request);
            report("程序接口 " + row[0], response);
            assertEquals(row[1], response.getStatus(), row[0] + " → " + response.getContentAsString());
            if (response.getStatus() == 403) {
                assertEquals("source_not_allowed", JSONObject.parseObject(response.getContentAsString())
                        .getJSONObject("error").getString("code"));
            }
        }
    }

    // 抓的故障：客户端自带的 X-Forwarded-Prefix 经可信代理原样送到，改写了请求路径，控制台原样路径里查 // 的那一项被绕过。
    @Test
    @DisplayName("控制台：路径里带 // 的请求，客户端自带 X-Forwarded-Prefix 也照样回 400")
    void doubleSlashRejectedDespiteForwardedPrefix() throws Exception {
        List<Object[]> rows = List.of(
                new Object[]{"不带 X-Forwarded-Prefix", new String[]{}},
                new Object[]{"自带 X-Forwarded-Prefix: /p", new String[]{"X-Forwarded-Prefix", "/p"}},
                new Object[]{"自带 X-Forwarded-Prefix: /p，代理送 X-Forwarded-Proto: https",
                        new String[]{"X-Forwarded-Prefix", "/p", "X-Forwarded-Proto", "https"}});
        for (Object[] row : rows) {
            MockHttpServletRequestBuilder request = get("/config/api/status").header(HttpHeaders.HOST, "bot.example");
            String[] headers = (String[]) row[1];
            for (int i = 0; i < headers.length; i += 2) {
                request.header(headers[i], headers[i + 1]);
            }
            MockHttpServletResponse response = mvc.perform(request.with(raw -> {
                raw.setRemoteAddr("127.0.0.1");
                raw.setRequestURI("//config/api/status");
                return raw;
            })).andReturn().getResponse();
            report("控制台 //config/api/status " + row[0], response);
            assertEquals(400, response.getStatus(), row[0] + " → " + response.getContentAsString());
        }
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(peer -> {
            peer.setRemoteAddr("127.0.0.1");
            return peer;
        })).andReturn().getResponse();
    }

    private static void report(String name, MockHttpServletResponse response) throws Exception {
        String body = response.getContentAsString();
        System.out.printf("FULL-CONTEXT %s → %d %s%n", name, response.getStatus(),
                body.length() > 120 ? body.substring(0, 120) : body);
    }
}
