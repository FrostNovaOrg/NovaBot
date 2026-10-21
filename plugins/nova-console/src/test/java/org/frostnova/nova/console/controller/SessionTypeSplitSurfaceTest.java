package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.command.CommandContext;
import org.frostnova.nova.core.command.CommandDispatcher;
import org.frostnova.nova.core.command.CommandSettingsService;
import org.frostnova.nova.core.command.NovaCommand;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigUiController;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.sender.PushGate;
import org.frostnova.nova.core.service.AtSubscriptionService;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.LiveSessionArchive;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.service.RevenueVisibilityService;
import org.frostnova.nova.core.service.SessionQuietHoursService;
import org.frostnova.nova.core.service.StreamerNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 控制台的会话设置按「平台 + 会话类型 + 号」分开写
 * <p>
 * 走 HTTP 这一层量：请求体里少了 type 时回的是什么状态码，要从接口外面才看得见。
 */
@DisplayName("控制台写会话设置要带会话类型，同号的群与好友各占一行")
class SessionTypeSplitSurfaceTest {
    private static final String PLATFORM = "qq-onebot";

    /** 群号与好友账号同一个数 */
    private static final long NUM = 123456L;

    private static final String COMMAND = "直播报告";

    private static final String BASE = ConfigUiController.BASE_PATH + "/api/state";

    @TempDir
    Path dir;

    private MockMvc mvc;
    private ChannelSettingsFile channels;

    @BeforeEach
    void setUp() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        NovaStateStore store = new NovaStateStore(properties);

        PushUser user = new PushUser();
        user.setUid(1L);
        user.setUname("主播甲");
        user.setPlatform("bilibili");
        user.getTargets().add(target(PushTargetType.GROUP));
        user.getTargets().add(target(PushTargetType.FRIEND));
        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getAllUsers()).thenReturn(List.of(user));

        NovaCommand command = mock(NovaCommand.class);
        when(command.name()).thenReturn(COMMAND);
        when(command.aliases()).thenReturn(List.of());
        when(command.disableable()).thenReturn(true);
        when(command.usageKeys(any(CommandContext.class))).thenReturn(List.of(COMMAND));
        CommandDispatcher dispatcher = mock(CommandDispatcher.class);
        when(dispatcher.all()).thenReturn(List.of(command));

        SessionQuietHoursService quiet = new SessionQuietHoursService(store);
        RuntimeStateController controller = new RuntimeStateController(
                dispatcher,
                new CommandSettingsService(store),
                mock(AtSubscriptionService.class),
                store,
                dataSource,
                new RevenueVisibilityService(store),
                mock(LiveDataService.class),
                new StreamerNames(new LiveSessionArchive(properties)),
                quiet,
                new PushGate(properties, quiet));
        channels = new ChannelSettingsFile(properties, dataSource); channels.attach(controller);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static PushTarget target(PushTargetType type) {
        PushTarget target = new PushTarget();
        target.setPlatform(PLATFORM);
        target.setType(type);
        target.setNum(NUM);
        return target;
    }

    @Test
    @DisplayName("四个写口缺 type 或 type 认不出时回 400 并说缺什么，什么都不改")
    void missingTypeIsRejected() throws Exception {
        // 用户故障：控制台只带平台与号来写，后端无从知道改的是群还是同号的好友
        List<String> paths = List.of("/command", "/commands", "/revenue", "/quiet");
        List<JSONObject> bodies = List.of(
                body(null).fluentPut("command", COMMAND).fluentPut("disabled", true),
                body(null).fluentPut("commands", List.of(COMMAND)).fluentPut("disabled", true),
                body(null).fluentPut("visible", true),
                body(null).fluentPut("mode", "off"));
        for (int i = 0; i < paths.size(); i++) {
            for (Object bad : new Object[]{null, 2, -1, "群"}) {
                JSONObject request = bodies.get(i).clone();
                if (bad != null) {
                    request.put("type", bad);
                }
                MvcResult result = send(paths.get(i), request);
                String where = paths.get(i) + " type=" + bad;
                assertEquals(400, result.getResponse().getStatus(), where);
                JSONObject reply = JSON.parseObject(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
                assertEquals(Boolean.FALSE, reply.getBoolean("success"), where);
                assertTrue(reply.getString("message").contains("type"), where + " 的回话没说缺的是 type：" + reply);
            }
        }

        for (JSONObject row : sessions()) {
            assertEquals(Boolean.FALSE, row.getBoolean("revenueExplicit"), "被拒的请求改动了 " + row);
            assertEquals("follow", row.getString("quietMode"), "被拒的请求改动了 " + row);
            assertEquals(0, row.getJSONArray("disabled").size(), "被拒的请求改动了 " + row);
        }
    }

    @Test
    @DisplayName("同号的群与好友在会话列表里各一行；带 type 写只改那一边")
    void typedWriteTouchesOneSide() throws Exception {
        // 用户故障：给好友设了金额可见、免打扰，同号群也跟着变；群里关的命令，好友也用不了
        assertEquals(200, send("/revenue", body(0).fluentPut("visible", true)).getResponse().getStatus());
        assertEquals(200, send("/quiet", body(0).fluentPut("mode", "off")).getResponse().getStatus());
        assertEquals(200, send("/commands", body(1).fluentPut("commands", List.of(COMMAND))
                .fluentPut("disabled", true)).getResponse().getStatus());

        assertEquals(2, sessions().size());
        JSONObject group = channels.target(PLATFORM, PushTargetType.GROUP, NUM);
        JSONObject friend = channels.target(PLATFORM, PushTargetType.FRIEND, NUM);
        assertEquals(true, friend.getBoolean("revenueVisible"));
        assertEquals("off", friend.getJSONObject("quietHours").getString("mode"));
        assertFalse(group.containsKey("revenueVisible"));
        assertFalse(group.containsKey("quietHours"));
        assertEquals(List.of(COMMAND), group.getJSONArray("disabledCommands"));
        assertEquals(200, send("/commands", body(1).fluentPut("commands", List.of(COMMAND))
                .fluentPut("disabled", false)).getResponse().getStatus());
        assertEquals(200, send("/command", body(0).fluentPut("command", COMMAND)
                .fluentPut("disabled", true)).getResponse().getStatus());
        assertEquals(List.of(COMMAND), channels.target(PLATFORM, PushTargetType.FRIEND, NUM).getJSONArray("disabledCommands"));
        assertFalse(channels.target(PLATFORM, PushTargetType.GROUP, NUM).containsKey("disabledCommands"));
    }

    private JSONObject body(Integer type) {
        JSONObject body = new JSONObject();
        body.put("platform", PLATFORM);
        body.put("num", NUM);
        if (type != null) {
            body.put("type", type);
        }
        return body;
    }

    private MvcResult send(String path, JSONObject body) throws Exception {
        return mvc.perform(post(BASE + path).contentType(MediaType.APPLICATION_JSON)
                .content(body.toJSONString())).andReturn();
    }

    private List<JSONObject> sessions() throws Exception {
        MvcResult result = mvc.perform(get(BASE)).andReturn();
        JSONArray rows = JSON.parseObject(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .getJSONArray("sessions");
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            list.add(rows.getJSONObject(i));
        }
        return list;
    }

    private JSONObject row(int typeCode) throws Exception {
        List<JSONObject> rows = sessions();
        assertEquals(2, rows.size(), "同号的群与好友应各占一行：" + rows);
        JSONObject hit = rows.stream()
                .filter(item -> Integer.valueOf(typeCode).equals(item.getInteger("typeCode")))
                .findFirst().orElse(null);
        assertNotNull(hit, "会话列表里没有类型码 " + typeCode + " 的那一行：" + rows);
        return hit;
    }
}
