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
        assertTrue(ranking.execute(context("数据排行榜", "礼物", "甲")).content().contains("不展示金额"));
        assertFalse(ranking.execute(context("数据排行榜", "礼物", "乙")).content().contains("不展示金额"));
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
            var t = raw.getJSONArray("targets").getJSONObject(0);
            var target = new PushTarget(); target.setUser(user); target.setPlatform(t.getString("platform"));
            target.setType(PushTargetType.of(t.getIntValue("type"))); target.setNum(t.getLong("num")); target.setEnabled(true);
            target.setRevenueVisible(t.getBoolean("revenueVisible")); target.setQuietHours(t.getObject("quietHours", PushTarget.QuietHours.class));
            target.setDisabledCommands(t.getList("disabledCommands", String.class));
            user.setTargets(List.of(target)); users.add(user);
        }
    }
}
