package org.frostnova.nova.interact.console;

import com.alibaba.fastjson2.JSONObject;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.event.datasource.change.NovaDataSourceAddEvent;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.interact.auto.AutoDanmakuQueue;
import org.frostnova.nova.interact.auto.InteractStore;
import org.frostnova.nova.interact.auto.LiveInteractRuntime;
import org.frostnova.nova.interact.config.InteractProperties;
import org.frostnova.nova.interact.danmaku.DanmakuAccountChoice;
import org.frostnova.nova.interact.danmaku.DanmakuFailure;
import org.frostnova.nova.interact.danmaku.DanmakuResult;
import org.frostnova.nova.interact.danmaku.LiveDanmakuService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「直播间互动」页的接口
 * <p>
 * 抓的用户故障：试发时选的是一位主播，弹幕却去了别的房间；随手填一个房间号就能让机器人往那儿发；
 * 状态回包里漏出凭据。成员调不调得到这些接口由控制台那一侧的权限表与它的覆盖测试管。
 */
@DisplayName("直播间互动页的接口")
class LiveInteractConsoleControllerTest {
    private LiveDanmakuService danmaku;

    private MockMvc mvc;
    @TempDir Path dir;

    @BeforeEach
    void setUp() {
        danmaku = mock(LiveDanmakuService.class);
        when(danmaku.rooms()).thenReturn(List.of(new LiveDanmakuService.Room(501L, "示例主播", 1001L)));
        when(danmaku.accountStatus()).thenReturn(new LiveDanmakuService.AccountStatus(DanmakuAccountChoice.SPEAKER,
                true, null, null, 7L, null));
        NovaCoreProperties core = new NovaCoreProperties();
        core.getLive().setLiveDataPath(dir.resolve("live.json").toString());
        var store = new InteractStore(new NovaStateStore(core));
        var queue = new AutoDanmakuQueue(danmaku, mock(LiveDataService.class), store);
        var user = new PushUser();
        user.setPlatform("bilibili");
        user.setUid(501L);
        user.setRoomId(1001L);
        new LiveInteractRuntime(queue, store, danmaku).streamerChanged(new NovaDataSourceAddEvent(user));
        mvc = MockMvcBuilders.standaloneSetup(new LiveInteractConsoleController(danmaku, new InteractProperties(),
                store, queue)).build();
    }

    private JSONObject body(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return JSONObject.parseObject(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("升级后从未设置过的主播默认停发，管理员能读到默认上限")
    void untouchedRoomStartsDisabled() throws Exception {
        JSONObject reply = body(mvc.perform(get("/config/api/interact/danmaku/settings").param("uid", "501"))
                .andExpect(status().isOk()).andReturn());
        JSONObject settings = reply.getJSONObject("settings");
        assertEquals(false, settings.get("enabled"));
        assertEquals(6, settings.getIntValue("perMinute"));
    }

    @Test
    @DisplayName("状态：当前选项、账号能不能用、已添加的主播，没有任何凭据")
    void stateListsChoiceAccountAndRooms() throws Exception {
        JSONObject state = body(mvc.perform(get("/config/api/interact/danmaku/state")).andExpect(status().isOk()).andReturn());

        assertEquals("speaker", state.getString("choice"));
        assertEquals("novabot.bilibili.interact.danmaku.account", state.getString("settingKey"));
        assertTrue(state.getJSONObject("account").getBooleanValue("usable"));
        assertEquals("7", state.getJSONObject("account").getString("uid"));
        assertEquals("1001", state.getJSONArray("rooms").getJSONObject(0).getString("roomId"));
        String text = state.toJSONString().toLowerCase();
        assertFalse(text.contains("sessdata") || text.contains("csrf") || text.contains("cookie") || text.contains("jct"),
                state.toJSONString());
    }

    @Test
    @DisplayName("试发：按选中的主播找到房间再发；不认识的主播一律拒绝，不发")
    void testSendGoesToTheChosenStreamersRoom() throws Exception {
        when(danmaku.send(1001L, "你好")).thenReturn(new DanmakuResult(false, DanmakuFailure.BLOCKED_WORD,
                "被屏蔽词拦下了", 1001L, "你好", DanmakuAccountChoice.SPEAKER, 0, "f"));

        JSONObject reply = body(mvc.perform(post("/config/api/interact/danmaku/test").contentType("application/json")
                .content("{\"uid\":\"501\",\"text\":\"你好\"}")).andExpect(status().isOk()).andReturn());
        assertFalse(reply.getBooleanValue("sent"));
        assertEquals("BLOCKED_WORD", reply.getString("failure"));
        assertEquals("被屏蔽词拦下", reply.getString("failureText"));
        assertEquals("f", reply.getString("platformMessage"));
        verify(danmaku).send(1001L, "你好");
        JSONObject history = body(mvc.perform(get("/config/api/interact/danmaku/history").param("uid", "501")
                .param("outcome", "NOT_SENT")).andExpect(status().isOk()).andReturn());
        assertEquals(1, history.getIntValue("total"));
        JSONObject row = history.getJSONArray("rows").getJSONObject(0);
        assertEquals("test", row.getString("feature"));
        assertEquals("你好", row.getString("text"));

        mvc.perform(post("/config/api/interact/danmaku/test").contentType("application/json")
                .content("{\"uid\":\"999\",\"text\":\"你好\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/config/api/interact/danmaku/test").contentType("application/json")
                .content("{\"roomId\":\"1002\",\"text\":\"你好\"}")).andExpect(status().isBadRequest());
        verify(danmaku, times(1)).send(anyLong(), any());
    }

    @Test
    @DisplayName("移除主播后再也查不到这位主播最近一次试发的去向")
    void recentHistoryCanStillBeSelectedAfterRemoval() throws Exception {
        when(danmaku.send(1001L, "你好")).thenReturn(new DanmakuResult(true, null, "已发出", 1001L, "你好",
                DanmakuAccountChoice.SPEAKER, 0, null));
        mvc.perform(post("/config/api/interact/danmaku/test").contentType("application/json")
                .content("{\"uid\":\"501\",\"text\":\"你好\"}")).andExpect(status().isOk());
        when(danmaku.rooms()).thenReturn(List.of());
        JSONObject history = body(mvc.perform(get("/config/api/interact/danmaku/history").param("uid", "501"))
                .andExpect(status().isOk()).andReturn());
        assertEquals("示例主播", history.getJSONArray("rows").getJSONObject(0).getString("streamer"));
        JSONObject state = body(mvc.perform(get("/config/api/interact/danmaku/state")).andReturn());
        assertEquals("501", state.getJSONArray("historyRooms").getJSONObject(0).getString("uid"));
    }

    @Test
    @DisplayName("保存成功后刷新却丢失计划，或错误的间隔、条数覆盖了正在使用的设置")
    void savesValidPlansAndRejectsInvalidReplacements() throws Exception {
        String route = "/config/api/interact/danmaku/settings";
        JSONObject plan = JSONObject.of("id", "one", "enabled", true, "texts", List.of("你好"));
        JSONObject settings = JSONObject.of("enabled", true, "features", JSONObject.of("timed", true), "plans", List.of(plan));
        JSONObject payload = JSONObject.of("uid", "501", "settings", settings);
        mvc.perform(post(route).contentType("application/json").content(payload.toJSONString())).andExpect(status().isOk());
        JSONObject saved = body(mvc.perform(get(route).param("uid", "501")).andReturn()).getJSONObject("settings");
        JSONObject savedPlan = saved.getJSONArray("plans").getJSONObject(0);
        assertTrue(saved.getBooleanValue("enabled"));
        assertEquals(10, savedPlan.getIntValue("minutes"));
        assertTrue(savedPlan.getBooleanValue("skipQuiet"));
        assertFalse(savedPlan.getBooleanValue("random"));
        settings.put("perMinute", 21);
        mvc.perform(post(route).contentType("application/json").content(payload.toJSONString())).andExpect(status().isBadRequest());
        settings.put("perMinute", 6);
        plan.put("minutes", 1);
        mvc.perform(post(route).contentType("application/json").content(payload.toJSONString())).andExpect(status().isBadRequest());
        settings.put("plans", java.util.stream.IntStream.range(0, 11)
                .mapToObj(i -> JSONObject.of("id", "plan" + i, "texts", List.of("你好"))).toList());
        mvc.perform(post(route).contentType("application/json").content(payload.toJSONString())).andExpect(status().isBadRequest());
        JSONObject after = body(mvc.perform(get(route).param("uid", "501")).andReturn()).getJSONObject("settings");
        assertEquals(saved, after);
        JSONObject state = body(mvc.perform(get("/config/api/interact/danmaku/state")).andReturn());
        assertTrue(state.getJSONArray("rooms").getJSONObject(0).getBooleanValue("autoEnabled"));
    }

    @Test
    @DisplayName("字数上限：只查已添加的主播的房间")
    void limitOnlyForAddedStreamers() throws Exception {
        when(danmaku.lengthLimit(1001L)).thenReturn(new LiveDanmakuService.LengthLimit(30, true));

        JSONObject limit = body(mvc.perform(get("/config/api/interact/danmaku/limit").param("uid", "501"))
                .andExpect(status().isOk()).andReturn());
        assertEquals(30, limit.getIntValue("limit"));
        assertTrue(limit.getBooleanValue("fromPlatform"));

        mvc.perform(get("/config/api/interact/danmaku/limit").param("uid", "777")).andExpect(status().isBadRequest());
    }
}
