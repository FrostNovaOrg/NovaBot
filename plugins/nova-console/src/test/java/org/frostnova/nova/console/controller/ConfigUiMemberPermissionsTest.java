package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.*;
import org.frostnova.nova.core.config.ui.auth.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ConfigUiMemberPermissionsTest {
    private static final String API = "/config/api/";
    private static final Set<String> EVERYONE = routes("""
            GET /config
            GET /config/
            GET /config/assets/{name}
            GET /config/manual/{name}
            GET /config/manual/images/{name}
            GET auth/state
            POST auth/login
            POST auth/logout
            POST auth/passkey/login/options
            POST auth/passkey/login/verify
            GET oidc/login
            GET oidc/callback
            POST oidc/backchannel-logout
            GET auth/agreement
            GET vocab
            GET platforms
            GET session-check
            GET setup/state
            GET handlers
            GET templates
            GET report/layout-options
            """);
    private static final Set<String> SCOPED = routes("""
            GET datasource/users/{platform}/{uid}
            GET datasource/users/{platform}/{uid}/version
            POST datasource/users/{platform}/{uid}
            GET streamers/{platform}/{uid}
            GET streamers/{platform}/{uid}/insights
            GET streamers/{platform}/{uid}/audience
            GET streamers/{platform}/{uid}/calendar
            GET streamers/{platform}/{uid}/fans
            GET streamers/{platform}/{uid}/sessions
            GET streamers/{platform}/{uid}/trend
            GET streamers/{platform}/{uid}/start-slots
            GET streamers/{platform}/{uid}/weekly-report
            GET streamers/{platform}/{uid}/live
            GET streamers/{platform}/{uid}/sessions/{start}/report
            GET streamer/name
            POST streamer/lookup
            POST state/subscription
            GET weekly-report/{platform}/{uid}/status
            GET weekly-report/{platform}/{uid}/preview
            POST weekly-report/{platform}/{uid}/trial
            POST report/preview
            """);
    private static final Set<String> FILTERED = routes("""
            GET pages
            GET datasource/users
            GET status
            GET timeline
            GET streamers
            GET state
            GET push-history
            GET at-all/quota
            GET bot/targets/lookup
            """);
    private static final Set<String> ADMIN = routes("""
            GET /config/napcat-bootstrap
            GET /config/napcat-bootstrap/napcat-resume.js
            POST auth/agreement/accept
            POST auth/agreement/revoke
            GET auth/totp/setup
            POST auth/totp/enroll
            POST auth/totp/disable
            POST auth/totp/skip
            POST auth/password/set
            POST auth/password/change
            POST auth/password/reset
            POST auth/passkey/register/options
            POST auth/passkey/register/verify
            GET auth/passkeys
            DELETE auth/passkeys/{id}
            GET accounts
            POST accounts/{id}/enable
            POST accounts/{id}/disable
            POST accounts/{id}/role
            POST accounts/{id}/streamers
            DELETE accounts/{id}
            POST accounts/local/bind
            DELETE accounts/local/binding
            GET schema
            GET values
            POST values
            GET event-tokens
            POST event-tokens
            POST event-tokens/{fingerprint}/revoke
            GET setup/bot
            POST setup/bot
            POST setup/test-bot
            GET login
            POST login/logout
            POST test-message
            GET datasource
            POST datasource
            POST datasource/users
            POST datasource/users/{platform}/{uid}/delete
            POST version/skip
            GET alert/channels
            POST alert/test
            POST setup/test-sent
            POST setup/rerun
            POST setup/rerun/consumed
            GET engineering-log
            GET timeline/days
            POST push/toggle
            POST templates
            POST state/command
            POST state/commands
            POST state/revenue
            POST state/quiet
            POST state/binding
            GET analytics
            GET analytics/sessions
            GET bot/console
            POST napcat/credential
            GET bot/targets
            POST bot/targets/refresh
            """);
    private static Set<String> routes(String text) {
        var result = new TreeSet<String>();
        text.lines().map(String::trim).filter(s -> !s.isEmpty()).forEach(line -> {
            String[] pair = line.split(" ", 2);
            result.add(pair[0] + " " + (pair[1].startsWith("/") ? pair[1] : API + pair[1]));
        });
        return result;
    }
    private static final ConfigUiAccount MEMBER = new ConfigUiAccount("member", ConfigUiAccount.Role.MEMBER,
            ConfigUiAccount.Status.ENABLED, "Member", null, null, null, List.of("bilibili:1"), Instant.EPOCH, null, true);
    private static MockMvc mvc;
    private static org.frostnova.nova.core.datasource.DataSourceServiceRegistry platforms;
    private static Map<String, org.springframework.web.method.HandlerMethod> mappings;

    @BeforeAll static void setup() throws Exception {
        platforms = mock(org.frostnova.nova.core.datasource.DataSourceServiceRegistry.class);
        when(platforms.platforms()).thenReturn(List.of("bilibili"));
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        var controllers = new ArrayList<Object>();
        for (var bean : scanner.findCandidateComponents("org.frostnova.nova")) {
            if (!bean.getBeanClassName().contains("$")) controllers.add(mock(Class.forName(bean.getBeanClassName())));
        }
        var builder = MockMvcBuilders.standaloneSetup(controllers.toArray())
                .addInterceptors(new ConfigUiApiInterceptor(mock(ConfigUiAuthService.class), null, platforms));
        builder.setControllerAdvice(new ConfigUiMemberBodyAdvice(platforms));
        mvc = builder.build();
        var mapping = mvc.getDispatcherServlet().getWebApplicationContext().getBean(RequestMappingHandlerMapping.class);
        mappings = new TreeMap<>();
        mapping.getHandlerMethods().forEach((info, handler) -> info.getPatternValues().stream()
                .filter(path -> path.equals("/config") || path.startsWith("/config/"))
                .forEach(path -> info.getMethodsCondition().getMethods()
                        .forEach(method -> mappings.put(method + " " + path, handler))));
    }

    // 新接口忘了指定权限时，覆盖检查必须报出它，运行时仍默认只给管理员。
    @Test void everySpringHandlerHasAnExplicitClassification() throws Exception {
        var expected = new TreeSet<String>(); expected.addAll(EVERYONE); expected.addAll(SCOPED); expected.addAll(ADMIN); expected.addAll(FILTERED);
        assertEquals(expected, mappings.keySet());
        assertEquals(111, mappings.size());
        assertEquals(21, EVERYONE.size()); assertEquals(21, SCOPED.size()); assertEquals(60, ADMIN.size()); assertEquals(9, FILTERED.size());
        var entries = ConfigUiPermissions.entries();
        assertEquals(expected, new TreeSet<>(entries.keySet()));
        EVERYONE.forEach(route -> assertEquals(ConfigUiPermissions.Access.EVERYONE, entries.get(route).access(), route));
        SCOPED.forEach(route -> assertEquals(ConfigUiPermissions.Access.STREAMER, entries.get(route).access(), route));
        FILTERED.forEach(route -> assertEquals("FILTERED", entries.get(route).access().name(), route));
        ADMIN.forEach(route -> assertEquals(ConfigUiPermissions.Access.ADMIN, entries.get(route).access(), route));
        System.out.println("Config handlers: " + mappings.size() + "; everyone="
                + entries.values().stream().filter(rule -> rule.access() == ConfigUiPermissions.Access.EVERYONE).count()
                + "; streamer=" + entries.values().stream().filter(rule -> rule.access() == ConfigUiPermissions.Access.STREAMER).count()
                + "; filtered=" + entries.values().stream().filter(rule -> rule.access().name().equals("FILTERED")).count()
                + "; admin=" + entries.values().stream().filter(rule -> rule.access() == ConfigUiPermissions.Access.ADMIN).count());
    }

    // 成员不能改实例、管理账号或看日志；管理员仍能到达每一个原处理器。
    @Test void everyAdminHandlerRejectsMembersAndKeepsAdministrators() throws Exception {
        for (String route : ADMIN) {
            mvc.perform(request(route, "1", MEMBER)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("成员不能使用这一项"));
            mvc.perform(request(route, "1", ConfigUiAccount.local(Instant.EPOCH))).andExpect(status().isOk());
            var admin = new ConfigUiAccount("oidc-admin", ConfigUiAccount.Role.ADMIN, MEMBER.status(), "Admin",
                    null, null, null, List.of(), Instant.EPOCH, null, true);
            mvc.perform(request(route, "1", admin)).andExpect(status().isOk());
        }
    }

    // 成员能到达自己的主播各口，别人的主播和不存在的主播回相同的内容。
    @Test void everyScopedHandlerAllowsOwnerAndConcealsOtherStreamers() throws Exception {
        for (String route : SCOPED) {
            if (route.endsWith("/trial")) continue;
            mvc.perform(request(route, "1", MEMBER)).andExpect(status().isOk());
            var b = mvc.perform(request(route, "2", MEMBER)).andExpect(status().isNotFound()).andReturn();
            var c = mvc.perform(request(route, "3", MEMBER)).andExpect(status().isNotFound()).andReturn();
            assertEquals(b.getResponse().getContentAsString(), c.getResponse().getContentAsString(), route);
            assertEquals("没有这位主播", JSONObject.parseObject(b.getResponse().getContentAsString()).getString("message"));
        }
    }

    // 登录、资源和公共选项对成员开放，避免成员进来以后界面所需接口全被拒绝。
    @Test void everySharedHandlerAllowsMembers() throws Exception {
        for (String route : java.util.stream.Stream.concat(EVERYONE.stream(), FILTERED.stream()).toList()) mvc.perform(request(route, "1", MEMBER)).andExpect(status().isOk());
    }

    // 全群名单、刷新、登录及日期总数仍不能向成员开放。
    @Test void instanceListsRemainDenied() throws Exception {
        for (String path : List.of("bot/targets", "timeline/days", "login"))
            mvc.perform(request("GET " + API + path, "1", MEMBER)).andExpect(status().isForbidden());
        mvc.perform(request("POST " + API + "bot/targets/refresh", "1", MEMBER)).andExpect(status().isForbidden());
    }

    // 旧会话设置会改全群主播，成员必须从自己的主播配置修改。
    @Test void oldSessionSettingsRemainAdminOnly() throws Exception {
        for (String path : List.of("command", "commands", "revenue", "quiet"))
            mvc.perform(request("POST " + API + "state/" + path, "1", MEMBER)).andExpect(status().isForbidden());
    }

    // 查主播的链接、非法编号、预览缺一半身份都不能用来探查别的主播。
    @Test void malformedSubjectsAndLookupLinksAreConcealed() throws Exception {
        for (String route : SCOPED) {
            if (route.endsWith("/trial")) continue;
            mvc.perform(request(route, "bad", MEMBER)).andExpect(status().isNotFound());
        }
        for (String raw : List.of("https://space.bilibili.com/1", "https://live.bilibili.com/1", "0", "-1", "1.5"))
            mvc.perform(post(API + "streamer/lookup").requestAttr(ConfigUiAccess.ACCOUNT, MEMBER)
                    .contentType("application/json").content(JSONObject.of("platform", "bilibili", "uid", raw).toJSONString()))
                    .andExpect(status().isNotFound());
        for (String body : List.of("{\"streamerPlatform\":\"bilibili\"}", "{\"streamerUid\":1}",
                "{\"streamerPlatform\":null,\"streamerUid\":null}"))
            mvc.perform(post(API + "report/preview").requestAttr(ConfigUiAccess.ACCOUNT, MEMBER)
                    .contentType("application/json").content(body)).andExpect(status().isNotFound());
        mvc.perform(post(API + "report/preview").requestAttr(ConfigUiAccess.ACCOUNT, MEMBER)
                .contentType("application/json").content("{}")).andExpect(status().isOk());
    }

    // Spring 能读出的数字按同一数值核对，多值平台不能只拿第一项误放行。
    @Test void numericBindingAndRepeatedQueryValuesMatchControllerParsing() throws Exception {
        for (String uid : List.of("0001", "+1", "0x1")) {
            mvc.perform(request("GET " + API + "streamers/{platform}/{uid}", uid, MEMBER)).andExpect(status().isOk());
            mvc.perform(request("GET " + API + "streamer/name", uid, MEMBER)).andExpect(status().isOk());
        }
        mvc.perform(get(API + "streamer/name").requestAttr(ConfigUiAccess.ACCOUNT, MEMBER)
                .param("platform", "bilibili", "other").param("uid", "1")).andExpect(status().isNotFound());
        mvc.perform(get(API + "streamer/name").requestAttr(ConfigUiAccess.ACCOUNT, MEMBER)
                .param("platform", "bilibili").param("uid", "1", "2")).andExpect(status().isOk());
    }

    // 旧名单中写错或已卸载的平台及带前导零的编号不能授予成员读取权限。
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"BILIBILI:1", "哔哩:1", "bilibili:01", "unknown:1"})
    void legacyAssignmentsCannotGrantReadAccess(String assignment) throws Exception {
        var account = new ConfigUiAccount(MEMBER.id(), MEMBER.role(), MEMBER.status(), MEMBER.displayName(),
                null, null, null, List.of(assignment), Instant.EPOCH, null, true);
        String platform = assignment.substring(0, assignment.indexOf(':'));
        mvc.perform(get(API + "streamers/" + platform + "/1").requestAttr(ConfigUiAccess.ACCOUNT, account))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("没有这位主播"));
    }

    // 整机组件扫描后的处理器表不能出现其他测试专用的接口。
    @Test void scannedControllersDoNotExposeTemporaryHandlers() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        var controllers = new ArrayList<Object>();
        for (var bean : scanner.findCandidateComponents("org.frostnova.nova"))
            controllers.add(mock(Class.forName(bean.getBeanClassName())));
        var app = MockMvcBuilders.standaloneSetup(controllers.toArray()).build();
        var handlers = app.getDispatcherServlet().getWebApplicationContext().getBean(RequestMappingHandlerMapping.class);
        var paths = handlers.getHandlerMethods().keySet().stream().flatMap(info -> info.getPatternValues().stream()).toList();
        assertFalse(paths.contains("/config/api/future"), paths.toString());
        assertFalse(paths.contains("/config/future"), paths.toString());
    }

    static class FutureController {
        public String future() { return "ok"; }
    }
    // 未登记在表中的 API 和页面都默认拒绝成员。
    @Test void unknownHandlersDefaultToAdmin() throws Exception {
        var future = MockMvcBuilders.standaloneSetup()
                .addInterceptors(new ConfigUiApiInterceptor(mock(ConfigUiAuthService.class), null, platforms)).build();
        var handlers = future.getDispatcherServlet().getWebApplicationContext().getBean(RequestMappingHandlerMapping.class);
        handlers.registerMapping(org.springframework.web.servlet.mvc.method.RequestMappingInfo
                        .paths("/config/api/future", "/config/future").methods(RequestMethod.GET)
                        .options(handlers.getBuilderConfiguration()).build(),
                new FutureController(), FutureController.class.getMethod("future"));
        for (String path : List.of("/config/api/future", "/config/future")) {
            future.perform(get(path).requestAttr(ConfigUiAccess.ACCOUNT, MEMBER)).andExpect(status().isForbidden());
            future.perform(get(path).requestAttr(ConfigUiAccess.ACCOUNT, ConfigUiAccount.local(Instant.EPOCH)))
                    .andExpect(status().isOk());
        }
    }

    private static MockHttpServletRequestBuilder request(String route, String uid, ConfigUiAccount account) {
        String[] pair = route.split(" ", 2);
        String path = pair[1].replace("{platform}", "bilibili").replace("{uid}", uid)
                .replace("{start}", "1").replace("{name}", "core.js").replace("{id}", "member")
                .replace("{fingerprint}", "test");
        var req = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                org.springframework.http.HttpMethod.valueOf(pair[0]), path)
                .requestAttr(ConfigUiAccess.ACCOUNT, account).contentType("application/json")
                .param("platform", "bilibili").param("uid", uid).param("pushPlatform", "qq-onebot")
                .param("type", "1").param("num", "1001");
        var method = mappings.get(route);
        if (method != null) for (var parameter : method.getMethodParameters()) {
            var annotation = parameter.getParameterAnnotation(RequestParam.class);
            if (annotation == null || !annotation.required() || !annotation.defaultValue().equals(ValueConstants.DEFAULT_NONE)) continue;
            String name = annotation.name().isEmpty() ? annotation.value() : annotation.name();
            if (name.isEmpty()) name = parameter.getParameter().getName();
            if (!List.of("platform", "uid", "pushPlatform", "type", "num").contains(name)) req.param(name, "1");
        }
        return req.content(JSONObject.of("platform", "bilibili", "uid", uid,
                "streamerPlatform", "bilibili", "streamerUid", uid).toJSONString());
    }
}
