package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.ui.*;
import org.frostnova.nova.core.config.ui.auth.*;
import org.frostnova.nova.core.datasource.DataSourceServiceRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

class PushMemberPageTest {
    private static final String FIXTURE = "src/test/resources/frontend/push-member-fixture.mjs";
    @TempDir Path dir;

    // 各格在脚本入口说明对应的用户故障，运行真实页面的事件与请求。
    @ParameterizedTest
    @ValueSource(strings = {"controls", "defaults", "exact", "invalid", "platforms", "duplicate", "rejected", "names",
            "stranded", "recent", "quiet", "wizard", "malformed"})
    void pageBehaviour(String scenario) throws Exception {
        FrontendFixture.runFromModule(FIXTURE, scenario, Map.of("PUSH_CASE", scenario));
    }

    // 成员页面的每个实际请求均通过真实路由、权限拦截器与请求体权限检查，避免假回包掩盖越权。
    @Test void memberRequestsPassProductionPermissions() throws Exception {
        Path trace = dir.resolve("requests.json");
        FrontendFixture.runFromModule(FIXTURE, "成员请求", Map.of("PUSH_CASE", "permissions", "PUSH_TRACE", trace.toString()));
        var platforms = mock(DataSourceServiceRegistry.class);
        when(platforms.platforms()).thenReturn(List.of("bilibili"));
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        var controllers = new ArrayList<Object>();
        for (var bean : scanner.findCandidateComponents("org.frostnova.nova")) {
            if (!bean.getBeanClassName().contains("$")) controllers.add(mock(Class.forName(bean.getBeanClassName())));
        }
        var mvc = MockMvcBuilders.standaloneSetup(controllers.toArray())
                .addInterceptors(new ConfigUiApiInterceptor(mock(ConfigUiAuthService.class), null, platforms))
                .setControllerAdvice(new ConfigUiMemberBodyAdvice(platforms)).build();
        var member = new ConfigUiAccount("member", ConfigUiAccount.Role.MEMBER, ConfigUiAccount.Status.ENABLED,
                "Member", null, null, null, List.of("bilibili:1"), Instant.EPOCH, null, true);
        var denied = new ArrayList<String>();
        JSONArray requests = JSONArray.parseArray(Files.readString(trace));
        for (Object value : requests) {
            JSONObject entry = (JSONObject)value;
            URI uri = URI.create(entry.getString("url"));
            var req = request(HttpMethod.valueOf(entry.getString("method")), uri.getPath())
                    .requestAttr(ConfigUiAccess.ACCOUNT, member).contentType("application/json");
            if (uri.getRawQuery() != null) for (String pair : uri.getRawQuery().split("&")) {
                String[] kv = pair.split("=", 2);
                req.param(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
            if (entry.get("body") != null) req.content(entry.getJSONObject("body").toJSONString());
            var result = mvc.perform(req).andReturn();
            assertNotNull(result.getHandler(), entry.toJSONString());
            if (result.getResponse().getStatus() == 403) denied.add(entry.getString("method") + " " + uri + " " + result.getResponse().getContentAsString(StandardCharsets.UTF_8));
            else assertEquals(200, result.getResponse().getStatus(), entry.toJSONString());
        }
        System.out.println("Member request replay: " + requests.size() + " requests; denied=" + denied);
        assertEquals(List.of(), denied);
    }
}
