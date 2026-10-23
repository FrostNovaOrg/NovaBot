package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.*;
import org.frostnova.nova.bilibili.command.BilibiliStreamerChoice;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.event.live.BilibiliLiveOffEvent;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.command.*;
import org.frostnova.nova.core.command.builtin.*;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.*;
import org.frostnova.nova.core.datasource.*;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.event.remote.NovaRemoteMessageEvent;
import org.frostnova.nova.core.model.*;
import org.frostnova.nova.core.sender.*;
import org.frostnova.nova.core.service.*;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.frostnova.nova.report.command.*;
import org.frostnova.nova.report.handler.BilibiliLiveReportPushHandler;
import org.frostnova.nova.report.painter.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChannelSettingsIntegrationTest {
    @TempDir Path dir;
    Path file;
    NovaCoreProperties props;
    JsonDataSource source;
    List<PushUser> users;
    NovaStateStore state;
    RevenueVisibilityService revenue;
    CommandSettingsService settings;
    DatasourceChannelStore channels;
    PushGate gate;
    LiveDataService live;
    BilibiliLiveReportPainter painter;
    BilibiliLiveReportCommand report;
    BilibiliRankingCommand ranking;
    MenuCommand menu;
    DisableCommand disable;
    EnableCommand enable;
    CommandDispatcher dispatcher;
    NovaMessageSender sender;
    ConfigUiController config;
    List<Message> replies;

    @BeforeEach @SuppressWarnings("unchecked") void setup() throws Exception {
        props = new NovaCoreProperties();
        file = dir.resolve("datasource.json");
        props.getDatasource().setJsonPath(file.toString());
        props.getLive().setLiveDataPath(dir.resolve("live.json").toString());
        Files.writeString(file, """
                [{"platform":"bilibili","uid":10001,"targets":[{"platform":"qq","type":1,"num":100,"enabled":true}]},
                 {"platform":"bilibili","uid":10002,"targets":[{"platform":"qq","type":1,"num":100,"enabled":true}]}]
                """);
        source = mock(JsonDataSource.class);
        refresh();
        when(source.getUsers("bilibili")).thenAnswer(i -> users);
        when(source.getAllUsers()).thenAnswer(i -> users);
        doAnswer(i -> { refresh(); return null; }).when(source).reload();
        state = new NovaStateStore(props);
        revenue = new RevenueVisibilityService(state);
        settings = new CommandSettingsService(state);
        var validator = new ConfigurationValidator(mock(NovaEventHandlerService.class));
        channels = new DatasourceChannelStore(props, source, validator);
        ReflectionTestUtils.setField(settings, "channels", channels);
        gate = new PushGate(props, new SessionQuietHoursService(state));
        ReflectionTestUtils.setField(gate, "dataSource", source);
        live = mock(LiveDataService.class);
        when(live.getLiveStatus(anyString(), anyLong())).thenReturn(Optional.of(true));
        painter = mock(BilibiliLiveReportPainter.class);
        when(painter.paint(anyString(), any(), any())).thenReturn(Optional.of("QUJD"));
        var choice = mock(BilibiliStreamerChoice.class);
        report = new BilibiliLiveReportCommand(source, choice, live, painter, revenue);
        ranking = new BilibiliRankingCommand(source, choice, live, mock(BilibiliDataQueryPainter.class), revenue, new NovaBilibiliProperties());
        sender = mock(NovaMessageSender.class);
        replies = new ArrayList<>();
        doAnswer(i -> { Message message = i.getArgument(0); if (gate.allowedFor(message)) replies.add(message); return null; }).when(sender).send(any());
        ObjectProvider<CommandDispatcher> provider = mock(ObjectProvider.class);
        menu = new MenuCommand(provider, settings);
        disable = new DisableCommand(provider, settings);
        enable = new EnableCommand(provider, settings);
        var plugin = new NovaCommand() {
            public String name() { return "天气"; }
            public String description() { return "查询天气"; }
            public CommandReply execute(CommandContext context) { return CommandReply.of("晴天"); }
        };
        var commands = List.<NovaCommand>of(report, ranking, menu, disable, enable, plugin);
        ObjectProvider<NovaCommand> commandProvider = mock(ObjectProvider.class);
        when(commandProvider.iterator()).thenAnswer(i -> commands.iterator());
        when(commandProvider.orderedStream()).thenAnswer(i -> commands.stream());
        ObjectProvider<CommandFollowUp> follow = mock(ObjectProvider.class);
        when(follow.iterator()).thenAnswer(i -> List.<CommandFollowUp>of().iterator());
        dispatcher = new CommandDispatcher(commandProvider, follow, settings, source, sender, props, TimelineWriter.NONE,
                new Clock() {
                    Instant time = Instant.parse("2026-10-01T00:00:00Z");
                    public ZoneId getZone() { return ZoneOffset.UTC; }
                    public Clock withZone(ZoneId zone) { return this; }
                    public Instant instant() { time = time.plusSeconds(5); return time; }
                });
        when(provider.getIfAvailable()).thenReturn(dispatcher);
        var constructor = Arrays.stream(ConfigUiController.class.getConstructors()).min(Comparator.comparingInt(java.lang.reflect.Constructor::getParameterCount)).orElseThrow();
        Object[] args = Arrays.stream(constructor.getParameterTypes()).map(type -> {
            if (type == NovaCoreProperties.class) return props;
            if (type == AbstractDataSource.class) return source;
            if (type == ConfigurationValidator.class) return validator;
            return mock(type);
        }).toArray();
        config = (ConfigUiController) constructor.newInstance(args);
    }

    // 多人管理时，每次保存、删除、静音、删订阅和签发口令都要查得到操作者，且不能泄露凭据。
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void mutationsIdentifyActorAndTarget(boolean oidc) {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer console-token-secret");
        String actor = "本地管理员";
        if (oidc) {
            var account = new org.frostnova.nova.core.config.ui.auth.ConfigUiAccount("admin-42",
                    org.frostnova.nova.core.config.ui.auth.ConfigUiAccount.Role.ADMIN,
                    org.frostnova.nova.core.config.ui.auth.ConfigUiAccount.Status.ENABLED,
                    "管理员甲", null, "https://id.example", "subject", List.of(), Instant.now(), null, true);
            request.setAttribute(ConfigUiAccess.ACCOUNT, account);
            actor = account.actor();
        }
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.frostnova.nova");
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start(); logger.addAppender(logs);
        try {
            var loaded = config.datasourceUser("bilibili", "10001");
            loaded.getJSONObject("user").put("enabled", false);
            assertTrue(config.saveDatasourceUser("bilibili", "10001", JSONObject.of("user", loaded.get("user"),
                    "version", loaded.get("version"))).getBody().getBooleanValue("success"));
            assertAudit(logs, actor, "bilibili:10001", "存了");
            logs.list.clear();
            var controller = runtime();
            controller.setQuietHours(JSONObject.of("platform", "qq", "type", 1, "num", 100,
                    "streamerPlatform", "bilibili", "streamerUid", 10001, "mode", "custom", "start", "23:00", "end", "07:00"));
            assertAudit(logs, actor, "qq:1:100", "23:00");
            assertAudit(logs, actor, "bilibili:10001", "静音");
            logs.list.clear();
            controller.removeSubscription(JSONObject.of("platform", "qq", "num", 100, "streamerUid", 10001,
                    "type", "live", "userUid", 9));
            assertAudit(logs, actor, "qq", "订阅");
            assertAudit(logs, actor, "10001", "订阅");
            logs.list.clear();
            loaded = config.datasourceUser("bilibili", "10001");
            assertTrue(config.deleteDatasourceUser("bilibili", "10001", JSONObject.of("version", loaded.get("version")))
                    .getBody().getBooleanValue("success"));
            assertAudit(logs, actor, "bilibili:10001", "删了");
            logs.list.clear();
            var tokens = new org.frostnova.nova.core.protocol.EventStreamTokenService(props.getLive());
            ReflectionTestUtils.setField(config, "eventStreamTokens", tokens);
            String token = config.issueEventToken(Map.of("label", "只读面板")).getString("token");
            assertNotNull(token);
            assertAudit(logs, actor, "指纹", "签发");
            assertFalse(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains(token)));
            logs.list.clear();
            config.revokeEventToken(org.frostnova.nova.core.lang.SecureToken.fingerprint(token));
            assertAudit(logs, actor, "指纹", "吊销");
            assertFalse(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains(token)));
            assertFalse(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("access-secret")));
        } finally {
            logger.detachAppender(logs); logs.stop();
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    // 成员可改主播名字，控制字符不能把一次通道设置伪造成多条审计日志。
    @Test
    void streamerNameCannotInjectAuditLines() {
        users.get(0).setUname("甲\n伪造\r\t\u2028\u2029名字");
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(DatasourceChannelStore.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start(); logger.addAppender(logs);
        try {
            var response = runtime().setRevenueVisibility(JSONObject.of("platform", "qq", "type", 1, "num", 100,
                    "streamerPlatform", "bilibili", "streamerUid", 10001, "visible", true));
            assertEquals(200, response.getStatusCode().value());
            assertTrue(response.getBody().getBooleanValue("success"));
            assertEquals(1, logs.list.size());
            String line = logs.list.get(0).getFormattedMessage();
            assertTrue(line.contains("甲 伪造    名字"), line);
            assertFalse(java.util.regex.Pattern.compile("[\\p{Cntrl}\\p{Zl}\\p{Zp}]").matcher(line).find(), line);
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    // 写盘后的审计运行时异常不能阻止设置生效和成功回包，警告应保留原因。
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void failedAuditStillReloadsAndReturnsSuccess(boolean argumentFailure) throws Exception {
        RuntimeException failure = argumentFailure ? new IllegalArgumentException("name unavailable")
                : new IllegalStateException("name unavailable");
        when(source.getAllUsers()).thenThrow(failure);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(DatasourceChannelStore.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start(); logger.addAppender(logs);
        try {
            var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(runtime()).build();
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/config/api/state/revenue")
                    .contentType("application/json").content(JSONObject.of("platform", "qq", "type", 1, "num", 100,
                            "streamerPlatform", "bilibili", "streamerUid", 10001, "visible", true).toJSONString()))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.success").value(true));
            assertTrue(JSONArray.parseArray(Files.readString(file)).getJSONObject(0).getJSONArray("targets")
                    .getJSONObject(0).getBooleanValue("revenueVisible"));
            verify(source).reload();
            assertTrue(target(0).getRevenueVisible());
            assertEquals(1, logs.list.size());
            assertEquals(ch.qos.logback.classic.Level.WARN, logs.list.get(0).getLevel());
            assertTrue(logs.list.get(0).getFormattedMessage().contains("审计"));
            assertNotNull(logs.list.get(0).getThrowableProxy());
            assertEquals(failure.getMessage(), logs.list.get(0).getThrowableProxy().getMessage());
            assertEquals(failure.getClass().getName(), logs.list.get(0).getThrowableProxy().getClassName());
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    static void assertAudit(ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs,
                            String actor, String target, String action) {
        assertFalse(logs.list.stream().anyMatch(event -> event.getFormattedMessage().contains("console-token-secret")));
        assertTrue(logs.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .anyMatch(line -> line.contains(actor) && line.contains(target) && line.contains(action)),
                "Missing audit: " + actor + " / " + target + " / " + action);
    }

    // 设置、连接和试发也必须留下可查的操作者与目的地，正文和连接凭据不能进日志。
    @Test
    @SuppressWarnings("unchecked")
    void remainingWriteEndpointsAreAuditedWithoutSecrets() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.frostnova.nova");
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start(); logger.addAppender(logs);
        try {
            String content = Files.readString(file);
            assertTrue(config.saveDatasource(JSONObject.of("content", content)).getBody().getBooleanValue("success"));
            assertAudit(logs, "本地管理员", "整份", "覆盖");
            assertTrue(config.addDatasourceUser(JSONObject.of("user", JSONObject.of("platform", "bilibili", "uid", 10003,
                    "targets", new JSONArray()))).getBody().getBooleanValue("success"));
            assertAudit(logs, "本地管理员", "bilibili:10003", "加了");
            var controller = runtime();
            controller.setRevenueVisibility(JSONObject.of("platform", "qq", "type", 1, "num", 100, "visible", true));
            assertAudit(logs, "本地管理员", "qq:1:100", "金额");
            controller.toggleCommand(JSONObject.of("platform", "qq", "type", 1, "num", 100, "command", "天气", "disabled", true));
            assertAudit(logs, "本地管理员", "qq:1:100", "禁用");
            controller.toggleCommands(JSONObject.of("platform", "qq", "type", 1, "num", 100, "commands", JSONArray.of("天气"), "disabled", false));
            assertAudit(logs, "本地管理员", "qq:1:100", "启用");
            controller.removeSubscription(JSONObject.of("platform", "qq", "num", 100, "streamerUid", 10001, "type", "live"));
            assertAudit(logs, "本地管理员", "qq:1:100", "清空");

            var metadata = (ConfigurationMetadataService) ReflectionTestUtils.getField(config, "metadataService");
            String key = "novabot.core.push.enabled";
            when(metadata.getKnownTypes()).thenReturn(Map.of(key, "java.lang.Boolean"));
            var configFile = (ConfigurationFileService) ReflectionTestUtils.getField(config, "fileService");
            when(configFile.write(anyMap())).thenAnswer(call -> new ArrayList<>(((Map<String, String>) call.getArgument(0)).keySet()));
            assertTrue(config.save(Map.of(key, "true")).getBody().getBooleanValue("success"));
            assertAudit(logs, "本地管理员", key, "设置");

            var tester = mock(org.frostnova.nova.core.account.BotConnectionTester.class);
            when(tester.apply(anyString(), anyInt(), anyInt(), anyString(), anyString())).thenReturn(
                    new org.frostnova.nova.core.account.BotConnectionTester.Applied(true, "已连接", Map.of("name", "qq")));
            ObjectProvider<org.frostnova.nova.core.account.BotConnectionTester> testers = mock(ObjectProvider.class);
            when(testers.orderedStream()).thenAnswer(call -> java.util.stream.Stream.of(tester));
            ReflectionTestUtils.setField(config, "connectionTesters", testers);
            ObjectProvider<BotConnectionContributor> connections = mock(ObjectProvider.class);
            when(connections.orderedStream()).thenAnswer(call -> java.util.stream.Stream.of((BotConnectionContributor) () -> "adapter.connections"));
            ReflectionTestUtils.setField(config, "botConnections", connections);
            assertTrue(config.saveBot(JSONObject.of("address", "localhost", "httpPort", "3000", "httpToken", "bot-token-secret"))
                    .getBooleanValue("success"));
            assertAudit(logs, "本地管理员", "机器人连接", "存了");

            var login = mock(org.frostnova.nova.core.account.AccountLoginProvider.class);
            when(login.platform()).thenReturn("bilibili");
            ObjectProvider<org.frostnova.nova.core.account.AccountLoginProvider> providers = mock(ObjectProvider.class);
            when(providers.orderedStream()).thenAnswer(call -> java.util.stream.Stream.of(login));
            ReflectionTestUtils.setField(config, "loginProviders", providers);
            assertTrue(config.logout(JSONObject.of("platform", "bilibili")).getBooleanValue("success"));
            assertAudit(logs, "本地管理员", "bilibili", "退出");
            var messageSender = (NovaMessageSender) ReflectionTestUtils.getField(config, "messageSender");
            when(messageSender.sendNow(any())).thenReturn(JSONObject.of("code", 0));
            assertTrue(config.testMessage(JSONObject.of("platform", "qq", "type", 1, "num", 100, "content", "message-secret"))
                    .getBooleanValue("success"));
            assertAudit(logs, "本地管理员", "qq:1:100", "测试消息");

            var push = new PushController(mock(RuntimeConfigurationApplier.class), configFile,
                    mock(NovaEventHandlerService.class), mock(PushTemplateDefaults.class), mock(org.frostnova.nova.core.health.PushActivityRecorder.class));
            push.togglePush(JSONObject.of("enabled", false)); push.togglePush(JSONObject.of("enabled", true));
            assertAudit(logs, "本地管理员", "全局推送", "暂停");
            assertAudit(logs, "本地管理员", "全局推送", "恢复");
            var handlers = (NovaEventHandlerService) ReflectionTestUtils.getField(push, "handlerService");
            when(handlers.getHandler("example.Handler")).thenReturn(Optional.of(mock(org.frostnova.nova.core.handler.NovaEventHandler.class)));
            assertTrue(push.saveTemplate(JSONObject.of("className", "example.Handler", "params", JSONObject.of("message", "message-secret")))
                    .getBody().getBooleanValue("success"));
            assertAudit(logs, "本地管理员", "example.Handler", "默认模板");

            var reports = mock(WeeklyReportDeliveryService.class);
            when(reports.trial(anyString(), anyLong(), any())).thenReturn(JSONObject.of("code", 0));
            new WeeklyReportPushController(reports, source).trial("bilibili", 10001L, "qq", 1, 100L);
            assertAudit(logs, "本地管理员", "qq:1:100", "周报");
            var credentials = mock(org.frostnova.nova.adapter.onebot.napcat.NapCatCredentialService.class);
            when(credentials.issue(true)).thenReturn(new org.frostnova.nova.adapter.onebot.napcat.NapCatCredentialService.Issued(
                    org.frostnova.nova.adapter.onebot.napcat.NapCatCredentialService.Outcome.OK, "napcat-credential-secret"));
            new org.frostnova.nova.adapter.onebot.napcat.NapCatBootstrapController(credentials).credential(true);
            assertAudit(logs, "本地管理员", "NapCat", "凭据");

            var alert = mock(org.frostnova.nova.core.alert.AlertChannel.class);
            when(alert.id()).thenReturn("qq"); when(alert.isAvailable()).thenReturn(true);
            when(alert.auditDestination()).thenReturn("会话 qq:1:100");
            doAnswer(call -> { ((java.util.function.Consumer<org.frostnova.nova.core.alert.AlertChannel.SendResult>) call.getArgument(2))
                    .accept(org.frostnova.nova.core.alert.AlertChannel.SendResult.sent()); return null; }).when(alert).sendReporting(anyString(), anyString(), any());
            ObjectProvider<org.frostnova.nova.core.alert.AlertChannel> alerts = mock(ObjectProvider.class);
            when(alerts.orderedStream()).thenAnswer(call -> java.util.stream.Stream.of(alert));
            new AlertTestController(new org.frostnova.nova.core.alert.AlertService(props, alerts, TimelineWriter.NONE)).test("qq");
            assertAudit(logs, "本地管理员", "qq:1:100", "告警");
            for (String secret : List.of("bot-token-secret", "message-secret", "napcat-credential-secret")) {
                assertFalse(logs.list.stream().anyMatch(event -> event.getFormattedMessage().contains(secret)), secret);
            }
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    // 群管理员改命令以后，日志必须能与控制台改动区分，不能记录发命令人的号码。
    @Test
    void groupCommandsIdentifyTheirSource() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(DatasourceChannelStore.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start(); logger.addAppender(logs);
        try {
            dispatcher.onRemoteMessage(new NovaRemoteMessageEvent("qq", "group", 100L, 987654321L,
                    "禁用命令 直播报告 甲", "owner", true));
            assertAudit(logs, "群里命令", "qq:1:100", "命令");
            assertAudit(logs, "群里命令", "bilibili:10001", "命令");
            assertFalse(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("987654321")));
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    @Test @DisplayName("关甲的直播报告后不点名直接答乙，点甲说已关闭，菜单仍列出")
    void disabledCommandFiltersStreamerCandidates() {
        target(0).setDisabledCommands(List.of("直播报告"));
        dispatch("直播报告 甲");
        assertTrue(replies.get(0).getContent().contains("已关闭「甲」的「直播报告」"));
        dispatch("直播报告");
        assertEquals(10002L, replies.get(1).getChannel().getUser().getUid());
        assertTrue(menu.execute(context("菜单")).content().contains("直播报告"));
        target(1).setDisabledCommands(List.of("直播报告"));
        assertFalse(menu.execute(context("菜单")).content().contains("直播报告"));
        dispatch("直播报告");
        assertTrue(replies.get(2).getContent().contains("本群已关闭「直播报告」命令"));
    }

    @Test @DisplayName("菜单后多打几个字也能看见可用的主播命令，两位都关闭才隐藏")
    void menuArgumentsDoNotSelectStreamers() {
        for (String text : List.of("菜单", "菜单 abc", "帮助 直播报告")) {
            dispatch(text);
            assertTrue(replies.get(replies.size() - 1).getContent().contains("直播报告"), text);
        }
        target(0).setDisabledCommands(List.of("直播报告"));
        target(1).setDisabledCommands(List.of("直播报告"));
        for (String text : List.of("菜单", "菜单 abc", "帮助 直播报告")) {
            dispatch(text);
            assertFalse(replies.get(replies.size() - 1).getContent().contains("直播报告"), text);
        }
    }

    @Test @DisplayName("QQ 开关无法保存无效推送配置时说明失败原因，不能一句话都不回")
    void invalidConfigurationExplainsToggleFailure() throws Exception {
        var raw = JSONArray.parseArray(Files.readString(file));
        raw.add(JSONObject.of("platform", "bilibili", "uid", null));
        Files.writeString(file, raw.toJSONString());
        String before = Files.readString(file);
        dispatch("禁用命令 直播报告 甲");
        assertEquals(1, replies.size(), "保存失败也应回复");
        assertTrue(replies.get(0).getContent().contains("这次没改"));
        assertTrue(replies.get(0).getContent().contains("uid"));
        assertEquals(before, Files.readString(file));
    }

    @Test @DisplayName("插件命令仍可按群禁用和启用，QQ 与旧接口及菜单口径一致")
    void pluginCommandsKeepSessionSettings() {
        dispatch("禁用命令 天气");
        assertTrue(settings.isDisabled("qq", PushTargetType.GROUP, 100L, "天气"));
        assertFalse(menu.execute(context("菜单")).content().contains("天气"));
        dispatch("天气");
        assertFalse(replies.get(replies.size() - 1).getContent().contains("晴天"));
        dispatch("启用命令 天气"); dispatch("天气");
        assertEquals("晴天", replies.get(replies.size() - 1).getContent());
        var controller = runtime();
        var body = JSONObject.of("platform", "qq", "type", 1, "num", 999, "command", "天气", "disabled", true);
        assertTrue(controller.toggleCommand(body).getBody().getBooleanValue("success"));
        assertTrue(settings.isDisabled("qq", PushTargetType.GROUP, 999L, "天气"));
        var orphan = controller.state().getJSONArray("sessions").stream().map(o -> (JSONObject) o)
                .filter(o -> o.getLongValue("num") == 999L).findFirst().orElseThrow();
        assertEquals(List.of("天气"), orphan.getJSONArray("disabled").toList(String.class));
        assertTrue(controller.toggleCommands(JSONObject.of("platform", "qq", "type", 1, "num", 999,
                "commands", JSONArray.of("天气"), "disabled", false)).getBody().getBooleanValue("success"));
        assertFalse(settings.isDisabled("qq", PushTargetType.GROUP, 999L, "天气"));
    }

    @Test @DisplayName("没有通道时金额和静音接口明确失败，不能谎称已生效")
    void emptySessionCannotChangeChannelSettings() {
        var controller = runtime();
        for (var result : List.of(
                controller.setRevenueVisibility(JSONObject.of("platform", "qq", "type", 1, "num", 999, "visible", true)),
                controller.setQuietHours(JSONObject.of("platform", "qq", "type", 1, "num", 999, "mode", "off")))) {
            assertFalse(result.getBody().getBooleanValue("success"));
            assertTrue(result.getBody().getString("message").contains("没有可改的通道"));
        }
    }

    @Test @DisplayName("旧金额静音和主播命令记录不再占残留会话，插件命令与订阅仍可管理")
    void orphanRowsOnlyIncludeEffectiveState() {
        revenue.set("qq", PushTargetType.GROUP, 999L, true);
        new SessionQuietHoursService(state).set("qq", PushTargetType.GROUP, 999L, SessionQuietHoursService.Mode.OFF, null, null);
        settings.disable("qq", PushTargetType.GROUP, 999L, "直播报告");
        assertFalse(runtime().state().getJSONArray("sessions").stream().map(o -> (JSONObject) o)
                .anyMatch(o -> o.getLongValue("num") == 999L));
        settings.disable("qq", PushTargetType.GROUP, 999L, "天气");
        var rows = runtime().state().getJSONArray("sessions").stream().map(o -> (JSONObject) o)
                .filter(o -> o.getLongValue("num") == 999L).toList();
        assertEquals(1, rows.size());
        assertEquals(List.of("天气"), rows.get(0).getJSONArray("disabled").toList(String.class));
        var subscriptions = new AtSubscriptionService(state);
        subscriptions.subscribe("qq", 888L, 10001L, "live", 9L);
        var controller = runtime();
        assertTrue(controller.state().getJSONArray("sessions").stream().map(o -> (JSONObject) o)
                .anyMatch(o -> o.getLongValue("num") == 888L));
        assertTrue(controller.removeSubscription(JSONObject.of("platform", "qq", "num", 888L,
                "streamerUid", 10001L, "type", "live")).getBooleanValue("success"));
        assertFalse(controller.state().getJSONArray("sessions").stream().map(o -> (JSONObject) o)
                .anyMatch(o -> o.getLongValue("num") == 888L));
    }

    @Test @DisplayName("按主播保存冲突区分已删除和仍在盘上，向导删后重加有据可依")
    void conflictReportsWhetherStreamerIsMissing() throws Exception {
        var loaded = config.datasourceUser("bilibili", "10001");
        var raw = JSONArray.parseArray(Files.readString(file));
        raw.getJSONObject(0).put("enabled", false);
        Files.writeString(file, raw.toJSONString());
        var body = JSONObject.of("user", loaded.getJSONObject("user"), "version", loaded.getString("version"));
        var present = config.saveDatasourceUser("bilibili", "10001", body);
        assertEquals(409, present.getStatusCode().value());
        assertEquals(Boolean.FALSE, present.getBody().getBoolean("missing"));
        raw.remove(0); Files.writeString(file, raw.toJSONString());
        var missing = config.saveDatasourceUser("bilibili", "10001", body);
        assertEquals(409, missing.getStatusCode().value());
        assertTrue(missing.getBody().getBooleanValue("missing"));
    }

    @Test @DisplayName("关自动重载时控制台保存仍立即生效，成功提示不让用户重启")
    void saveWithoutFileWatcherStillTakesEffect() {
        props.getDatasource().setJsonAutoReload(false);
        var loaded = config.datasourceUser("bilibili", "10001");
        loaded.getJSONObject("user").getJSONArray("targets").getJSONObject(0).put("revenueVisible", true);
        var saved = config.saveDatasourceUser("bilibili", "10001", JSONObject.of("user", loaded.getJSONObject("user"),
                "version", loaded.getString("version")));
        assertTrue(saved.getBody().getBooleanValue("success"));
        assertTrue(saved.getBody().getString("message").contains("已生效"));
        assertTrue(revenue.isVisible(target(0)));
    }

    RuntimeStateController runtime() {
        var controller = new RuntimeStateController(dispatcher, settings, new AtSubscriptionService(state), state,
                source, revenue, live, StreamerNames.none(), new SessionQuietHoursService(state), gate);
        ReflectionTestUtils.setField(controller, "channelStore", channels);
        return controller;
    }

    @Test @DisplayName("甲的取消订阅和名单开关不能限制乙，开关式取消也遵守甲的那一格")
    @SuppressWarnings("unchecked")
    void subscriptionAndListCommandsUseSelectedChannel() {
        var subscriptions = new AtSubscriptionService(state);
        var choice = mock(BilibiliStreamerChoice.class);
        var at = new org.frostnova.nova.bilibili.command.BilibiliLiveAtMeCommand(source, choice, subscriptions, settings);
        target(0).setDisabledCommands(List.of("取消开播@我", "开播@名单"));
        subscriptions.subscribe("qq", 100L, 10001L, "live", 9L);
        subscriptions.subscribe("qq", 100L, 10002L, "live", 9L);
        assertTrue(at.execute(context("开播@我", "甲")).content().contains("已关闭「甲」的「取消开播@我」"));
        assertTrue(subscriptions.contains("qq", 100L, 10001L, "live", 9L));
        assertTrue(at.execute(context("开播@我", "乙")).content().contains("已取消"));
        assertFalse(subscriptions.contains("qq", 100L, 10002L, "live", 9L));
        ObjectProvider<SessionMemberNames> names = mock(ObjectProvider.class);
        when(names.orderedStream()).thenAnswer(i -> java.util.stream.Stream.empty());
        var list = new org.frostnova.nova.bilibili.command.BilibiliAtListCommand(source, choice, subscriptions, names, settings);
        assertTrue(list.execute(context("开播@名单", "甲")).content().contains("已关闭「甲」"));
        assertFalse(list.execute(context("开播@名单", "乙")).content().contains("已关闭"));
        assertFalse(list.execute(context("@名单", "甲")).content().contains("开播"));
    }

    @Test @DisplayName("同群两位主播金额互不影响，下播与直播报告及金额榜使用各自设置")
    void revenueIsScopedToStreamer() {
        target(0).setRevenueVisible(false);
        target(1).setRevenueVisible(true);
        for (int i = 0; i < 2; i++) {
            var context = context("直播报告", i == 0 ? "甲" : "乙");
            report.execute(context);
            var handler = new BilibiliLiveReportPushHandler(mock(BilibiliApiUtil.class), sender, painter, revenue);
            var message = new PushMessage(); message.setTarget(target(i)); message.setParamsJsonObject(JSONObject.of("message", "{report}"));
            handler.handle(new BilibiliLiveOffEvent(new LiveStreamerInfo(users.get(i).getUid(), users.get(i).getUname(), 1L)), message);
        }
        var options = org.mockito.ArgumentCaptor.forClass(BilibiliLiveReportOptions.class);
        verify(painter, times(4)).paint(anyString(), any(), options.capture());
        assertEquals(List.of(false, false, true, true), options.getAllValues().stream().map(BilibiliLiveReportOptions::isShowRevenue).toList());
        assertTrue(ranking.execute(context("数据排行榜", "礼物", "甲")).content().contains("不展示「甲」的金额相关榜单"));
        var visibleReply = ranking.execute(context("数据排行榜", "礼物", "乙")).content();
        assertFalse(visibleReply.contains("不展示"));
        assertFalse(visibleReply.contains("金额相关榜单"));
    }

    @Test @DisplayName("甲静音不会阻止乙推送或菜单，两位都静音才停菜单，全局关闭仍拦一切")
    void quietChannelsAndUnscopedReplies() {
        var q = new PushTarget.QuietHours(); q.setMode("custom");
        q.setStart(LocalTime.now().minusHours(1).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));
        q.setEnd(LocalTime.now().plusHours(1).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));
        target(0).setQuietHours(q);
        var off = new PushTarget.QuietHours(); off.setMode("off"); target(1).setQuietHours(off);
        assertFalse(gate.allowedFor(target(0)));
        assertTrue(gate.allowedFor(target(1)));
        org.frostnova.nova.bilibili.handler.PushHandlerSupport.send(sender, target(0), "甲推送");
        org.frostnova.nova.bilibili.handler.PushHandlerSupport.send(sender, target(1), "乙推送");
        assertEquals(List.of("乙推送"), replies.stream().map(Message::getContent).toList());
        replies.clear();
        dispatch("菜单"); assertEquals(1, replies.size());
        dispatch("直播报告 甲"); assertEquals(1, replies.size());
        dispatch("直播报告 乙"); assertEquals(2, replies.size());
        target(1).setQuietHours(q);
        dispatch("菜单"); assertEquals(2, replies.size());
        props.getPush().setEnabled(false);
        target(1).setQuietHours(off);
        assertFalse(gate.allowedFor(target(1)));
        assertFalse(gate.alertsAllowed());
    }

    @Test @DisplayName("QQ 点名只关甲，不点名全关并报人数，名字不存在不改，管理命令不能关")
    void togglesPersistOnlyRequestedChannelsWithoutBackups() throws Exception {
        dispatch("禁用命令 直播报告 甲");
        assertTrue(settings.isDisabled(target(0), "直播报告"));
        assertFalse(settings.isDisabled(target(1), "直播报告"));
        assertTrue(replies.get(0).getContent().contains("已禁用「甲」在本群的「直播报告」"));
        dispatch("禁用命令 直播报告");
        assertTrue(settings.isDisabled(target(1), "直播报告"));
        assertTrue(replies.get(1).getContent().contains("1 位主播"));
        String before = Files.readString(file);
        dispatch("禁用命令 直播报告 不存在的人");
        assertEquals(before, Files.readString(file));
        assertTrue(replies.get(2).getContent().contains("本群没有配置"));
        for (String name : List.of("菜单", "启用命令", "禁用命令")) {
            dispatch("禁用命令 " + name);
            assertTrue(replies.get(replies.size() - 1).getContent().contains("不可开关"));
        }
        try (var files = Files.list(dir)) { assertEquals(0, files.filter(p -> p.toString().endsWith(".bak")).count()); }
        assertTrue(state.namespace(CommandSettingsService.NAMESPACE).isEmpty());
    }

    @Test @DisplayName("控制台只保存甲的金额；保存前 QQ 改过命令时旧页面收到冲突")
    void consoleSaveUsesChannelFieldsAndDetectsQqChanges() {
        var loaded = config.datasourceUser("bilibili", "10001");
        var a = loaded.getJSONObject("user");
        a.getJSONArray("targets").getJSONObject(0).put("revenueVisible", true);
        var saved = config.saveDatasourceUser("bilibili", "10001", JSONObject.of("user", a, "version", loaded.getString("version")));
        assertTrue(saved.getBody().getBooleanValue("success"));
        assertTrue(revenue.isVisible(target(0)));
        assertFalse(revenue.isVisible(target(1)));
        String version = saved.getBody().getString("version");
        dispatch("禁用命令 直播报告 甲");
        var conflict = config.saveDatasourceUser("bilibili", "10001", JSONObject.of("user", a, "version", version));
        assertEquals(409, conflict.getStatusCode().value());
        assertTrue(conflict.getBody().getString("message").contains("重新载入"));
    }

    @Test @DisplayName("旧金额接口不点主播改全部通道，点了主播只改该通道，旧状态保留")
    void compatibilityEndpointsUpdateDatasource() {
        var controller = new RuntimeStateController(dispatcher, settings, mock(AtSubscriptionService.class), state,
                source, revenue, live, StreamerNames.none(), new SessionQuietHoursService(state), gate);
        ReflectionTestUtils.setField(controller, "channelStore", channels);
        var body = JSONObject.of("platform", "qq", "type", 1, "num", 100L, "visible", true);
        assertTrue(controller.setRevenueVisibility(body).getBody().getBooleanValue("success"));
        assertTrue(revenue.isVisible(target(0))); assertTrue(revenue.isVisible(target(1)));
        body.put("streamerUid", 10001L); body.put("streamerPlatform", "bilibili"); body.put("visible", false);
        controller.setRevenueVisibility(body);
        assertFalse(revenue.isVisible(target(0))); assertTrue(revenue.isVisible(target(1)));
        assertTrue(state.namespace(RevenueVisibilityService.NAMESPACE).isEmpty());
        controller.toggleCommand(JSONObject.of("platform", "qq", "type", 1, "num", 100, "streamerUid", 10001L,
                "command", "直播报告", "disabled", true));
        assertTrue(settings.isDisabled(target(0), "直播报告")); assertFalse(settings.isDisabled(target(1), "直播报告"));
        controller.setQuietHours(JSONObject.of("platform", "qq", "type", 1, "num", 100, "mode", "off"));
        assertEquals("off", target(0).getQuietHours().getMode()); assertEquals("off", target(1).getQuietHours().getMode());
    }

    PushTarget target(int index) { return users.get(index).getTargets().get(0); }
    CommandContext context(String name, String... args) { return new CommandContext("qq", PushTargetType.GROUP, 100L, 9L, name, List.of(args), "", true); }
    void dispatch(String text) { dispatcher.onRemoteMessage(new NovaRemoteMessageEvent("qq", "group", 100L, 9L, text, "owner", true)); }
    void refresh() throws Exception {
        users = new ArrayList<>();
        for (Object entry : JSONArray.parseArray(Files.readString(file))) {
            var raw = (JSONObject) entry;
            var user = new PushUser(); user.setPlatform(raw.getString("platform")); user.setUid(raw.getLong("uid"));
            user.setUname(user.getUid() == 10001 ? "甲" : "乙"); user.setEnabled(true);
            if (raw.getJSONArray("targets").isEmpty()) { user.setTargets(List.of()); users.add(user); continue; }
            var t = raw.getJSONArray("targets").getJSONObject(0);
            var target = new PushTarget(); target.setUser(user); target.setPlatform(t.getString("platform"));
            target.setType(PushTargetType.of(t.getIntValue("type"))); target.setNum(t.getLong("num")); target.setEnabled(true);
            target.setRevenueVisible(t.getBoolean("revenueVisible")); target.setQuietHours(t.getObject("quietHours", PushTarget.QuietHours.class));
            target.setDisabledCommands(t.getList("disabledCommands", String.class));
            user.setTargets(List.of(target)); users.add(user);
        }
    }
}
