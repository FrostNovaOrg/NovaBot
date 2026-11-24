package org.frostnova.nova.adapter.qqbot;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.command.BilibiliAtListCommand;
import org.frostnova.nova.bilibili.command.BilibiliStreamerChoice;
import org.frostnova.nova.core.command.CommandContext;
import org.frostnova.nova.core.command.CommandDispatcher;
import org.frostnova.nova.core.command.CommandReply;
import org.frostnova.nova.core.command.CommandReplyHint;
import org.frostnova.nova.core.command.CommandSettingsService;
import org.frostnova.nova.core.command.NovaCommand;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigUiAccess;
import org.frostnova.nova.core.config.ui.ConfigUiApiInterceptor;
import org.frostnova.nova.core.config.ui.ConfigUiController;
import org.frostnova.nova.core.config.ui.ConfigUiOperationAdvice;
import org.frostnova.nova.core.config.ui.ConfigUiOperationFilter;
import org.frostnova.nova.core.config.ui.ConfigUiOperationProvider;
import org.frostnova.nova.core.config.ui.ConfigUiOperationStore;
import org.frostnova.nova.core.config.ui.ConfigUiPushTargetsController;
import org.frostnova.nova.core.config.ui.ConfigUiSetupController;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.config.ui.page.ConsolePageProvider;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.health.PushActivityRecorder;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.sender.PushGate;
import org.frostnova.nova.core.service.AtSubscriptionService;
import org.frostnova.nova.core.service.NovaSenderService;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class QqBotConsoleControllerTest {
    private static final String BASE = "/config/api/qqbot/";
    private static final String APP_ID = "12345678";
    private static final String SECRET = "fixture-console-secret";
    private static final String GROUP = "GROUP12345678FIXTURE";
    private static final String REMOVED = "REMOVED12345678FIXTURE";
    private static final String SENDER = "SENDER12345678FIXTURE";
    private static final String STRANGER = "STRANGER12345678FIXTURE";
    private static final String PLATFORM = "qq-official";
    private static final String CLAIM_CODE = "admin-claims/code";
    private static final Instant JOINED = Instant.parse("2026-11-16T17:05:00Z");
    private static final ConfigUiAccount ADMIN = ConfigUiAccount.local(Instant.EPOCH);
    private static final ConfigUiAccount MEMBER = new ConfigUiAccount("member", ConfigUiAccount.Role.MEMBER,
            ConfigUiAccount.Status.ENABLED, "成员", null, null, null, List.of(), Instant.EPOCH, null, true);

    @TempDir
    Path root;

    @Test
    void firstCredentialSaveAfterStartupRegistersTheSenderAndConnects() {
        withConsole(rig -> {
            rig.ready.run();
            assertFalse(state(rig).getBooleanValue("configured"));

            var response = rig.mvc.perform(write("credentials", JSONObject.of("appId", APP_ID, "appSecret", SECRET)))
                    .andReturn().getResponse();
            String body = response.getContentAsString(StandardCharsets.UTF_8);
            assertEquals(200, response.getStatus(), body);
            assertTrue(JSONObject.parseObject(body).getBooleanValue("success"));
            assertEquals(List.of(PLATFORM), rig.senders.getSenderNames().stream().sorted().toList());

            connect(rig);
            var connected = state(rig);
            assertTrue(connected.getBooleanValue("configured"));
            assertEquals("OK", connected.getJSONObject("connection").getString("level"));
        });
    }

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
    void joinWelcomeDefaultsOnAndFollowsSavesWithoutReconnecting() {
        withConsole(rig -> {
            saveCredentials(rig.mvc);
            connect(rig);
            rig.joinEvent(GROUP, "default-welcome", 2);
            assertThat(rig.welcome("default-welcome"), containsString("机器人主动在群聊内发言"));

            var saved = rig.mvc.perform(write("welcome", JSONObject.of("enabled", false)));
            rig.joinEvent(REMOVED, "disabled-welcome", 3);
            long group = rig.directory.groupNumber(REMOVED).orElseThrow();
            var groups = state(rig).getJSONArray("groups").toJavaList(JSONObject.class);
            assertTrue(groups.stream().anyMatch(row -> row.getLongValue("num") == group && !row.getBooleanValue("removed")));
            // 认领回复排在同一发送队列里，收到它时前面的进群回复已经处理完。
            rig.message("GROUP_AT_MESSAGE_CREATE", "claim-in-new-group", REMOVED, SENDER, "认领 ABCDEF23", "小明", "");
            assertThat(rig.passiveReply("claim-in-new-group"), containsString("认领码"));
            assertFalse(rig.groupMessages.stream().anyMatch(reply -> "disabled-welcome".equals(reply.getString("event_id"))),
                    "关闭欢迎语后仍回复了进群事件");
            saved.andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));

            var push = JSONObject.of("platform", PLATFORM, "type", 1, "num", group, "content", "测试推送");
            rig.mvc.perform(post("/config/api/test-message").requestAttr(ConfigUiAccess.ACCOUNT, ADMIN)
                            .contentType(MediaType.APPLICATION_JSON).content(push.toJSONString()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
            assertTrue(rig.groupMessages.stream().anyMatch(reply -> "测试推送".equals(reply.getString("content"))));

            rig.mvc.perform(write("welcome", JSONObject.of("enabled", true)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
            rig.joinEvent(GROUP, "enabled-welcome", 4);
            assertThat(rig.welcome("enabled-welcome"), containsString("机器人主动在群聊内发言"));
            assertEquals(1, rig.gatewayRequests.size());
        });
    }

    @Test
    void savedDisabledWelcomeSurvivesReload() {
        withConsole(rig -> {
            rig.mvc.perform(read("welcome")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
            rig.mvc.perform(write("welcome", JSONObject.of("enabled", false)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        });
        withConsole(rig -> {
            rig.mvc.perform(read("welcome")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
            saveCredentials(rig.mvc);
            rig.mvc.perform(write("interval", JSONObject.of("millis", 3700))).andExpect(status().isOk());
        });
        withConsole(rig -> {
            rig.mvc.perform(read("welcome")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
            assertEquals(3700, state(rig).getLongValue("intervalMillis"));
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
    @DisplayName("只配 QQ 官方机器人时，可向名单里的群试发，确认收到后引导记下完成")
    void setupCanSendAndConfirmWithOnlyTheOfficialBot() {
        withConsole(rig -> {
            var state = get("/config/api/setup/state").requestAttr(ConfigUiAccess.ACCOUNT, ADMIN);
            var picker = get("/config/api/bot/targets").param("type", "group")
                    .requestAttr(ConfigUiAccess.ACCOUNT, ADMIN);
            assertTrue(rig.senders.getSenderNames().isEmpty());
            rig.mvc.perform(picker).andExpect(jsonPath("$.items").isEmpty());
            saveCredentials(rig.mvc);
            connect(rig);
            assertEquals(Set.of("qq-official"), rig.senders.getSenderNames());

            var joinEventId = "fixture-joined";
            rig.listeners.get(0).onText(JSONObject.of("op", 0, "t", "GROUP_ADD_ROBOT", "s", 2,
                    "id", joinEventId, "d", JSONObject.of("group_openid", GROUP,
                            "timestamp", JOINED.toString())).toJSONString());
            rig.scheduler.advance(0);
            await().atMost(java.time.Duration.ofSeconds(10)).until(() -> !rig.groupMessages.isEmpty());
            var welcome = rig.groupMessages.get(0);
            assertEquals(0, welcome.getInteger("msg_type"));
            assertEquals(joinEventId, welcome.getString("event_id"));
            assertThat(welcome.getString("content"), containsString("我是这个群的推送机器人"));

            var targets = rig.mvc.perform(picker).andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].sender").value("qq-official")).andReturn().getResponse();
            var group = JSONObject.parseObject(targets.getContentAsString(StandardCharsets.UTF_8))
                    .getJSONArray("items").getJSONObject(0);
            var text = "这是一条来自 NovaBot 的测试消息，收到即表示推送链路正常。";
            var payload = JSONObject.of("platform", group.getString("sender"), "type", 1,
                    "num", group.getLong("num"), "content", text);
            rig.mvc.perform(post("/config/api/test-message").requestAttr(ConfigUiAccess.ACCOUNT, ADMIN)
                            .contentType(MediaType.APPLICATION_JSON).content(payload.toJSONString()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.raw.id").value("fixture-test-message"));
            assertEquals(2, rig.groupMessages.size());
            assertEquals(JSONObject.of("msg_type", 0, "content", text), rig.groupMessages.get(1));

            rig.mvc.perform(state).andExpect(jsonPath("$.testSent").value(false));
            rig.mvc.perform(post("/config/api/setup/test-sent").requestAttr(ConfigUiAccess.ACCOUNT, ADMIN))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
            rig.mvc.perform(state).andExpect(jsonPath("$.testSent").value(true));
            assertTrue(Files.isRegularFile(root.resolve("state.json")));
        });
    }

    @Test
    void membersCannotReadOrWriteAndAuditRecordsOnlyTheActions() {
        withConsole(rig -> {
            rig.mvc.perform(read("state").requestAttr(ConfigUiAccess.ACCOUNT, MEMBER)).andExpect(status().isForbidden());
            rig.mvc.perform(read("welcome").requestAttr(ConfigUiAccess.ACCOUNT, MEMBER)).andExpect(status().isForbidden());
            var credential = JSONObject.of("appId", APP_ID, "appSecret", SECRET);
            var interval = JSONObject.of("millis", 2000);
            var welcome = JSONObject.of("enabled", false);
            var name = JSONObject.of("name", "主播一群");
            rig.mvc.perform(write("credentials", credential).requestAttr(ConfigUiAccess.ACCOUNT, MEMBER))
                    .andExpect(status().isForbidden());
            assertTrue(rig.tokenRequests.isEmpty());
            rig.mvc.perform(write("credentials", credential)).andExpect(status().isOk());
            String group = "groups/" + join(rig) + "/name";
            for (var request : List.of(write("interval", interval), write("welcome", welcome), write(group, name))) {
                rig.mvc.perform(request.requestAttr(ConfigUiAccess.ACCOUNT, MEMBER)).andExpect(status().isForbidden());
            }
            rig.mvc.perform(write("interval", interval)).andExpect(status().isOk());
            rig.mvc.perform(write("welcome", welcome)).andExpect(status().isOk());
            rig.mvc.perform(write(group, JSONObject.of("name", "主播一群", "command", SECRET))
                    .param("platform", SECRET).param("uid", APP_ID)).andExpect(status().isOk());
            var audit = audit();
            var successful = audit.stream().filter(row -> "SUCCESS".equals(row.getString("result"))).toList();
            assertEquals(List.of("保存 QQ 官方机器人凭据", "调整 QQ 官方机器人发送间隔",
                            "调整 QQ 官方机器人进群欢迎语", "修改 QQ 官方机器人的群名"),
                    successful.stream().map(row -> row.getString("description")).toList());
            assertTrue(audit.stream().allMatch(row -> row.getJSONObject("target").isEmpty()));
            assertPrivate(audit.toString());
            assertFalse(audit.toString().contains(APP_ID));
            assertFalse(audit.toString().contains("主播一群"));
        });
    }

    @Test
    void pluginRegistersTheCardOnlyWhenTheConsoleIsEnabled() {
        var runner = context(new NovaSenderService(new NovaCoreProperties()));
        runner.run(app -> {
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
        runner.withPropertyValues("novabot.core.config-ui.enabled=false").run(app -> {
            assertNull(app.getStartupFailure());
            assertTrue(app.getBeansWithAnnotation(RestController.class).isEmpty());
            assertTrue(app.getBeansOfType(ConsolePageProvider.class).isEmpty());
            assertNotNull(app.getBean(QqBotCredentials.class));
        });
    }

    @Test
    void mentionSuppliesTheGroupNicknameInAtList() {
        withConsole(rig -> {
            saveCredentials(rig.mvc);
            connect(rig);
            long group = join(rig);
            long member = rig.directory.memberSeen(SENDER, JOINED);
            long unseen = rig.directory.memberSeen(STRANGER, JOINED);
            configurePush(rig, group);
            when(rig.subscriptions.list(PLATFORM, group, 10001L, "live")).thenReturn(List.of(member, unseen));
            rig.message("GROUP_AT_MESSAGE_CREATE", "at-list", GROUP, SENDER, "@名单 开播", "小明", "账号昵称");
            String reply = rig.passiveReply("at-list");
            assertTrue(reply.contains("· 小明"), reply);
            assertTrue(reply.contains("· （昵称未知）"), reply);
            assertFalse(reply.contains("账号昵称"), reply);
            assertFalse(reply.contains(Long.toString(unseen)), reply);
        });
    }

    @Test
    void claimedOwnerCanRunManagementCommandsAndLosesAccessAfterRevocation() {
        withConsole(rig -> {
            saveCredentials(rig.mvc);
            connect(rig);
            String code = generate(rig);
            rig.claim("claim", SENDER, "　认领 \t" + code.toLowerCase(java.util.Locale.ROOT) + "　");
            assertTrue(rig.passiveReply("claim").contains("已认领超级管理员：小明"));

            long group = rig.directory.groupNumber(GROUP).orElseThrow();
            long otherGroup = rig.directory.groupJoined(REMOVED, JOINED);
            configurePush(rig, group, otherGroup);
            rig.message("GROUP_AT_MESSAGE_CREATE", "admin", GROUP, SENDER, "管理命令", "", "账号昵称");
            assertTrue(rig.passiveReply("admin").contains("管理命令已执行"));

            String claimId = state(rig).getJSONArray("claimedAdmins").getJSONObject(0).getString("memberNum");
            var revoke = write("admin-claims/" + claimId + "/revoke", new JSONObject());
            rig.mvc.perform(revoke.requestAttr(ConfigUiAccess.ACCOUNT, MEMBER)).andExpect(status().isForbidden());
            rig.mvc.perform(revoke.requestAttr(ConfigUiAccess.ACCOUNT, ADMIN)).andExpect(status().isOk());
            rig.message("GROUP_AT_MESSAGE_CREATE", "revoked", REMOVED, SENDER, "管理命令", "", "账号昵称");
            assertTrue(rig.passiveReply("revoked").contains("仅群主、群管理员或超级管理员可用"));
        });
    }

    @Test
    void usedCodeCannotBeReplayedByAStranger() {
        withConsole(rig -> {
            saveCredentials(rig.mvc);
            connect(rig);
            String code = generate(rig);
            rig.claim("first", SENDER, "认领 " + code);
            assertTrue(rig.passiveReply("first").contains("已认领超级管理员"));
            rig.claim("replay", STRANGER, "认领 " + code);
            assertRejected(rig, "replay");
            assertEquals(1, state(rig).getJSONArray("claimedAdmins").size());
        });
    }

    @Test
    void expiredCodeCannotGrantAccess() {
        withConsole(rig -> {
            saveCredentials(rig.mvc);
            connect(rig);
            String code = generate(rig);
            rig.scheduler.advance(Duration.ofMinutes(11).toMillis());
            rig.claim("expired", STRANGER, "认领 " + code);
            assertRejected(rig, "expired");
            assertTrue(state(rig).getJSONArray("claimedAdmins").isEmpty());
        });
    }

    @Test
    void fifthWrongAttemptInvalidatesTheCode() {
        withConsole(rig -> {
            saveCredentials(rig.mvc);
            connect(rig);
            String code = generate(rig);
            for (int i = 1; i <= 5; i++) {
                String id = "guess-" + i;
                rig.claim(id, STRANGER, "认领 11111111");
                assertRejected(rig, id);
                assertEquals(i < 5, state(rig).getBooleanValue("claimCodeActive"));
            }
            rig.claim("after-guesses", SENDER, "认领 " + code);
            assertRejected(rig, "after-guesses");
            assertTrue(state(rig).getJSONArray("claimedAdmins").isEmpty());
        });
    }

    @Test
    void newCodeReplacesOldCodeAndDirectMessagesCannotClaim() {
        withConsole(rig -> {
            saveCredentials(rig.mvc);
            connect(rig);
            String oldCode = generate(rig);
            String newCode = generate(rig);
            rig.claim("old", STRANGER, "认领 " + oldCode);
            assertRejected(rig, "old");
            rig.message("C2C_MESSAGE_CREATE", "direct", GROUP, STRANGER, "认领 " + newCode, "小明", "");
            assertTrue(state(rig).getJSONArray("claimedAdmins").isEmpty());
            rig.claim("new", SENDER, "认领 " + newCode);
            assertTrue(rig.passiveReply("new").contains("已认领超级管理员"));
            assertEquals(2, rig.groupMessages.size());
        });
    }

    @Test
    void nicknameAndClaimPersistAcrossRestartWithoutBackingUpALiveCode() throws Exception {
        List<String> codes = new ArrayList<>();
        withConsole(rig -> {
            saveCredentials(rig.mvc);
            connect(rig);
            codes.add(generate(rig));
            rig.message("GROUP_AT_MESSAGE_CREATE", "claim", GROUP, SENDER, "认领 " + codes.get(0), " ", "账号昵称");
            assertTrue(rig.passiveReply("claim").contains("已认领超级管理员：账号昵称"));
            codes.add(generate(rig));
        });

        Path file = root.resolve("qqbot/members.json");
        String members = Files.readString(file);
        assertTrue(members.contains("seenAt"), "群昵称记录应带最后见到的时间");
        assertTrue(members.contains("账号昵称"));
        assertFalse(members.contains(SENDER));
        assertFalse(members.contains(GROUP));
        assertTrue(codes.stream().noneMatch(members::contains));
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        }

        withConsole(rig -> {
            assertFalse(state(rig).getBooleanValue("claimCodeActive"));
            var admins = state(rig).getJSONArray("claimedAdmins");
            assertEquals(1, admins.size());
            assertEquals("账号昵称", admins.getJSONObject(0).getString("nickname"));
            rig.ready.run();
            connect(rig);
            long group = rig.directory.groupNumber(GROUP).orElseThrow();
            configurePush(rig, group);
            long member = rig.directory.memberNumber(SENDER).orElseThrow();
            when(rig.subscriptions.list(PLATFORM, group, 10001L, "live")).thenReturn(List.of(member));
            rig.message("GROUP_AT_MESSAGE_CREATE", "list-after-restart", GROUP, STRANGER, "@名单 开播", "陌生人", "");
            assertTrue(rig.passiveReply("list-after-restart").contains("· 账号昵称"));
        });
    }

    @Test
    void claimCodeAndOpenIdsStayOutOfLogsTimelineAuditAndState() {
        Logger gatewayLog = (Logger) LoggerFactory.getLogger(QqBotGateway.class);
        Logger mvcLog = (Logger) LoggerFactory.getLogger(HttpEntityMethodProcessor.class);
        Logger senderLog = (Logger) LoggerFactory.getLogger(QqBotSender.class);
        Level originalGatewayLevel = gatewayLog.getLevel();
        Level originalMvcLevel = mvcLog.getLevel();
        boolean originalMvcAdditivity = mvcLog.isAdditive();
        boolean originalSenderAdditivity = senderLog.isAdditive();
        var logs = new ListAppender<ILoggingEvent>();
        logs.list = new CopyOnWriteArrayList<>();
        logs.start();
        gatewayLog.addAppender(logs);
        mvcLog.addAppender(logs);
        senderLog.addAppender(logs);
        gatewayLog.setLevel(Level.DEBUG);
        mvcLog.setLevel(Level.TRACE);
        mvcLog.setAdditive(false);
        senderLog.setAdditive(false);
        try {
            withConsole(rig -> {
                saveCredentials(rig.mvc);
                connect(rig);
                long group = join(rig);
                configurePush(rig, group);
                rig.mvc.perform(write(CLAIM_CODE, new JSONObject()).requestAttr(ConfigUiAccess.ACCOUNT, MEMBER))
                        .andExpect(status().isForbidden());
                String code = generate(rig);
                JSONObject withCode = state(rig);
                assertTrue(withCode.getBooleanValue("claimCodeActive"));
                assertNotNull(withCode.getString("claimCodeExpiresAt"));
                assertFalse(withCode.toString().contains(code));

                rig.nextReplyError.set("发送失败 " + code + " " + GROUP + " " + SENDER);
                rig.claim("reply-error", SENDER, "认领 11111111");
                await().atMost(Duration.ofSeconds(8)).until(() -> logs.list.stream().anyMatch(line ->
                        line.getLoggerName().equals(senderLog.getName()) && line.getLevel() == Level.WARN));
                assertTrue(logs.list.stream().noneMatch(line -> line.getFormattedMessage().contains(code)),
                        "认领回复失败时日志外泄认领码");
                JSONObject afterReplyError = state(rig);
                assertTrue(afterReplyError.getBooleanValue("claimCodeActive"));
                assertFalse(afterReplyError.toJSONString().contains(code), "认领回复失败时状态回包外泄认领码");

                rig.claim("claim", SENDER, "认领 " + code);
                assertTrue(rig.passiveReply("claim").contains("已认领超级管理员"));
                JSONObject claimed = state(rig);
                assertFalse(claimed.getBooleanValue("claimCodeActive"));
                var admin = claimed.getJSONArray("claimedAdmins").getJSONObject(0);
                assertEquals("小明", admin.getString("nickname"));
                assertEquals("接口群名", admin.getString("groupName"));
                assertNotNull(admin.getString("claimedAt"));
                rig.mvc.perform(write("admin-claims/" + admin.getString("memberNum") + "/revoke", new JSONObject())
                        .param("uid", code).param("platform", SENDER)).andExpect(status().isOk());

                List<JSONObject> audit = audit();
                assertTrue(audit.stream().anyMatch(row -> "生成超管认领码".equals(row.getString("description"))));
                assertTrue(audit.stream().anyMatch(row -> "撤销官方机器人超管".equals(row.getString("description"))));
                assertTrue(audit.stream().allMatch(row -> row.getJSONObject("target").isEmpty()));
                assertEquals(1, rig.timeline.size(), "认领只记一条专用时间线");
                assertTrue(rig.timeline.get(0).text().contains("群『接口群名』的『小明』认领了超级管理员"));
                for (var line : logs.list) {
                    assertFalse(line.getFormattedMessage().contains(code),
                            "认领码出现在 " + line.getLoggerName() + " 的日志");
                }
                for (String text : List.of(withCode.toString(), claimed.toString(), audit.toString(), rig.timeline.toString(),
                        logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList().toString())) {
                    assertFalse(text.contains(code), "认领码外泄");
                    assertFalse(text.contains(SENDER), "成员标识外泄");
                    assertPrivate(text);
                }
            });
        } finally {
            gatewayLog.detachAppender(logs);
            mvcLog.detachAppender(logs);
            senderLog.detachAppender(logs);
            gatewayLog.setLevel(originalGatewayLevel);
            mvcLog.setLevel(originalMvcLevel);
            mvcLog.setAdditive(originalMvcAdditivity);
            senderLog.setAdditive(originalSenderAdditivity);
            logs.stop();
        }
    }

    @Test
    void malformedMentionCannotLeakARedeemableCode() {
        Logger dispatcherLog = (Logger) LoggerFactory.getLogger(CommandDispatcher.class);
        Level originalLevel = dispatcherLog.getLevel();
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        dispatcherLog.addAppender(logs);
        dispatcherLog.setLevel(Level.DEBUG);
        try {
            withConsole(rig -> {
                saveCredentials(rig.mvc);
                connect(rig);
                configurePush(rig, join(rig));

                String code = generate(rig);
                String typo = "认领" + code.toLowerCase(Locale.ROOT);
                rig.claim("missing-space", SENDER, typo);
                rig.claim("repeat-typo", SENDER, typo);
                JSONObject afterTypo = state(rig);
                rig.claim("copied-code", STRANGER, "认领 " + code);
                String copiedReply = rig.passiveReply("copied-code");
                assertAll(
                        () -> assertFalse(afterTypo.getBooleanValue("claimCodeActive"), "漏空格后认领码仍有效"),
                        () -> assertTrue(copiedReply.contains("认领码不对或已过期"), "别人还能用这个码认领"),
                        () -> assertTrue(state(rig).getJSONArray("claimedAdmins").isEmpty(), "陌生人成了超级管理员"),
                        () -> assertFalse(rig.timeline.toString().toUpperCase(Locale.ROOT).contains(code), "时间线外泄认领码"),
                        () -> assertTrue(logs.list.stream().noneMatch(line ->
                                line.getFormattedMessage().toUpperCase(Locale.ROOT).contains(code)), "日志外泄认领码"),
                        () -> assertFalse(afterTypo.toString().contains(code), "状态回包外泄认领码"),
                        () -> assertFalse(audit().toString().contains(code), "审计外泄认领码"),
                        () -> assertTrue(rig.groupMessages.stream().noneMatch(reply ->
                                reply.toJSONString().toUpperCase(Locale.ROOT).contains(code)), "群回复外泄认领码"));
                String typoReply = rig.passiveReply("missing-space");
                assertTrue(typoReply.contains("格式不对") && typoReply.contains("已作废") && typoReply.contains("重新生成"));

                String nextCode = generate(rig);
                rig.claim("missing-verb", SENDER, nextCode);
                assertTrue(rig.passiveReply("missing-verb").contains("已作废"));
                rig.claim("copied-bare-code", STRANGER, "认领 " + nextCode);
                assertRejected(rig, "copied-bare-code");
                assertTrue(state(rig).getJSONArray("claimedAdmins").isEmpty());
            });
        } finally {
            dispatcherLog.detachAppender(logs);
            dispatcherLog.setLevel(originalLevel);
            logs.stop();
        }
    }

    private static String generate(Rig rig) throws Exception {
        var response = rig.mvc.perform(write(CLAIM_CODE, new JSONObject())).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store"))).andReturn().getResponse();
        String code = JSONObject.parseObject(response.getContentAsString(StandardCharsets.UTF_8)).getString("code");
        assertTrue(code.matches("[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{8}"), "认领码格式错误");
        return code;
    }

    private static JSONObject state(Rig rig) throws Exception {
        var response = rig.mvc.perform(read("state")).andExpect(status().isOk()).andReturn().getResponse();
        return JSONObject.parseObject(response.getContentAsString(StandardCharsets.UTF_8));
    }

    private static void assertRejected(Rig rig, String id) {
        assertTrue(rig.passiveReply(id).contains("认领码不对或已过期"));
    }

    private static void configurePush(Rig rig, long... groups) {
        var streamer = new PushUser();
        streamer.setUid(10001L);
        streamer.setPlatform("bilibili");
        streamer.setUname("测试主播");
        List<PushTarget> targets = new ArrayList<>();
        for (long group : groups) {
            var target = new PushTarget();
            target.setPlatform(PLATFORM);
            target.setType(PushTargetType.GROUP);
            target.setNum(group);
            target.setMessages(List.of());
            targets.add(target);
        }
        streamer.setTargets(targets);
        when(rig.dataSource.getAllUsers()).thenReturn(List.of(streamer));
        when(rig.dataSource.getUsers("bilibili")).thenReturn(List.of(streamer));
    }

    private ApplicationContextRunner context(NovaSenderService senders) {
        return new ApplicationContextRunner().withUserConfiguration(QqBotPluginAutoConfiguration.class)
                .withBean("qqBotSettings", QqBotSettings.class, () -> new QqBotSettings(root))
                .withBean(NovaSenderService.class, () -> senders);
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
        var nextReplyError = new AtomicReference<String>();
        var groupMessages = new CopyOnWriteArrayList<JSONObject>();
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
            if (path.equals("/v2/groups/" + GROUP + "/messages") || path.equals("/v2/groups/" + REMOVED + "/messages")) {
                assertEquals("POST", method);
                JSONObject reply = JSONObject.parseObject(body);
                assertEquals(0, reply.getIntValue("msg_type"));
                boolean passive = reply.containsKey("msg_id");
                if (passive) assertTrue(reply.getIntValue("msg_seq") > 0);
                groupMessages.add(reply);
                if (passive) {
                    String error = nextReplyError.getAndSet(null);
                    if (error != null) {
                        return new QqBotHttpTransport.Response(400,
                                JSONObject.of("code", 40059999, "message", error).toJSONString());
                    }
                    return response(JSONObject.of("id", "fixture-reply"));
                }
                return response(JSONObject.of("id", "fixture-test-message"));
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
        var dataSource = mock(AbstractDataSource.class);
        var subscriptions = mock(AtSubscriptionService.class);
        List<TimelineEvent> timeline = new CopyOnWriteArrayList<>();
        var registry = new NovaSenderService(properties);
        var pushGate = mock(PushGate.class);
        when(pushGate.allowedFor(any(Message.class))).thenReturn(true);
        when(pushGate.alertsAllowed()).thenReturn(true);
        var sender = new NovaMessageSender(null, registry, mock(PushActivityRecorder.class), pushGate,
                timeline::add, null, null, null);
        context(registry).withBean(QqBotHttpTransport.class, () -> http, definition -> definition.setPrimary(true))
                .withBean(QqBotGatewayScheduler.class, () -> scheduler, definition -> definition.setPrimary(true))
                .withBean(QqBotWebSocketTransport.class, () -> sockets, definition -> definition.setPrimary(true))
                .withUserConfiguration(CommandDispatcher.class, BilibiliAtListCommand.class)
                .withBean(Clock.class, scheduler::clock)
                .withBean(NovaCoreProperties.class, () -> properties)
                .withBean(AbstractDataSource.class, () -> dataSource)
                .withBean(CommandSettingsService.class, () -> mock(CommandSettingsService.class))
                .withBean(CommandReplyHint.class, () -> mock(CommandReplyHint.class))
                .withBean(BilibiliStreamerChoice.class, () -> mock(BilibiliStreamerChoice.class))
                .withBean(AtSubscriptionService.class, () -> subscriptions)
                .withBean(NovaMessageSender.class, () -> sender)
                .withBean(TimelineWriter.class, () -> timeline::add)
                .withBean(NovaCommand.class, () -> new ManagementCommand())
                .run(app -> {
                    assertNull(app.getStartupFailure());
                    var directory = app.getBean(QqBotDirectory.class);
                    var auth = mock(ConfigUiAuthService.class);
                    var controllers = new ArrayList<>(app.getBeansWithAnnotation(RestController.class).values());
                    controllers.add(new ConfigUiPushTargetsController(List.of(directory), List.of(directory), dataSource));
                    controllers.add(new ConfigUiSetupController(new NovaStateStore(properties)));
                    controllers.add(new ConfigUiController(null, null, properties, dataSource, null, null, registry,
                            sender, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                            null, null, auth, null, null));
                    MockMvc mvc = MockMvcBuilders.standaloneSetup(controllers.toArray())
                            .addInterceptors(new ConfigUiApiInterceptor(auth))
                            .addFilters(new ConfigUiOperationFilter(store,
                                    List.copyOf(app.getBeansOfType(ConfigUiOperationProvider.class).values())))
                            .setControllerAdvice(new ConfigUiOperationAdvice()).build();
                    Runnable ready = () -> app.publishEvent(new ApplicationReadyEvent(new SpringApplication(),
                            new String[0], app.getSourceApplicationContext(), Duration.ZERO));
                    test.run(new Rig(mvc, directory, scheduler, requests, gateways, listeners, groupMessages,
                            timeline, dataSource, subscriptions, ready, registry, nextReplyError));
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
        listener.onText(JSONObject.of("op", 10, "d", JSONObject.of("heartbeat_interval", 720000)).toJSONString());
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
                       List<QqBotWebSocketTransport.Listener> listeners, List<JSONObject> groupMessages,
                       List<TimelineEvent> timeline, AbstractDataSource dataSource, AtSubscriptionService subscriptions,
                       Runnable ready, NovaSenderService senders, AtomicReference<String> nextReplyError) {
        void joinEvent(String group, String id, int sequence) {
            listeners.get(0).onText(JSONObject.of("op", 0, "t", "GROUP_ADD_ROBOT", "s", sequence, "id", id,
                    "d", JSONObject.of("group_openid", group, "timestamp", JOINED.toString())).toJSONString());
            scheduler.advance(0);
        }

        String welcome(String id) {
            return await().atMost(Duration.ofSeconds(10)).until(() -> groupMessages.stream()
                    .filter(reply -> id.equals(reply.getString("event_id"))).map(reply -> reply.getString("content"))
                    .findFirst().orElse(null), java.util.Objects::nonNull);
        }

        void claim(String id, String sender, String content) {
            message("GROUP_AT_MESSAGE_CREATE", id, GROUP, sender, content, "小明", "账号昵称");
        }

        void message(String event, String id, String group, String sender, String content, String nickname, String username) {
            listeners.get(0).onText(JSONObject.of("op", 0, "t", event, "d", JSONObject.of("id", id, "content", content,
                    "timestamp", Instant.now().toString(), "group_openid", group, "author",
                    JSONObject.of("member_openid", sender, "member_role", "member",
                            "member_nickname", nickname, "username", username))).toJSONString());
            scheduler.advance(0);
        }

        String passiveReply(String id) {
            return await().atMost(Duration.ofSeconds(8)).until(() -> groupMessages.stream()
                    .filter(reply -> id.equals(reply.getString("msg_id"))).map(reply -> reply.getString("content"))
                    .findFirst().orElse(null), java.util.Objects::nonNull);
        }
    }

    private static final class ManagementCommand implements NovaCommand {
        @Override
        public String name() { return "管理命令"; }

        @Override
        public String description() { return "测试管理命令"; }

        @Override
        public boolean requiresAdmin() { return true; }

        @Override
        public CommandReply execute(CommandContext context) {
            assertTrue(context.isAdmin());
            return CommandReply.of("管理命令已执行");
        }
    }

    private interface ConsoleCheck {
        void run(Rig rig) throws Exception;
    }
}
