package org.frostnova.nova.adapter.qqbot;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigUiAccess;
import org.frostnova.nova.core.config.ui.ConfigUiApiInterceptor;
import org.frostnova.nova.core.config.ui.ConfigUiOperationAdvice;
import org.frostnova.nova.core.config.ui.ConfigUiOperationFilter;
import org.frostnova.nova.core.config.ui.ConfigUiOperationProvider;
import org.frostnova.nova.core.config.ui.ConfigUiOperationStore;
import org.frostnova.nova.core.config.ui.ConfigUiPushTargetsController;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.config.ui.page.ConsolePageProvider;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.service.NovaSenderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class QqBotConsoleControllerTest {
    private static final String BASE = "/config/api/qqbot/";
    private static final String APP_ID = "12345678";
    private static final String SECRET = "fixture-console-secret";
    private static final String GROUP = "GROUP12345678FIXTURE";
    private static final String REMOVED = "REMOVED12345678FIXTURE";
    private static final Instant JOINED = Instant.parse("2026-11-16T17:05:00Z");
    private static final ConfigUiAccount ADMIN = ConfigUiAccount.local(Instant.EPOCH);
    private static final ConfigUiAccount MEMBER = new ConfigUiAccount("member", ConfigUiAccount.Role.MEMBER,
            ConfigUiAccount.Status.ENABLED, "成员", null, null, null, List.of(), Instant.EPOCH, null, true);

    @TempDir
    Path root;

    @Test
    void readsOnlyMaskedCredentialsAndPublicGroupFields() {
        withConsole(rig -> {
            var response = rig.mvc.perform(write("credentials", JSONObject.of("appId", APP_ID, "appSecret", SECRET)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andReturn().getResponse();
            assertPrivate(response.getContentAsString(StandardCharsets.UTF_8));
            connect(rig);
            long number = join(rig);
            rig.directory.groupRemoved(REMOVED, JOINED.plusSeconds(10));
            rig.directory.recordFailure(number, "发送失败 " + GROUP, JOINED);
            response = rig.mvc.perform(read("state"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", containsString("no-store")))
                    .andExpect(jsonPath("$.configured").value(true))
                    .andExpect(jsonPath("$.appId").value(APP_ID))
                    .andExpect(jsonPath("$.secretMasked").value(containsString("…")))
                    .andExpect(jsonPath("$.intervalMillis").value(2000))
                    .andExpect(jsonPath("$.connection.level").value("OK"))
                    .andExpect(jsonPath("$.connection.summary").value(containsString("已连接")))
                    .andExpect(jsonPath("$.connection.robotName").value("小助手"))
                    .andExpect(jsonPath("$.groups[0].num").value(number))
                    .andExpect(jsonPath("$.groups[0].name").value("接口群名"))
                    .andExpect(jsonPath("$.groups[0].nameSource").value("GROUP_INFO_API"))
                    .andExpect(jsonPath("$.groups[0].memberCount").value(42))
                    .andExpect(jsonPath("$.groups[0].role").value("ADMIN"))
                    .andExpect(jsonPath("$.groups[0].proactive").value("DISABLED"))
                    .andExpect(jsonPath("$.groups[0].lastFailure").value(containsString("发送失败")))
                    .andExpect(jsonPath("$.groups[0].removed").value(false))
                    .andExpect(jsonPath("$.groups[0].joinedAt").value(JOINED.toString()))
                    .andExpect(jsonPath("$.groups[1].removed").value(true)).andReturn().getResponse();
            assertPrivate(response.getContentAsString(StandardCharsets.UTF_8));
            rig.directory.recordFailure(number, "回包重复了 " + SECRET, JOINED.plusSeconds(60));
            response = rig.mvc.perform(read("state")).andExpect(status().isOk()).andReturn().getResponse();
            assertPrivate(response.getContentAsString(StandardCharsets.UTF_8));
            response = rig.mvc.perform(post(BASE + "credentials").requestAttr(ConfigUiAccess.ACCOUNT, ADMIN)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"appSecret\":\"" + SECRET + "\""))
                    .andExpect(status().isBadRequest()).andReturn().getResponse();
            assertPrivate(response.getContentAsString(StandardCharsets.UTF_8));
        });
    }

    @Test
    void leavingSecretBlankKeepsItAndReconnectsWithTheNewAppId() {
        withConsole(rig -> {
            saveCredentials(rig.mvc);
            rig.scheduler.advance(0);
            var path = write("credentials", JSONObject.of("appId", "87654321", "appSecret", ""));
            rig.mvc.perform(path).andExpect(status().isOk());
            rig.scheduler.advance(0);
            assertEquals(List.of(APP_ID, "87654321"), rig.tokenRequests.stream().map(j -> j.getString("appId")).toList());
            assertTrue(rig.tokenRequests.stream().allMatch(j -> SECRET.equals(j.getString("clientSecret"))));
            assertEquals(2, rig.gatewayRequests.size());
        });
    }

    @Test
    void intervalRejectsInvalidValuesAndPersistsAcceptedValuesWithoutDisconnecting() {
        withConsole(rig -> {
            saveCredentials(rig.mvc);
            connect(rig);
            var sender = rig.senders.getSender("qq-official").orElseThrow();
            rig.mvc.perform(read("state")).andExpect(jsonPath("$.connection.level").value("OK"));
            for (var invalid : List.of(500, 70000, 1000.5, "1000")) {
                rig.mvc.perform(write("interval", JSONObject.of("millis", invalid))).andExpect(status().isBadRequest());
                assertEquals(2000, new QqBotSettings(root).snapshot().intervalMillis());
                assertEquals(2000, sender.getDelay());
            }
            rig.mvc.perform(write("interval", JSONObject.of("millis", 1000))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.intervalMillis").value(1000))
                    .andExpect(jsonPath("$.warning").value(containsString("每分钟 60 条只对做过个人认证或企业认证")));
            assertEquals(1000, new QqBotSettings(root).snapshot().intervalMillis());
            assertEquals(1000, sender.getDelay(), "在连接页保存的发送间隔应该立即生效");
            rig.mvc.perform(read("state")).andExpect(jsonPath("$.intervalMillis").value(1000));
            rig.mvc.perform(write("interval", JSONObject.of("millis", 60000))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.warning").doesNotExist());
            assertEquals(60000, new QqBotSettings(root).snapshot().intervalMillis());
            assertEquals(60000, sender.getDelay());
            assertSame(sender, rig.senders.getSender("qq-official").orElseThrow());
            rig.scheduler.advance(0);
            rig.mvc.perform(read("state")).andExpect(jsonPath("$.connection.level").value("OK"));
            assertEquals(1, rig.gatewayRequests.size());
        });
    }

    @Test
    void renamedGroupAppearsInPushPickerAndResetRestoresTheNameFromQq() {
        withConsole(rig -> {
            saveCredentials(rig.mvc);
            long number = join(rig);
            String path = "groups/" + number + "/name";
            var picker = get("/config/api/bot/targets").param("type", "group").requestAttr(ConfigUiAccess.ACCOUNT, ADMIN);
            rig.mvc.perform(write(path, JSONObject.of("name", "群".repeat(41)))).andExpect(status().isBadRequest());
            rig.mvc.perform(picker).andExpect(jsonPath("$.items[0].name").value("接口群名"));
            rig.mvc.perform(write(path, JSONObject.of("name", "  主播一群  "))).andExpect(status().isOk());
            rig.mvc.perform(picker).andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].num").value(number))
                    .andExpect(jsonPath("$.items[0].name").value("主播一群"));
            rig.mvc.perform(write(path, JSONObject.of("name", " "))).andExpect(status().isOk());
            rig.mvc.perform(picker).andExpect(jsonPath("$.items[0].name").value("接口群名"));
        });
    }

    @Test
    void membersCannotReadOrWriteAndAuditRecordsOnlyTheActions() {
        withConsole(rig -> {
            rig.mvc.perform(read("state").requestAttr(ConfigUiAccess.ACCOUNT, MEMBER)).andExpect(status().isForbidden());
            var credential = JSONObject.of("appId", APP_ID, "appSecret", SECRET);
            var interval = JSONObject.of("millis", 2000);
            var name = JSONObject.of("name", "主播一群");
            rig.mvc.perform(write("credentials", credential).requestAttr(ConfigUiAccess.ACCOUNT, MEMBER))
                    .andExpect(status().isForbidden());
            assertTrue(rig.tokenRequests.isEmpty());
            rig.mvc.perform(write("credentials", credential)).andExpect(status().isOk());
            String group = "groups/" + join(rig) + "/name";
            for (var request : List.of(write("interval", interval), write(group, name))) {
                rig.mvc.perform(request.requestAttr(ConfigUiAccess.ACCOUNT, MEMBER)).andExpect(status().isForbidden());
            }
            rig.mvc.perform(write("interval", interval)).andExpect(status().isOk());
            rig.mvc.perform(write(group, JSONObject.of("name", "主播一群", "command", SECRET))
                    .param("platform", SECRET).param("uid", APP_ID)).andExpect(status().isOk());
            var audit = audit();
            var successful = audit.stream().filter(row -> "SUCCESS".equals(row.getString("result"))).toList();
            assertEquals(List.of("保存 QQ 官方机器人凭据", "调整 QQ 官方机器人发送间隔", "修改 QQ 官方机器人的群名"),
                    successful.stream().map(row -> row.getString("description")).toList());
            assertTrue(audit.stream().allMatch(row -> row.getJSONObject("target").isEmpty()));
            assertPrivate(audit.toString());
            assertFalse(audit.toString().contains(APP_ID));
            assertFalse(audit.toString().contains("主播一群"));
        });
    }

    @Test
    void pluginRegistersTheCardOnlyWhenTheConsoleIsEnabled() {
        context().run(app -> {
            assertNull(app.getStartupFailure());
            var page = app.getBean(ConsolePageProvider.class);
            assertEquals("qqbot", page.id());
            assertEquals("links", page.slot().name().toLowerCase(java.util.Locale.ROOT));
            assertEquals("qqbot.js", page.script());
            assertTrue(page.botLink());
            var probe = app.getBean(QqBotHealthProbe.class);
            assertEquals(page.id(), probe.card());
            assertFalse(probe.active());
        });
        context().withPropertyValues("novabot.core.config-ui.enabled=false").run(app -> {
            assertNull(app.getStartupFailure());
            assertTrue(app.getBeansWithAnnotation(RestController.class).isEmpty());
            assertTrue(app.getBeansOfType(ConsolePageProvider.class).isEmpty());
            assertNotNull(app.getBean(QqBotCredentials.class));
        });
    }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(QqBotPluginAutoConfiguration.class)
                .withBean("qqBotSettings", QqBotSettings.class, () -> new QqBotSettings(root))
                .withBean(NovaSenderService.class, () -> new NovaSenderService(new NovaCoreProperties()));
    }

    private List<JSONObject> audit() throws Exception {
        try (var files = Files.list(root.resolve("console-audit"))) {
            var rows = new ArrayList<JSONObject>();
            for (Path file : files.sorted().toList()) {
                for (String line : Files.readAllLines(file)) rows.add(JSONObject.parseObject(line));
            }
            return rows;
        }
    }

    private void withConsole(ConsoleCheck test) {
        var requests = new ArrayList<JSONObject>();
        var gateways = new ArrayList<String>();
        QqBotHttpTransport http = (method, uri, headers, body) -> {
            String path = uri.getPath();
            if (path.equals("/app/getAppAccessToken")) {
                requests.add(JSONObject.parseObject(body));
                return response(JSONObject.of("access_token", "fixture-console-token"));
            }
            if (path.startsWith("/gateway")) {
                gateways.add(path);
                return response(JSONObject.of("url", "wss://gateway.example.test/bot"));
            }
            if (path.endsWith("/info")) {
                return response(JSONObject.of("group_name", "接口群名", "group_member_num", 42));
            }
            return response(JSONObject.of("member_role", "admin", "allow_proactive_msg", false));
        };
        var scheduler = new QqBotGatewayTest.ManualScheduler();
        var listeners = new ArrayList<QqBotWebSocketTransport.Listener>();
        QqBotWebSocketTransport sockets = (uri, listener) -> {
            listeners.add(listener);
            var socket = mock(QqBotWebSocketTransport.Connection.class);
            doReturn(CompletableFuture.completedFuture(null)).when(socket).send(anyString());
            return socket;
        };
        var properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(root.resolve("live.json").toString());
        var store = new ConfigUiOperationStore(properties);
        context().withBean(QqBotHttpTransport.class, () -> http, definition -> definition.setPrimary(true))
                .withBean(QqBotGatewayScheduler.class, () -> scheduler, definition -> definition.setPrimary(true))
                .withBean(QqBotWebSocketTransport.class, () -> sockets, definition -> definition.setPrimary(true))
                .run(app -> {
                    assertNull(app.getStartupFailure());
                    var directory = app.getBean(QqBotDirectory.class);
                    var controllers = new ArrayList<>(app.getBeansWithAnnotation(RestController.class).values());
                    controllers.add(new ConfigUiPushTargetsController(List.of(directory), List.of(directory),
                            mock(AbstractDataSource.class)));
                    MockMvc mvc = MockMvcBuilders.standaloneSetup(controllers.toArray())
                            .addInterceptors(new ConfigUiApiInterceptor(mock(ConfigUiAuthService.class)))
                            .addFilters(new ConfigUiOperationFilter(store,
                                    List.copyOf(app.getBeansOfType(ConfigUiOperationProvider.class).values())))
                            .setControllerAdvice(new ConfigUiOperationAdvice()).build();
                    test.run(new Rig(mvc, directory, scheduler, requests, gateways, listeners,
                            app.getBean(NovaSenderService.class)));
                });
    }

    private static QqBotHttpTransport.Response response(JSONObject body) {
        return new QqBotHttpTransport.Response(200, body.toJSONString());
    }

    private static long join(Rig rig) {
        long number = rig.directory.groupJoined(GROUP, JOINED);
        await().atMost(java.time.Duration.ofSeconds(10)).until(() ->
                rig.directory.group(number).orElseThrow().proactive().status() == QqBotDirectory.ProactiveStatus.DISABLED);
        return number;
    }

    private static void connect(Rig rig) {
        rig.scheduler.advance(0);
        var listener = rig.listeners.get(0);
        listener.onText(JSONObject.of("op", 10, "d", JSONObject.of("heartbeat_interval", 45000)).toJSONString());
        rig.scheduler.advance(0);
        listener.onText(JSONObject.of("op", 0, "t", "READY", "s", 1,
                "d", JSONObject.of("session_id", "fixture-console-session",
                        "user", JSONObject.of("username", "小助手"))).toJSONString());
        rig.scheduler.advance(0);
    }

    private static void assertPrivate(String body) {
        assertFalse(body.contains(SECRET));
        assertFalse(body.toLowerCase(java.util.Locale.ROOT).contains("openid"));
        assertFalse(body.contains(GROUP));
        assertFalse(body.contains(REMOVED));
    }

    private static void saveCredentials(MockMvc mvc) throws Exception {
        mvc.perform(write("credentials", JSONObject.of("appId", APP_ID, "appSecret", SECRET))).andExpect(status().isOk());
    }

    private static MockHttpServletRequestBuilder read(String path) {
        return get(BASE + path).requestAttr(ConfigUiAccess.ACCOUNT, ADMIN);
    }

    private static MockHttpServletRequestBuilder write(String path, JSONObject body) {
        return post(BASE + path).requestAttr(ConfigUiAccess.ACCOUNT, ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content(body.toJSONString());
    }

    private record Rig(MockMvc mvc, QqBotDirectory directory, QqBotGatewayTest.ManualScheduler scheduler,
                       List<JSONObject> tokenRequests, List<String> gatewayRequests,
                       List<QqBotWebSocketTransport.Listener> listeners, NovaSenderService senders) {}

    private interface ConsoleCheck {
        void run(Rig rig) throws Exception;
    }
}
