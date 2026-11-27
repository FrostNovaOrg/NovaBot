package org.frostnova.nova.interact.console;

import com.alibaba.fastjson2.JSONObject;

import org.frostnova.nova.bilibili.service.BilibiliPkOpponentService;
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
        new LiveInteractRuntime(queue, store, danmaku, mock(BilibiliPkOpponentService.class)).streamerChanged(new NovaDataSourceAddEvent(user));
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
    @DisplayName("修改答谢后刷新丢失，或保存了不能渲染的话术和越界的窗口")
    void persistsThanksAndRejectsInvalidChangesAtomically() throws Exception {
        String route = "/config/api/interact/danmaku/settings";
        JSONObject thanks = JSONObject.of("giftMinYuan", 0.5, "giftWindowSeconds", 3,
                "giftIncludeFree", true, "followWindowSeconds", 120, "shareWindowSeconds", 10,
                "templates", JSONObject.of("gift", "{主播}谢谢{用户}的{礼物}", "share", " "));
        JSONObject settings = JSONObject.of("enabled", true, "features", JSONObject.of("gift", true), "thanks", thanks);
        JSONObject payload = JSONObject.of("uid", "501", "settings", settings);
        mvc.perform(post(route).contentType("application/json").content(payload.toJSONString())).andExpect(status().isOk());
        JSONObject saved = body(mvc.perform(get(route).param("uid", "501")).andReturn()).getJSONObject("settings");
        assertTrue(saved.containsKey("thanks"));
        assertEquals(0.5, saved.getJSONObject("thanks").getDoubleValue("giftMinYuan"));
        assertTrue(saved.getJSONObject("thanks").getBooleanValue("giftIncludeFree"));
        assertEquals("{主播}谢谢{用户}的{礼物}", saved.getJSONObject("thanks").getJSONObject("templates").getString("gift"));
        for (var change : List.of(JSONObject.of("giftWindowSeconds", 2), JSONObject.of("giftWindowSeconds", 61),
                JSONObject.of("shareWindowSeconds", 121), JSONObject.of("followWindowSeconds", 9),
                JSONObject.of("giftMinYuan", -1))) {
            settings.put("thanks", change);
            mvc.perform(post(route).contentType("application/json").content(payload.toJSONString())).andExpect(status().isBadRequest());
        }
        for (String key : List.of("gift", "membership", "superChat", "follow", "share")) {
            String invalid = key.equals("gift") ? "{等级}" : "{礼物}";
            settings.put("thanks", JSONObject.of("templates", JSONObject.of(key, "谢谢" + invalid)));
            var error = body(mvc.perform(post(route).contentType("application/json").content(payload.toJSONString()))
                    .andExpect(status().isBadRequest()).andReturn());
            assertTrue(error.getString("message").contains(invalid));
        }
        assertEquals(saved, body(mvc.perform(get(route).param("uid", "501")).andReturn()).getJSONObject("settings"));
    }

    @Test
    @DisplayName("保存名单后大 UID 变了，或者错误行和无效占位符覆盖了已经保存的欢迎")
    void savesWelcomeAndIdentifiesInvalidRows() throws Exception {
        String route = "/config/api/interact/danmaku/settings";
        var person = JSONObject.of("uid", "9007199254740993", "alias", "小明", "template", "欢迎{用户}");
        var welcome = JSONObject.of("minimumLevel", "COMMANDER", "people", List.of(person),
                "guardTemplate", "{主播}欢迎{等级}{用户}", "listedTemplate", "{用户}来了");
        var payload = JSONObject.of("uid", "501", "settings", JSONObject.of("welcome", welcome,
                "features", JSONObject.of("guardWelcome", true, "listedWelcome", true, "pauseActivities", true)));
        mvc.perform(post(route).contentType("application/json").content(payload.toJSONString())).andExpect(status().isOk());
        var saved = body(mvc.perform(get(route).param("uid", "501")).andReturn()).getJSONObject("settings");
        assertEquals(welcome, saved.getJSONObject("welcome"));
        var people = new java.util.ArrayList<>();
        people.add(person);
        for (Object invalid : List.of("abc", 0, -1, 1.5, "9007199254740993")) {
            welcome.put("people", List.of(person, JSONObject.of("uid", invalid)));
            var error = body(mvc.perform(post(route).contentType("application/json").content(payload.toJSONString()))
                    .andExpect(status().isBadRequest()).andReturn());
            assertTrue(error.getString("message").contains("第 2 行"));
        }
        for (int i = 1; i <= 50; i++) people.add(JSONObject.of("uid", i));
        welcome.put("people", people);
        var error = body(mvc.perform(post(route).contentType("application/json").content(payload.toJSONString()))
                .andExpect(status().isBadRequest()).andReturn());
        assertTrue(error.getString("message").contains("第 51 行"));
        for (var invalid : List.of(JSONObject.of("guardTemplate", "{礼物}"), JSONObject.of("listedTemplate", "{等级}"),
                JSONObject.of("people", List.of(JSONObject.of("uid", "1", "template", "{等级}"))),
                JSONObject.of("minimumLevel", "UNKNOWN"))) {
            payload.getJSONObject("settings").put("welcome", invalid);
            mvc.perform(post(route).contentType("application/json").content(payload.toJSONString())).andExpect(status().isBadRequest());
        }
        assertEquals(saved, body(mvc.perform(get(route).param("uid", "501")).andReturn()).getJSONObject("settings"));
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
    @Test
    @DisplayName("错误规则覆盖已保存设置，或错误提示没说清要改哪一条")
    void validatesRulesAndPreservesLastSavedSettings() throws Exception {
        String route = "/config/api/interact/danmaku/settings";
        var rule = JSONObject.of("keywords", List.of("😀".repeat(20)), "replies", List.of("{用户}好"));
        var settings = JSONObject.of("keywordRules", List.of(rule), "pkTemplate", "{主播}对阵{对手}");
        var payload = JSONObject.of("uid", "501", "settings", settings);
        mvc.perform(post(route).contentType("application/json").content(payload.toJSONString())).andExpect(status().isOk());
        var reply = body(mvc.perform(get(route).param("uid", "501")).andReturn());
        assertTrue(reply.getString("defaultPkTemplate") != null && reply.getString("defaultPkTemplate").contains("{粉丝}"));
        var saved = reply.getJSONObject("settings");
        var savedRule = saved.getJSONArray("keywordRules").getJSONObject(0);
        assertEquals(60, savedRule.getIntValue("cooldownSeconds"));
        assertTrue(savedRule.getBooleanValue("enabled"));
        assertFalse(savedRule.getBooleanValue("exact"));
        assertFalse(savedRule.getBooleanValue("random"));
        for (var bad : List.of(JSONObject.of("keywords", List.of()), JSONObject.of("keywords", List.of("　")),
                JSONObject.of("keywords", List.of("😀".repeat(21))), JSONObject.of("keywords", 123),
                JSONObject.of("keywords", java.util.Collections.nCopies(6, "词")),
                JSONObject.of("replies", List.of()), JSONObject.of("replies", List.of("\uFEFF")),
                JSONObject.of("replies", List.of("句".repeat(61))), JSONObject.of("replies", List.of("{对手}")),
                JSONObject.of("replies", java.util.Collections.nCopies(6, "好")), JSONObject.of("exact", "false"),
                JSONObject.of("cooldownSeconds", 5), JSONObject.of("cooldownSeconds", 3601))) {
            var second = new JSONObject(rule);
            second.putAll(bad);
            settings.put("keywordRules", List.of(rule, second));
            var error = body(mvc.perform(post(route).contentType("application/json").content(payload.toJSONString()))
                    .andExpect(status().isBadRequest()).andReturn());
            assertTrue(error.getString("message").contains("第 2 条"), error::toString);
        }
        settings.put("keywordRules", java.util.Collections.nCopies(31, rule));
        var overflow = body(mvc.perform(post(route).contentType("application/json").content(payload.toJSONString()))
                .andExpect(status().isBadRequest()).andReturn());
        assertTrue(overflow.getString("message").contains("第 31 条"));
        settings.put("keywordRules", List.of(rule));
        settings.put("pkTemplate", "{用户}");
        mvc.perform(post(route).contentType("application/json").content(payload.toJSONString())).andExpect(status().isBadRequest());
        assertEquals(saved, body(mvc.perform(get(route).param("uid", "501")).andReturn()).getJSONObject("settings"));
    }

    @Test
    void savesPkFanWelcomeAndRejectsInvalidLimitOrTemplate() throws Exception {
        String route = "/config/api/interact/danmaku/settings";
        var defaults = body(mvc.perform(get(route).param("uid", "501")).andReturn());
        assertEquals("欢迎 {用户} 来串门", defaults.getString("defaultPkFanWelcomeTemplate"));
        assertEquals(3, defaults.getJSONObject("settings").getIntValue("pkFanWelcomeLimit"));
        var options = JSONObject.of("enabled", true, "features", JSONObject.of("pkFanWelcome", true),
                "pkFanWelcomeTemplate", "{用户}戴着{对手}的{牌子}来看{主播}", "pkFanWelcomeLimit", 20);
        var payload = JSONObject.of("uid", "501", "settings", options);
        mvc.perform(post(route).contentType("application/json").content(payload.toJSONString())).andExpect(status().isOk());
        var saved = body(mvc.perform(get(route).param("uid", "501")).andReturn()).getJSONObject("settings");
        assertEquals(20, saved.getIntValue("pkFanWelcomeLimit"));
        assertEquals(options.getString("pkFanWelcomeTemplate"), saved.getString("pkFanWelcomeTemplate"));
        assertTrue(saved.getJSONObject("features").getBooleanValue("pkFanWelcome"));
        for (Object invalid : List.of(0, 21, 1.5, "5")) {
            options.put("pkFanWelcomeLimit", invalid);
            mvc.perform(post(route).contentType("application/json").content(payload.toJSONString())).andExpect(status().isBadRequest());
        }
        options.put("pkFanWelcomeLimit", 1);
        options.put("pkFanWelcomeTemplate", "{舰队}");
        mvc.perform(post(route).contentType("application/json").content(payload.toJSONString())).andExpect(status().isBadRequest());
        assertEquals(saved, body(mvc.perform(get(route).param("uid", "501")).andReturn()).getJSONObject("settings"));
    }

    @Test
    @DisplayName("清空 PK 话术后保存被拒，无法恢复默认播报")
    void clearingPkTemplateRestoresDefault() throws Exception {
        String route = "/config/api/interact/danmaku/settings";
        var result = body(mvc.perform(post(route).contentType("application/json")
                .content("{\"uid\":\"501\",\"settings\":{\"pkTemplate\":null}}")).andExpect(status().isOk()).andReturn());
        assertEquals("", result.getJSONObject("settings").getString("pkTemplate"));
    }
}
