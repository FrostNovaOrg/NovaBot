package org.frostnova.nova.core.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.command.CommandSettingsService;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigurationValidator;
import org.frostnova.nova.core.datasource.JsonDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.sender.PushGate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.*;
import java.time.LocalTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChannelSettingsMigrationTest {
    @TempDir Path dir;
    Path file;
    NovaStateStore state;
    RevenueVisibilityService revenue;
    SessionQuietHoursService quiet;
    DatasourceChannelStore channels;
    ChannelSettingsMigration migration;
    JsonDataSource source;
    ListAppender<ILoggingEvent> migrationLog;

    @BeforeEach void setup() throws Exception {
        var props = new NovaCoreProperties();
        props.getLive().setLiveDataPath(dir.resolve("live.json").toString());
        file = dir.resolve("datasource.json");
        props.getDatasource().setJsonPath(file.toString());
        source = mock(JsonDataSource.class);
        state = new NovaStateStore(props);
        revenue = new RevenueVisibilityService(state);
        quiet = new SessionQuietHoursService(state);
        var handlers = mock(NovaEventHandlerService.class);
        when(handlers.getAcceptedHandlerClasses()).thenReturn(Set.of("example.CurrentHandler"));
        channels = new DatasourceChannelStore(props, source, new ConfigurationValidator(handlers));
        migration = new ChannelSettingsMigration(state, channels, revenue, quiet);
        migrationLog = new ListAppender<>();
        migrationLog.start();
        ((Logger) LoggerFactory.getLogger(ChannelSettingsMigration.class)).addAppender(migrationLog);
        Files.writeString(file, """
                [{"platform":"bilibili","uid":1,"targets":[{"platform":"qq","type":1,"num":100,"extra":null}]},
                 {"platform":"bilibili","uid":2,"enabled":false,"targets":[{"platform":"qq","type":1,"num":100,"enabled":false}]}]
                """);
        revenue.set("qq", PushTargetType.GROUP, 100L, true);
        quiet.set("qq", PushTargetType.GROUP, 100L, SessionQuietHoursService.Mode.CUSTOM, "23:00", "07:00");
        state.write(CommandSettingsService.NAMESPACE, data -> data.put("qq:1:100",
                JSONObject.of("直播报告", 1, "菜单", 1, "禁用命令", 1, "启用命令", 1)));
    }

    @AfterEach void detachLog() {
        ((Logger) LoggerFactory.getLogger(ChannelSettingsMigration.class)).detachAppender(migrationLog);
        migrationLog.stop();
    }

    boolean logged(String text) { return migrationLog.list.stream().anyMatch(event -> event.getFormattedMessage().contains(text)); }

    @Test @DisplayName("升级后两位主播保留群设置，停用通道也保留，旧状态完全不变")
    void copiesAllExistingChannelsOnce() throws Exception {
        String oldRevenue = state.namespace(RevenueVisibilityService.NAMESPACE).toJSONString();
        String oldQuiet = state.namespace(SessionQuietHoursService.NAMESPACE).toJSONString();
        String oldCommands = state.namespace(CommandSettingsService.NAMESPACE).toJSONString();
        migration.migrate();
        var users = JSONArray.parseArray(Files.readString(file));
        for (int i = 0; i < 2; i++) {
            var target = target(users, i);
            assertTrue(target.getBooleanValue("revenueVisible"));
            assertEquals(JSONObject.of("mode", "custom", "start", "23:00", "end", "07:00"), target.getJSONObject("quietHours"));
            assertEquals(List.of("直播报告"), target.getList("disabledCommands", String.class));
        }
        assertTrue(target(users, 0).containsKey("extra"));
        assertNull(target(users, 0).get("extra"));
        assertTrue(Files.readString(file).contains("\n  {\n    \""));
        assertEquals(oldRevenue, state.namespace(RevenueVisibilityService.NAMESPACE).toJSONString());
        assertEquals(oldQuiet, state.namespace(SessionQuietHoursService.NAMESPACE).toJSONString());
        assertEquals(oldCommands, state.namespace(CommandSettingsService.NAMESPACE).toJSONString());
        assertTrue(state.namespace(ChannelSettingsMigration.NAMESPACE).containsKey(ChannelSettingsMigration.MIGRATED_KEY));
        try (var files = Files.list(dir)) { assertEquals(1, files.filter(p -> p.toString().endsWith(".bak")).count()); }
        verify(source, times(1)).reload();
    }

    @Test @DisplayName("迁完重启后新增丙使用默认值，不再继承群里的旧设置")
    void newChannelDoesNotInheritAfterRestart() throws Exception {
        migration.migrate();
        channels.update(users -> users.add(JSONObject.of("platform", "bilibili", "uid", 3,
                "targets", JSONArray.of(JSONObject.of("platform", "qq", "type", 1, "num", 100)))), false);
        var loaded = new NovaStateStore(properties());
        loaded.onApplicationReadyEvent();
        try {
            new ChannelSettingsMigration(loaded, channels, new RevenueVisibilityService(loaded),
                    new SessionQuietHoursService(loaded)).migrate();
        } finally { loaded.onContextClosedEvent(); }
        var c = target(JSONArray.parseArray(Files.readString(file)), 2);
        assertFalse(c.containsKey("revenueVisible"), "新增丙应为默认金额显示");
        assertFalse(c.containsKey("quietHours"), "新增丙应跟全局静音");
        assertFalse(c.containsKey("disabledCommands"), "新增丙应全开命令");
    }

    @Test @DisplayName("迁移中断后接着迁，已写过的目标不覆盖，默认值不展开")
    void resumesWithoutOverwritingExistingFields() throws Exception {
        channels.update(users -> target(users, 0).put("revenueVisible", false), false);
        migration.migrate();
        var users = JSONArray.parseArray(Files.readString(file));
        assertFalse(target(users, 0).getBooleanValue("revenueVisible"));
        assertFalse(target(users, 0).containsKey("quietHours"));
        assertTrue(target(users, 1).getBooleanValue("revenueVisible"));
        channels.update(data -> data.add(JSONObject.of("platform", "bilibili", "uid", 4,
                "targets", JSONArray.of(JSONObject.of("platform", "qq", "type", 0, "num", 101)))), false);
        state.write(ChannelSettingsMigration.NAMESPACE, data -> data.remove(ChannelSettingsMigration.MIGRATED_KEY));
        revenue.set("qq", PushTargetType.FRIEND, 101L, true);
        migration.migrate();
        assertFalse(target(JSONArray.parseArray(Files.readString(file)), 2).containsKey("revenueVisible"));
    }

    NovaCoreProperties properties() {
        var props = new NovaCoreProperties();
        props.getLive().setLiveDataPath(dir.resolve("live.json").toString());
        return props;
    }

    @Test @DisplayName("示例主播未填完、处理器已卸载或旧类型仍能启动，能迁的通道继续迁")
    void unrelatedInvalidEntriesDoNotBlockMigration() throws Exception {
        var users = JSONArray.parseArray(Files.readString(file));
        users.add(JSONObject.of("platform", "bilibili", "uid", null,
                "targets", JSONArray.of(JSONObject.of("platform", "qq", "type", 1, "num", 100))));
        target(users, 0).put("messages", JSONArray.of(JSONObject.of("handler", "example.RemovedHandler")));
        users.add(JSONObject.of("platform", "bilibili", "uid", 3,
                "targets", JSONArray.of(JSONObject.of("platform", "qq", "type", 2, "num", 100))));
        Files.writeString(file, users.toJSONString());
        assertDoesNotThrow(migration::migrate);
        var migrated = JSONArray.parseArray(Files.readString(file));
        assertTrue(target(migrated, 0).getBooleanValue("revenueVisible"));
        assertTrue(target(migrated, 1).getBooleanValue("revenueVisible"));
        assertFalse(target(migrated, 2).containsKey("revenueVisible"));
        assertFalse(target(migrated, 3).containsKey("revenueVisible"));
        assertTrue(logged("uid 未填"));
        assertTrue(logged("qq:2:100"));
    }

    @Test @DisplayName("一个群的旧命令记录损坏，不应让所有群的设置每次启动都迁不过来")
    void malformedCommandRecordOnlySkipsThatSetting() throws Exception {
        var users = JSONArray.parseArray(Files.readString(file));
        target(users, 1).put("num", 200);
        Files.writeString(file, users.toJSONString());
        revenue.set("qq", PushTargetType.GROUP, 200L, true);
        quiet.set("qq", PushTargetType.GROUP, 200L, SessionQuietHoursService.Mode.OFF, null, null);
        state.write(CommandSettingsService.NAMESPACE, data -> {
            data.put("qq:1:100", "broken");
            data.put("qq:1:200", JSONObject.of("直播报告", 1));
        });
        String oldCommands = state.namespace(CommandSettingsService.NAMESPACE).toJSONString();

        migration.migrate();

        var migrated = JSONArray.parseArray(Files.readString(file));
        assertTrue(target(migrated, 0).getBooleanValue("revenueVisible"), "坏命令记录不能挡住金额迁移");
        assertEquals("custom", target(migrated, 0).getJSONObject("quietHours").getString("mode"));
        assertFalse(target(migrated, 0).containsKey("disabledCommands"));
        assertTrue(target(migrated, 1).getBooleanValue("revenueVisible"), "别的群应照常迁移");
        assertEquals("off", target(migrated, 1).getJSONObject("quietHours").getString("mode"));
        assertEquals(List.of("直播报告"), target(migrated, 1).getList("disabledCommands", String.class));
        assertTrue(state.namespace(ChannelSettingsMigration.NAMESPACE).containsKey(ChannelSettingsMigration.MIGRATED_KEY));
        assertEquals(oldCommands, state.namespace(CommandSettingsService.NAMESPACE).toJSONString());
        assertTrue(logged("qq:1:100") && logged(CommandSettingsService.NAMESPACE), "应说明跳过了哪个群的哪一项");
        verify(source, times(1)).reload();
    }

    @Test @DisplayName("旧金额整段损坏，不应让各群的静音和命令每次启动都迁不过来")
    void malformedRevenueNamespaceOnlySkipsRevenue() throws Exception {
        var users = JSONArray.parseArray(Files.readString(file));
        target(users, 1).put("num", 200);
        Files.writeString(file, users.toJSONString());
        quiet.set("qq", PushTargetType.GROUP, 200L, SessionQuietHoursService.Mode.OFF, null, null);
        state.write(CommandSettingsService.NAMESPACE, data -> data.put("qq:1:200", JSONObject.of("直播报告", 1)));
        state.save();
        var stateFile = dir.resolve("state.json");
        var saved = JSONObject.parseObject(Files.readString(stateFile));
        saved.put(RevenueVisibilityService.NAMESPACE, "broken");
        Files.writeString(stateFile, saved.toJSONString());
        var loaded = new NovaStateStore(properties());
        loaded.onApplicationReadyEvent();
        try {
            new ChannelSettingsMigration(loaded, channels, new RevenueVisibilityService(loaded),
                    new SessionQuietHoursService(loaded)).migrate();
            var migrated = JSONArray.parseArray(Files.readString(file));
            for (int i = 0; i < 2; i++) {
                assertFalse(target(migrated, i).containsKey("revenueVisible"));
                assertNotNull(target(migrated, i).getJSONObject("quietHours"), "坏金额记录不能挡住静音迁移");
                assertEquals(i == 0 ? "custom" : "off", target(migrated, i).getJSONObject("quietHours").getString("mode"),
                        "坏金额记录不能挡住静音迁移");
                assertEquals(List.of("直播报告"), target(migrated, i).getList("disabledCommands", String.class));
            }
            assertTrue(loaded.namespace(ChannelSettingsMigration.NAMESPACE).containsKey(ChannelSettingsMigration.MIGRATED_KEY));
            assertEquals("broken", JSONObject.parseObject(Files.readString(stateFile)).get(RevenueVisibilityService.NAMESPACE));
            assertTrue(logged(RevenueVisibilityService.NAMESPACE), "应说明跳过了旧金额记录");
            verify(source, times(1)).reload();
        } finally { loaded.onContextClosedEvent(); }
    }

    @ParameterizedTest
    @CsvSource({"7:00,07:00,off", "23:00:00,07:00:00,custom", "23:00:05,07:00:10,custom", "23:00,23:00,off", "'',07:00,off"})
    @DisplayName("旧静音格式不让升级启动失败，有效秒数归一，原来不静音的仍不静音")
    void legacyQuietTimesDoNotBlockStartup(String start, String end, String expected) throws Exception {
        quiet.set("qq", PushTargetType.GROUP, 100L, SessionQuietHoursService.Mode.CUSTOM, start, end);
        assertDoesNotThrow(migration::migrate);
        var q = target(JSONArray.parseArray(Files.readString(file)), 0).getJSONObject("quietHours");
        assertNotNull(q, "旧版不静音的写法不能迁成跟全局");
        assertEquals(expected, q.getString("mode"));
        if (expected.equals("custom")) {
            assertEquals(LocalTime.parse(start).toString(), q.getString("start"));
            assertEquals(LocalTime.parse(end).toString(), q.getString("end"));
        }
        if (start.equals("7:00")) {
            assertTrue(logged("无效"));
            var props = properties();
            props.getPush().setQuietStart("00:00");
            props.getPush().setQuietEnd("23:59");
            var channel = new PushTarget();
            channel.setQuietHours(q.toJavaObject(PushTarget.QuietHours.class));
            assertTrue(new PushGate(props).allowedAt(LocalTime.NOON, channel), "旧版不跟全局静音的行为应保留");
        }
    }

    @Test @DisplayName("state.json 读不了时不迁不标记，修好后重启仍能保留原设置")
    void unreadableStateRetriesAfterRestart() throws Exception {
        state.save();
        String original = Files.readString(dir.resolve("state.json"));
        var loaded = new NovaStateStore(properties());
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.readString(dir.resolve("state.json")))
                    .thenThrow(new AccessDeniedException("state.json"));
            loaded.onApplicationReadyEvent();
            var retry = new ChannelSettingsMigration(loaded, channels, new RevenueVisibilityService(loaded), new SessionQuietHoursService(loaded));
            String before = Files.readString(file);
            assertDoesNotThrow(retry::migrate);
            assertFalse(loaded.namespace(ChannelSettingsMigration.NAMESPACE).containsKey(ChannelSettingsMigration.MIGRATED_KEY));
            assertEquals(before, Files.readString(file));
            assertTrue(logged("跳过通道设置迁移"));
        } finally { loaded.onContextClosedEvent(); }
        assertEquals(original, Files.readString(dir.resolve("state.json")));
        var repaired = new NovaStateStore(properties());
        repaired.onApplicationReadyEvent();
        try {
            new ChannelSettingsMigration(repaired, channels, new RevenueVisibilityService(repaired), new SessionQuietHoursService(repaired)).migrate();
            assertTrue(target(JSONArray.parseArray(Files.readString(file)), 0).getBooleanValue("revenueVisible"));
        } finally { repaired.onContextClosedEvent(); }
    }

    @Test @DisplayName("迁移写盘失败不挡启动和后续推送，不写完成标记并允许下次继续")
    void failedMigrationDoesNotBlockPushes() {
        var broken = mock(DatasourceChannelStore.class);
        doThrow(new IllegalStateException("sample disk error")).when(broken).migrate(any());
        var gate = new PushGate(properties());
        ReflectionTestUtils.invokeMethod(gate, "starting");
        assertDoesNotThrow(() -> {
            new ChannelSettingsMigration(state, broken, revenue, quiet).migrate();
            gate.ready();
        });
        assertFalse(state.namespace(ChannelSettingsMigration.NAMESPACE).containsKey(ChannelSettingsMigration.MIGRATED_KEY));
        var target = new PushTarget();
        assertTrue(gate.allowedFor(target));
        assertTrue(logged("迁移未完成"));
    }

    @Test @DisplayName("标记写入发生异常也不能留下已迁移标记，修好后仍可重试")
    void markerExceptionDoesNotCompleteMigration() {
        var failing = spy(state);
        doThrow(new IllegalStateException("sample write error")).when(failing).trySave();
        assertDoesNotThrow(() -> new ChannelSettingsMigration(failing, channels, revenue, quiet).migrate());
        assertFalse(failing.namespace(ChannelSettingsMigration.NAMESPACE).containsKey(ChannelSettingsMigration.MIGRATED_KEY));
        assertTrue(logged("迁移未完成"));
    }

    @Test @DisplayName("原群选了不静音，升级后不能变成跟全局而丢失通知")
    void copiesOffQuietHours() throws Exception {
        quiet.set("qq", PushTargetType.GROUP, 100L, SessionQuietHoursService.Mode.OFF, null, null);
        migration.migrate();
        var q = target(JSONArray.parseArray(Files.readString(file)), 0).getJSONObject("quietHours");
        assertNotNull(q); assertEquals("off", q.getString("mode"));
        var props = properties(); props.getPush().setQuietStart("00:00"); props.getPush().setQuietEnd("23:59");
        var gate = new PushGate(props);
        var target = new PushTarget();
        target.setQuietHours(q.toJavaObject(PushTarget.QuietHours.class));
        assertTrue(gate.allowedAt(LocalTime.NOON, target));
    }
    static JSONObject target(JSONArray users, int index) { return users.getJSONObject(index).getJSONArray("targets").getJSONObject(0); }
}
