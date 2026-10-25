package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.*;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.*;
import org.frostnova.nova.core.config.ui.auth.*;
import org.frostnova.nova.core.datasource.*;
import org.frostnova.nova.core.service.*;
import org.frostnova.nova.core.timeline.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class StreamerSelfTest {
    @TempDir Path dir;
    Path file;
    NovaCoreProperties properties;
    JsonDataSource source;
    MockMvc mvc;
    TimelineStore timeline;
    ConfigurationValidator validator;
    ConfigUiAccount member = new ConfigUiAccount("member-test", ConfigUiAccount.Role.MEMBER,
            ConfigUiAccount.Status.ENABLED, "测试成员", null, null, null,
            List.of("bilibili:1"), Instant.EPOCH, null, true);

    @BeforeEach void setup() throws Exception {
        properties = new NovaCoreProperties();
        file = dir.resolve("datasource.json");
        properties.getDatasource().setJsonPath(file.toString());
        properties.getDatasource().setJsonAutoReload(false);
        properties.getConfigUi().setBackupKeep(0);
        Files.writeString(file, """
                [{"platform":"bilibili","uid":1,"uname":"测试主播甲","targets":[
                  {"platform":"qq-onebot","type":0,"num":81003,"messages":[]},
                  {"platform":"qq-onebot","type":0,"num":81002,"messages":[]},
                  {"platform":"qq-onebot","type":1,"num":82001,"messages":[]}]},
                 {"platform":"bilibili","uid":2,"enabled":false,"targets":[
                  {"platform":"qq-other","type":0,"num":81001,"streamerSelf":true,"messages":[]}]}]
                """);
        var registry = mock(DataSourceServiceRegistry.class);
        when(registry.platforms()).thenReturn(List.of("bilibili"));
        when(registry.getDataSourceService("bilibili")).thenReturn(Optional.of(mock(DataSourceService.class)));
        source = new JsonDataSource(mock(ApplicationEventPublisher.class), registry, message -> true, properties.getDatasource());
        source.load();
        validator = new ConfigurationValidator(mock(NovaEventHandlerService.class));
        timeline = mock(TimelineStore.class);
        var constructor = Arrays.stream(ConfigUiController.class.getConstructors())
                .min(Comparator.comparingInt(java.lang.reflect.Constructor::getParameterCount)).orElseThrow();
        Object[] args = Arrays.stream(constructor.getParameterTypes()).map(type -> {
            if (type == NovaCoreProperties.class) return properties;
            if (type == AbstractDataSource.class) return source;
            if (type == ConfigurationValidator.class) return validator;
            if (type == TimelineStore.class) return timeline;
            if (type == PushTemplateDefaults.class) return new PushTemplateDefaults(properties);
            return mock(type);
        }).toArray();
        mvc = MockMvcBuilders.standaloneSetup(constructor.newInstance(args))
                .addInterceptors(new ConfigUiApiInterceptor(mock(ConfigUiAuthService.class), null, registry)).build();
    }
    @AfterEach void close() { source.shutdown(); }
    String path(int uid) { return "/config/api/datasource/users/bilibili/" + uid; }
    JSONObject load(int uid) throws Exception {
        return JSONObject.parseObject(mvc.perform(get(path(uid)).requestAttr(ConfigUiAccess.ACCOUNT, ConfigUiAccount.local(Instant.EPOCH))).andReturn().getResponse().getContentAsString());
    }
    org.springframework.mock.web.MockHttpServletResponse save(int uid, JSONObject loaded, ConfigUiAccount account) throws Exception {
        return mvc.perform(post(path(uid)).requestAttr(ConfigUiAccess.ACCOUNT, account)
                .contentType("application/json").content(loaded.toJSONString())).andReturn().getResponse();
    }

    // 防止小结发进群里、发给两个人，或让私聊中的同一号码对应两位主播。
    @ParameterizedTest @ValueSource(strings = {"group", "two", "shared", "invalid"})
    void rejectsUnsafeAssignmentsWithoutChangingFile(String kind) throws Exception {
        String before = Files.readString(file);
        JSONObject loaded = load(1);
        JSONArray targets = loaded.getJSONObject("user").getJSONArray("targets");
        String reason;
        if (kind.equals("group")) { targets.getJSONObject(2).put("streamerSelf", true); reason = "群通道不能标成主播本人"; }
        else if (kind.equals("two")) {
            targets.getJSONObject(0).put("num", 81003);
            targets.getJSONObject(0).put("streamerSelf", true);
            targets.getJSONObject(1).put("streamerSelf", true); reason = "最多";
        } else if (kind.equals("shared")) { targets.getJSONObject(0).put("num", 81001); targets.getJSONObject(0).put("streamerSelf", true); reason = "另一位主播"; }
        else { targets.getJSONObject(1).put("streamerSelf", "true"); reason = "true、false"; }
        var response = save(1, loaded, ConfigUiAccount.local(Instant.EPOCH));
        assertEquals(400, response.getStatus(), response.getContentAsString());
        String text = response.getContentAsString();
        assertTrue(text.contains(reason), text);
        assertTrue(text.contains("通道") || text.contains("推送目标"), text);
        assertEquals(before, Files.readString(file));
        verify(timeline, never()).record(any());
    }

    // 成员应能设置自己负责的主播；越权改别人时不能把小结拉到自己的号码上。
    @Test void memberCanMarkClearAndReplaceWithOneTracePerSave() throws Exception {
        for (int index : new int[]{1, -1, 1, 0}) {
            JSONObject loaded = load(1);
            JSONArray targets = loaded.getJSONObject("user").getJSONArray("targets");
            targets.getJSONObject(0).put("num", 81003);
            for (int i = 0; i < targets.size(); i++) targets.getJSONObject(i).put("streamerSelf", i == index);
            var response = save(1, loaded, member);
            assertEquals(200, response.getStatus(), response.getContentAsString());
            assertTrue(JSONObject.parseObject(response.getContentAsString()).getBooleanValue("success"));
        }
        var captured = ArgumentCaptor.forClass(TimelineEvent.class);
        verify(timeline, atLeastOnce()).record(captured.capture());
        int recorded = captured.getAllValues().size();
        var events = captured.getAllValues().stream()
                .filter(event -> event.type().name().equals("STREAMER_SELF_CHANGED")).toList();
        assertEquals(4, events.size(), "每次标记变动只能记一条；备份清理另有自己的记录");
        for (var event : events) {
            assertEquals("STREAMER_SELF_CHANGED", event.type().name());
            assertTrue(event.text().contains("member-test"), event.text());
            assertTrue(event.text().contains("bilibili:1"), event.text());
            assertEquals("1", event.detail().get("streamerUid"));
        }
        assertTrue(events.get(0).text().contains("81002"));
        assertTrue(events.get(1).text().contains("取消"));
        assertTrue(events.get(3).text().contains("81002") && events.get(3).text().contains("81003"));
        String before = Files.readString(file);
        assertEquals(404, save(2, load(2), member).getStatus());
        assertEquals(before, Files.readString(file));
        verify(timeline, times(recorded)).record(any());
    }

    // 升级后标记必须保留，旧配置不能自动勾上；只改标记也要替换运行中的配置。
    @Test void diskReloadInventoryComparisonAndChannelStoreKeepMark() throws Exception {
        var previous = source.getUser("bilibili", 1L).orElseThrow().getTargets().get(1);
        assertFalse(Boolean.TRUE.equals(JSON.parseObject(JSON.toJSONString(previous)).getBoolean("streamerSelf")));
        var loaded = load(1);
        loaded.getJSONObject("user").getJSONArray("targets").getJSONObject(1).put("streamerSelf", true);
        assertTrue(JSONObject.parseObject(save(1, loaded, ConfigUiAccount.local(Instant.EPOCH)).getContentAsString()).getBooleanValue("success"));
        source.reload();
        var current = source.getUser("bilibili", 1L).orElseThrow().getTargets().get(1);
        assertTrue(JSON.parseObject(JSON.toJSONString(current)).getBooleanValue("streamerSelf"));
        assertFalse(previous.same(current));
        assertEquals(previous, current);
        assertEquals(previous.hashCode(), current.hashCode());
        var unmarked = JSONObject.parseObject(JSON.toJSONString(previous));
        unmarked.put("streamerSelf", false);
        assertTrue(previous.same(unmarked.toJavaObject(org.frostnova.nova.core.model.PushTarget.class)), "缺失与 false 都表示没勾");
        var inventory = new ConfigUiConfiguredUsers(properties).users(List.of());
        assertTrue(JSON.parseObject(JSON.toJSONString(inventory.get(1).getTargets().get(0))).getBooleanValue("streamerSelf"), "停用主播的标记也要读入");
        assertTrue(JSON.parseObject(JSON.toJSONString(inventory.get(0).getTargets().get(1))).getBooleanValue("streamerSelf"));
        new DatasourceChannelStore(properties, source, validator).change("qq-onebot", org.frostnova.nova.core.enums.PushTargetType.FRIEND,
                81002L, "bilibili", 1L, target -> target.put("reportPalette", "warm"));
        assertTrue(JSONArray.parseArray(Files.readString(file)).getJSONObject(0).getJSONArray("targets").getJSONObject(1).getBooleanValue("streamerSelf"));
        var summary = JSONObject.parseObject(mvc.perform(get("/config/api/datasource/users").requestAttr(ConfigUiAccess.ACCOUNT, ConfigUiAccount.local(Instant.EPOCH))).andReturn().getResponse().getContentAsString());
        assertTrue(summary.getJSONArray("users").getJSONObject(0).getJSONArray("targets").getJSONObject(1).getBooleanValue("streamerSelf"));
    }

    // 其他通道设置入口也不能绕过标记校验，成功改动仍须留下时间线。
    @Test void channelStoreValidatesAndRecordsMark() throws Exception {
        var channels = new DatasourceChannelStore(properties, source, validator, timeline);
        channels.update(users -> users.getJSONObject(0).getJSONArray("targets").getJSONObject(1).put("streamerSelf", true), false, "test-admin");
        verify(timeline).record(argThat(event -> event.type().name().equals("STREAMER_SELF_CHANGED")
                && event.text().contains("test-admin") && event.text().contains("81002")));
        String before = Files.readString(file);
        var error = assertThrows(IllegalArgumentException.class, () -> channels.update(users ->
                users.getJSONObject(0).getJSONArray("targets").getJSONObject(2).put("streamerSelf", true), false));
        assertTrue(error.getMessage().contains("群通道不能标成主播本人"));
        assertEquals(before, Files.readString(file));
        verify(timeline, times(1)).record(any());
    }

    // 重排通道或把未勾显式写成 false，不能凭空出现更换接收人的记录。
    @Test void unchangedMarkAndTargetOrderDoNotCreateTrace() throws Exception {
        var loaded = load(1);
        JSONArray targets = loaded.getJSONObject("user").getJSONArray("targets");
        targets.getJSONObject(0).put("streamerSelf", false);
        Collections.reverse(targets);
        assertTrue(JSONObject.parseObject(save(1, loaded, member).getContentAsString()).getBooleanValue("success"));
        verify(timeline, never()).record(any());
    }

    // 整份配置导入也要留下标记变动，避免另一个保存入口漏记操作者。
    @Test void wholeDatasourceSaveRecordsAssignment() throws Exception {
        var users = JSONArray.parseArray(Files.readString(file));
        users.getJSONObject(0).getJSONArray("targets").getJSONObject(1).put("streamerSelf", true);
        var response = mvc.perform(post("/config/api/datasource").requestAttr(ConfigUiAccess.ACCOUNT, ConfigUiAccount.local(Instant.EPOCH))
                .contentType("application/json").content(JSONObject.of("content", users.toJSONString()).toJSONString())).andReturn().getResponse();
        assertTrue(JSONObject.parseObject(response.getContentAsString()).getBooleanValue("success"));
        verify(timeline).record(argThat(event -> event.type().name().equals("STREAMER_SELF_CHANGED") && event.text().contains("81002")));
    }
}
