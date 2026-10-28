package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.http.Cookie;
import org.frostnova.nova.core.NovaCoreApplication;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.config.ui.auth.PasswordHash;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = NovaCoreApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"spring.config.location=optional:classpath:/operation-context.yml",
                "novabot.core.config-ui.token=8vQ4sL2nW7rX9jH5mD3pF6kC",
                "novabot.core.config-ui.update.enabled=false", "novabot.core.config-ui.auth.totp=false"})
@DirtiesContext
class ConfigUiOperationContextTest {
    @TempDir static Path directory;
    @Autowired WebApplicationContext context;
    @Autowired ConfigUiAuthService auth;
    @Autowired NovaCoreProperties properties;
    @TestBean(methodName = "portContext") WebServerApplicationContext serverContext;
    @TestBean(methodName = "configurationFile") ConfigurationFileService fileService;

    @DynamicPropertySource
    static void paths(DynamicPropertyRegistry registry) {
        registry.add("novabot.core.live.live-data-path", () -> directory.resolve("live.json").toString());
        registry.add("novabot.core.datasource.json-path", () -> directory.resolve("datasource.json").toString());
        registry.add("logging.file.path", () -> directory.resolve("logs").toString());
    }

    static WebServerApplicationContext portContext() {
        var server = mock(WebServer.class);
        when(server.getPort()).thenReturn(7827);
        var context = mock(WebServerApplicationContext.class);
        when(context.getWebServer()).thenReturn(server);
        return context;
    }

    static ConfigurationFileService configurationFile() throws Exception {
        Path file = directory.resolve("application.yml");
        Files.writeString(file, "novabot:\n  core:\n    config-ui:\n      backup-keep: 2\n");
        return new ConfigurationFileService(file);
    }

    @Test @DisplayName("正式运行时过滤器未注册或排在权限拦截之后导致记录缺失：完整上下文验证拒绝与登录后的真实保存")
    void productionRegistrationRecordsDeniedAndSuccessfulWrites() throws Exception {
        auth.applyPasswordHash(PasswordHash.hash("context-password".toCharArray()));
        properties.getConfigUi().getAgreement().setAcceptedVersion(ConfigUiAgreement.VERSION);
        properties.getConfigUi().getAgreement().setAcceptedBy("test");
        var builder = MockMvcBuilders.webAppContextSetup(context);
        // 使用正式注册的路径、顺序和启用状态；MockMvc 不启动监听端口。
        context.getBeansOfType(FilterRegistrationBean.class).values().stream()
                .map(bean -> (FilterRegistrationBean<?>) bean)
                .filter(FilterRegistrationBean::isEnabled)
                .sorted(Comparator.comparingInt(FilterRegistrationBean::getOrder))
                .forEach(bean -> builder.addFilter(bean.getFilter(),
                        bean.getUrlPatterns().toArray(String[]::new)));
        var mvc = builder.build();
        mvc.perform(post("/config/api/values").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        var rows = rows();
        assertEquals(1, rows.size(), "未登录写请求必须经过操作过滤器");
        assertEquals("SETTINGS_SAVE", rows.get(0).getString("action"));
        assertEquals("REJECTED", rows.get(0).getString("result"));
        assertNull(rows.get(0).getJSONObject("actor").getString("id"));

        var login = mvc.perform(post("/config/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"context-password\"}"))
                .andExpect(status().isOk()).andReturn().getResponse();
        Cookie session = login.getCookie(ConfigUiSecurityFilter.SESSION_COOKIE);
        String csrf = JSONObject.parseObject(login.getContentAsString()).getString("csrfToken");
        assertNotNull(session);
        assertNotNull(csrf);
        int before = rows().size();
        mvc.perform(post("/config/api/values").cookie(session)
                        .header(ConfigUiSecurityFilter.CSRF_HEADER, csrf).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"novabot.core.config-ui.backup-keep\":\"7\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        rows = rows();
        assertEquals(before + 1, rows.size(), "保存设置只能增加一行");
        var saved = rows.get(rows.size() - 1);
        assertEquals("SETTINGS_SAVE", saved.getString("action"));
        assertEquals("SUCCESS", saved.getString("result"));
        assertEquals("local-admin", saved.getJSONObject("actor").getString("id"));
        assertEquals(java.util.List.of("novabot.core.config-ui.backup-keep"), saved.getJSONObject("target").getList("keys", String.class));
        assertEquals("7", fileService.read().get("novabot.core.config-ui.backup-keep"));
    }

    private java.util.List<JSONObject> rows() throws Exception {
        Path folder = directory.resolve("console-audit");
        if (!Files.isDirectory(folder)) return java.util.List.of();
        try (var files = Files.list(folder)) {
            var result = new java.util.ArrayList<JSONObject>();
            for (Path file : files.toList())
                for (String line : Files.readAllLines(file)) result.add(JSONObject.parseObject(line));
            return result;
        }
    }
}
