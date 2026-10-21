package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.command.CommandSettingsService;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigurationValidator;
import org.frostnova.nova.core.datasource.JsonDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
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

    @BeforeEach void setup() throws Exception {
        var props = new NovaCoreProperties();
        props.getLive().setLiveDataPath(dir.resolve("live.json").toString());
        file = dir.resolve("datasource.json");
        props.getDatasource().setJsonPath(file.toString());
        source = mock(JsonDataSource.class);
        state = new NovaStateStore(props);
        revenue = new RevenueVisibilityService(state);
        quiet = new SessionQuietHoursService(state);
        channels = new DatasourceChannelStore(props, source,
                new ConfigurationValidator(mock(NovaEventHandlerService.class)));
        migration = new ChannelSettingsMigration(state, channels, revenue, quiet);
        Files.writeString(file, """
                [{"platform":"bilibili","uid":1,"targets":[{"platform":"qq","type":1,"num":100,"extra":null}]},
                 {"platform":"bilibili","uid":2,"enabled":false,"targets":[{"platform":"qq","type":1,"num":100,"enabled":false}]}]
                """);
        revenue.set("qq", PushTargetType.GROUP, 100L, true);
        quiet.set("qq", PushTargetType.GROUP, 100L, SessionQuietHoursService.Mode.CUSTOM, "23:00", "07:00");
        state.write(CommandSettingsService.NAMESPACE, data -> data.put("qq:1:100",
                JSONObject.of("直播报告", 1, "菜单", 1, "禁用命令", 1, "启用命令", 1)));
    }

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
    static JSONObject target(JSONArray users, int index) { return users.getJSONObject(index).getJSONArray("targets").getJSONObject(0); }
}
