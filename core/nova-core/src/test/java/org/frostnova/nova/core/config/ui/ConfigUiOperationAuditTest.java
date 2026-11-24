package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.datasource.DataSourceServiceRegistry;
import org.frostnova.nova.core.command.CommandSettingsService;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.service.RevenueVisibilityService;
import org.frostnova.nova.core.service.SessionQuietHoursService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ConfigUiOperationAuditTest {
    @TempDir Path directory;
    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    static final ConfigUiAccount MEMBER = new ConfigUiAccount("member-7", ConfigUiAccount.Role.MEMBER,
            ConfigUiAccount.Status.ENABLED, "小明", "member@example.invalid", "private-issuer", "private-subject",
            List.of("bilibili:123"), Instant.EPOCH, null, true);

    @BeforeEach void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        var properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(directory.resolve("live.json").toString());
        context.register(Setup.class);
        context.addBeanFactoryPostProcessor(factory -> factory.registerSingleton("properties", properties));
        context.refresh();
        var builder = MockMvcBuilders.webAppContextSetup(context);
        context.getBeansOfType(FilterRegistrationBean.class).values().stream()
                .sorted(Comparator.comparingInt(FilterRegistrationBean::getOrder))
                .forEach(bean -> builder.addFilters((Filter) bean.getFilter()));
        mvc = builder.build();
    }
    @AfterEach void close() { context.close(); }

    @Configuration @EnableWebMvc
    @Import({ConfigUiOperationConfiguration.class, ConfigUiOperationAdvice.class, ConfigUiOperationStore.class,
            ConfigUiSessionSettingsController.class})
    static class Setup {
        @Bean NovaStateStore state(NovaCoreProperties properties) { return new NovaStateStore(properties); }
        @Bean org.frostnova.nova.core.config.ui.auth.oidc.OidcClient oidc() {
            return mock(org.frostnova.nova.core.config.ui.auth.oidc.OidcClient.class);
        }
        @Bean org.frostnova.nova.core.config.ui.auth.ConfigUiAccountStore accounts() {
            return mock(org.frostnova.nova.core.config.ui.auth.ConfigUiAccountStore.class);
        }
        @Bean Endpoints endpoints() { return new Endpoints(); }
        @Bean ConfigUiAuthService auth() { return mock(ConfigUiAuthService.class); }
        @Bean ConfigUiApiInterceptor permissions(ConfigUiAuthService auth) {
            return new ConfigUiApiInterceptor(auth, new NovaCoreProperties(), new DataSourceServiceRegistry(List.of()));
        }
    }

    @RestController static class Endpoints {
        @PostMapping("/config/api/**") JSONObject change(@RequestBody(required = false) Map<String, Object> body,
                                                        HttpServletRequest request) {
            if (body != null && Boolean.TRUE.equals(body.get("refuse")))
                return JSONObject.of("success", false, "message", "secret-response-value");
            return JSONObject.of("success", true, "token", "secret-response-value");
        }
    }

    private List<JSONObject> rows() throws Exception {
        Path folder = directory.resolve("console-audit");
        assertTrue(Files.isDirectory(folder), "事后必须能从数据目录找到操作记录");
        try (var files = Files.list(folder)) {
            List<JSONObject> rows = new ArrayList<>();
            for (Path file : files.filter(path -> path.toString().endsWith(".jsonl")).toList())
                for (String line : Files.readAllLines(file)) rows.add(JSONObject.parseObject(line));
            return rows;
        }
    }

    @Test
    void groupMigrationCopiesQuietHoursAndCommandsWithoutReplacingRevenue() throws Exception {
        var state = context.getBean(NovaStateStore.class);
        String original = "qq:1:101";
        String official = "qq-official:1:1000000000000001";
        String next = "qq-official:1:1000000000000002";
        var quiet = JSONObject.of("mode", "custom", "start", "23:00", "end", "07:00");
        var commands = JSONObject.of("直播报告", 1);
        state.write(RevenueVisibilityService.NAMESPACE, values -> {
            values.put(original, false);
            values.put(official, true);
            values.put("qq:0:101", true);
        });
        state.write(CommandSettingsService.NAMESPACE, values -> values.put(original, commands));
        state.write(SessionQuietHoursService.NAMESPACE, values -> values.put(original, quiet));

        mvc.perform(post("/config/api/state/copy-to-official-group")
                        .requestAttr(ConfigUiAccess.ACCOUNT, ConfigUiAccount.local(Instant.EPOCH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(migrationBody(1000000000000001L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.skipped[0]").value("金额可见"));
        var action = rows().get(0);
        assertEquals("把推送目标换成官方机器人", action.getString("description"));
        var channels = action.getJSONObject("target").getJSONArray("channels");
        assertTrue(channels.contains(JSONObject.of("platform", "qq", "type", "1", "num", "101")));
        assertTrue(channels.contains(JSONObject.of("platform", "qq-official", "type", "1", "num", "1000000000000001")));

        mvc.perform(post("/config/api/state/copy-to-official-group")
                        .requestAttr(ConfigUiAccess.ACCOUNT, ConfigUiAccount.local(Instant.EPOCH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(migrationBody(1000000000000002L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skipped").isEmpty());
        var disk = JSONObject.parseObject(Files.readString(directory.resolve("state.json")));
        var revenue = disk.getJSONObject(RevenueVisibilityService.NAMESPACE);
        assertEquals(false, revenue.get(original));
        assertEquals(false, revenue.get(next));
        assertEquals(true, revenue.get(official));
        assertEquals(quiet, disk.getJSONObject(SessionQuietHoursService.NAMESPACE).getJSONObject(official));
        assertEquals(commands, disk.getJSONObject(CommandSettingsService.NAMESPACE).getJSONObject(official));
        state.write(CommandSettingsService.NAMESPACE, values -> values.getJSONObject(original).clear());
        assertTrue(state.namespace(CommandSettingsService.NAMESPACE).getJSONObject(official).containsKey("直播报告"));
    }

    @Test
    void groupMigrationCannotReportSuccessOrLeaveCachedCopiesWhenStateCannotBeSaved() throws Exception {
        var state = context.getBean(NovaStateStore.class);
        state.write(RevenueVisibilityService.NAMESPACE, values -> values.put("qq:1:101", false));
        Files.createDirectory(directory.resolve("state.json"));
        mvc.perform(post("/config/api/state/copy-to-official-group")
                        .requestAttr(ConfigUiAccess.ACCOUNT, ConfigUiAccount.local(Instant.EPOCH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(migrationBody(1000000000000001L)))
                .andExpect(status().is5xxServerError())
                .andExpect(jsonPath("$.success").value(false));
        assertFalse(state.namespace(RevenueVisibilityService.NAMESPACE).containsKey("qq-official:1:1000000000000001"));
        assertEquals(false, state.namespace(RevenueVisibilityService.NAMESPACE).get("qq:1:101"));
    }

    @Test
    void groupMigrationAcceptsExistingCustomSenderNames() throws Exception {
        var state = context.getBean(NovaStateStore.class);
        state.write(RevenueVisibilityService.NAMESPACE, values -> values.put("group.bot:1:101", false));
        var body = JSONObject.parseObject(migrationBody(1000000000000001L)).fluentPut("platform", "group.bot");
        mvc.perform(post("/config/api/state/copy-to-official-group")
                        .requestAttr(ConfigUiAccess.ACCOUNT, ConfigUiAccount.local(Instant.EPOCH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toJSONString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        assertEquals(false, state.namespace(RevenueVisibilityService.NAMESPACE).get("qq-official:1:1000000000000001"));
    }

    private static String migrationBody(long officialNum) {
        return JSONObject.of("streamerPlatform", "bilibili", "streamerUid", 123, "platform", "qq",
                "type", 1, "num", 101, "officialNum", officialNum).toJSONString();
    }

    @DisplayName("事后查不到是谁改了设置或推送通道：每类操作各写一行并保留身份与对象")
    @ParameterizedTest @CsvSource({
        "values,SETTINGS_SAVE", "setup/bot,BOT_SAVE", "login/logout,PLATFORM_LOGOUT",
        "test-message,MESSAGE_TEST", "datasource,DATASOURCE_SAVE", "datasource/users,STREAMER_ADD",
        "push/toggle,PUSH_TOGGLE", "templates,TEMPLATE_SAVE", "state/command,COMMAND_SAVE",
        "state/commands,COMMANDS_SAVE", "state/revenue,REVENUE_SAVE", "state/quiet,QUIET_SAVE",
        "state/subscription,SUBSCRIPTION_REMOVE", "state/binding,BINDING_SAVE",
        "streamers/bilibili/123/assistant/ask,ASSISTANT_ASK",
        "weekly-report/bilibili/123/trial,WEEKLY_REPORT_SEND", "live-report/bilibili/123/send-latest,LIVE_REPORT_SEND",
        "napcat/credential,NAPCAT_CREDENTIAL", "report/palettes/default,PALETTE_SAVE", "alert/test,ALERT_TEST",
        "accounts/member-8/enable,ACCOUNT_ENABLE", "accounts/member-8/disable,ACCOUNT_DISABLE",
        "accounts/member-8/role,ACCOUNT_ROLE", "accounts/member-8/streamers,ACCOUNT_STREAMERS",
        "auth/login,LOGIN", "auth/logout,LOGOUT", "event-tokens,EVENT_TOKEN_ISSUE"
    })
    void recordsEachOperation(String route, String action) throws Exception {
        mvc.perform(post("/config/api/" + route).requestAttr(ConfigUiAccess.ACCOUNT, ConfigUiAccount.local(Instant.EPOCH))
                .contentType(MediaType.APPLICATION_JSON).content("{\"platform\":\"bilibili\",\"uid\":123,\"type\":1,\"num\":456}"))
                .andExpect(status().isOk());
        var rows = rows();
        assertEquals(1, rows.size(), "一次操作只能写一行");
        JSONObject row = rows.get(0);
        assertEquals(action, row.getString("action"));
        assertTrue(row.getLongValue("timestamp") > 0);
        assertEquals("local-admin", row.getJSONObject("actor").getString("id"));
        assertEquals("本地管理员", row.getJSONObject("actor").getString("displayName"));
        assertEquals("ADMIN", row.getJSONObject("actor").getString("role"));
        assertFalse(row.getString("description").isBlank());
        assertEquals("SUCCESS", row.getString("result"));
        assertFalse(row.containsKey("reason"));
        var target = row.getJSONObject("target");
        if (!route.equals("values")) {
            assertTrue(target.getJSONArray("streamers").contains(JSONObject.of("platform", "bilibili", "uid", "123")));
            assertTrue(target.getJSONArray("channels").contains(JSONObject.of("platform", "bilibili", "type", "1", "num", "456")));
        }
        if (route.startsWith("accounts/")) assertEquals("member-8", target.getString("accountId"));
        assertFalse(row.toJSONString().contains("secret-response-value"));
    }

    @Test @DisplayName("把密钥写进操作记录：设置请求与回包的值一律不保存")
    void neverRecordsSettingValuesOrPrivateIdentity() throws Exception {
        // 请求在权限检查之前被拒，仍不能把设置值与登录身份写到记录。
        mvc.perform(post("/config/api/values").requestAttr(ConfigUiAccess.ACCOUNT, MEMBER)
                .contentType(MediaType.APPLICATION_JSON).content("{\"novabot.core.secret\":\"private-secret-value\"}"))
                .andExpect(status().isForbidden());
        var row = rows().get(0);
        assertEquals("member-7", row.getJSONObject("actor").getString("id"));
        assertEquals("小明", row.getJSONObject("actor").getString("displayName"));
        assertEquals("MEMBER", row.getJSONObject("actor").getString("role"));
        String json = row.toJSONString();
        for (String secret : List.of("private-secret-value", "member@example.invalid", "private-issuer", "private-subject"))
            assertFalse(json.contains(secret), "不能记录凭据或私有登录身份");
    }

    @Test @DisplayName("权限不够被拒却查不到：拒绝也落一行并说明原因")
    void recordsPermissionDenial() throws Exception {
        mvc.perform(post("/config/api/push/toggle").requestAttr(ConfigUiAccess.ACCOUNT, MEMBER)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        var row = rows().get(0);
        assertEquals("REJECTED", row.getString("result"));
        assertTrue(row.getString("reason").contains("成员"));
    }

    @Test @DisplayName("接口返回失败却被记成成功：识别 HTTP 200 中的拒绝结果且不保存响应内容")
    void recordsBusinessDenial() throws Exception {
        mvc.perform(post("/config/api/push/toggle").requestAttr(ConfigUiAccess.ACCOUNT, ConfigUiAccount.local(Instant.EPOCH))
                .contentType(MediaType.APPLICATION_JSON).content("{\"refuse\":true}"))
                .andExpect(status().isOk());
        assertEquals("REJECTED", rows().get(0).getString("result"));
        assertFalse(rows().get(0).toJSONString().contains("secret-response-value"));
    }

    @Test @DisplayName("写盘失败拖垮操作：记录目录不可写时仍返回成功")
    void writeFailureDoesNotFailOperation() throws Exception {
        Files.writeString(directory.resolve("console-audit"), "blocked");
        mvc.perform(post("/config/api/push/toggle").requestAttr(ConfigUiAccess.ACCOUNT, ConfigUiAccount.local(Instant.EPOCH))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
    }
}
