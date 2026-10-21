package org.frostnova.nova.core.config.ui;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSession;
import org.frostnova.nova.core.config.ui.page.ConsolePageProvider;
import org.frostnova.nova.core.util.IpMatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class ConfigUiAssetsTest {
    private static final String TIME = "2026-10-20T12:00:00Z";
    private static final String VERSION = Long.toString(Instant.parse(TIME).toEpochMilli());
    private static final String LONG_CACHE = "max-age=31536000, private, immutable";

    @SuppressWarnings("unchecked")
    private MockMvc mvc(String time) {
        var controller = ConfigUiPrefixPages.controller();
        var properties = new Properties();
        properties.setProperty("time", time);
        properties.setProperty("version", "1.0.0");
        var build = mock(ObjectProvider.class);
        when(build.getIfAvailable()).thenReturn(new BuildProperties(properties));
        ReflectionTestUtils.setField(controller, "buildProperties", build);
        ConsolePageProvider page = new ConsolePageProvider() {
            public String id() { return "asset-test"; }
            public String displayName() { return "Asset test"; }
            public String script() { return "asset-test.js"; }
        };
        var pages = mock(ObjectProvider.class);
        when(pages.orderedStream()).thenAnswer(invocation -> Stream.of(page));
        ReflectionTestUtils.setField(controller, "pageProviders", pages);
        var auth = mock(org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService.class);
        when(auth.isEnabled()).thenReturn(true);
        for (var role : ConfigUiAccount.Role.values()) {
            var session = mock(ConfigUiSession.class);
            when(session.getChannel()).thenReturn(ConfigUiSession.Channel.PASSWORD);
            when(auth.validate(role.name())).thenReturn(Optional.of(session));
            when(auth.account(session)).thenReturn(new ConfigUiAccount("asset-user", role,
                    ConfigUiAccount.Status.ENABLED, "Asset user", null, null, null, List.of(), Instant.EPOCH, null, true));
        }
        var filter = new ConfigUiSecurityFilter("operator-token-for-test-0123456789",
                new IpMatcher(List.of("0.0.0.0/0", "::/0")), auth,
                new NovaCoreProperties.ConfigUi.Auth(), ConfigUiPrefixCookieTest.accepted(ConfigUiSession.Channel.PASSWORD));
        return MockMvcBuilders.standaloneSetup(controller).addFilters(filter)
                .addInterceptors(new ConfigUiApiInterceptor(mock(org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService.class)))
                .build();
    }

    private MockHttpServletResponse request(MockMvc mvc, String path, ConfigUiAccount.Role role) throws Exception {
        return mvc.perform(get("/novabot" + path).contextPath("/novabot").with(request -> {
            ((MockServletContext) request.getServletContext()).setContextPath("/novabot");
            if (role != null) {
                request.setCookies(new jakarta.servlet.http.Cookie(ConfigUiSecurityFilter.SESSION_COOKIE, role.name()));
            }
            return request;
        })).andReturn().getResponse();
    }

    // 升级后旧脚本配新页面会让首屏永远停在加载中；同版本号重建也必须换地址。
    @Test void pageAddressesChangeWithBuildTime() throws Exception {
        String first = request(mvc(TIME), "/config", ConfigUiAccount.Role.ADMIN).getContentAsString(StandardCharsets.UTF_8);
        String nextTime = "2026-10-21T12:00:00Z";
        String nextVersion = Long.toString(Instant.parse(nextTime).toEpochMilli());
        String next = request(mvc(nextTime), "/config", ConfigUiAccount.Role.ADMIN).getContentAsString(StandardCharsets.UTF_8);
        var addresses = Pattern.compile("(?:href|src)=\"(/novabot/config/assets/[^\"]+)\"");
        for (var entry : List.of(new String[]{first, VERSION}, new String[]{next, nextVersion})) {
            var matcher = addresses.matcher(entry[0]);
            int count = 0;
            while (matcher.find()) {
                assertTrue(matcher.group(1).startsWith("/novabot/config/assets/" + entry[1] + "/"), matcher.group(1));
                count++;
            }
            assertTrue(count >= 5, "图标、样式和入口脚本均应使用版本地址");
            assertFalse(entry[0].contains("{{nova-"), "页面占位应已填好");
        }
        assertNotEquals(first, next);
    }

    // 慢网每次重下所有模块会拖住首屏；核心、插件脚本和相对导入都应能长期留在浏览器。
    @ParameterizedTest @ValueSource(strings = {"app.css", "main.js", "store.js", "asset-test.js"})
    void currentBuildUsesPrivateImmutableCache(String name) throws Exception {
        var mvc = mvc(TIME);
        for (var role : ConfigUiAccount.Role.values()) {
            var response = request(mvc, "/config/assets/" + VERSION + "/" + name, role);
            assertEquals(200, response.getStatus(), role.name());
            assertEquals(LONG_CACHE, response.getHeader("Cache-Control"));
            assertTrue(response.getContentAsByteArray().length > 0);
        }
    }

    // 升级前已打开的页面仍会请求旧版本，必须拿到当前内容且不能把它长缓存到旧地址。
    @ParameterizedTest @ValueSource(strings = {"main.js", "asset-test.js"})
    void staleBuildReturnsCurrentBytesWithoutLongCache(String name) throws Exception {
        var mvc = mvc(TIME);
        var response = request(mvc, "/config/assets/old-build/" + name, ConfigUiAccount.Role.ADMIN);
        assertEquals(200, response.getStatus());
        assertEquals("no-cache", response.getHeader("Cache-Control"));
        assertArrayEquals(request(mvc, "/config/assets/" + name, ConfigUiAccount.Role.ADMIN).getContentAsByteArray(),
                response.getContentAsByteArray());
    }

    // 升级瞬间旧页面还在用无版本地址，资源不能断掉，也不能改成长缓存。
    @ParameterizedTest @ValueSource(strings = {"main.js", "asset-test.js"})
    void legacyAddressStillWorksWithoutLongCache(String name) throws Exception {
        var response = request(mvc(TIME), "/config/assets/" + name, ConfigUiAccount.Role.ADMIN);
        assertEquals(200, response.getStatus());
        assertEquals("no-cache", response.getHeader("Cache-Control"));
    }

    // 缓存策略不能让退出登录后的资源请求绕过认证。
    @ParameterizedTest @ValueSource(strings = {"main.js", "old-build/main.js", "current/main.js", "current/asset-test.js"})
    void unauthenticatedRequestsRemainClosed(String name) throws Exception {
        var response = request(mvc(TIME), "/config/assets/" + name.replace("current/", VERSION + "/"), null);
        assertEquals(401, response.getStatus());
        assertNotEquals(LONG_CACHE, response.getHeader("Cache-Control"));
    }
}
