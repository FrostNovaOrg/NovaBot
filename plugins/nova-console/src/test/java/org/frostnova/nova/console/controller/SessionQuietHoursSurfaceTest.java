package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.command.CommandDispatcher;
import org.frostnova.nova.core.command.CommandSettingsService;
import org.frostnova.nova.core.config.NovaCoreProperties;
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

import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「本群设置」静音时段那一行的接口面：存进去、在会话清单里读得回来、「静音中」由闸门判
 */
@DisplayName("本群设置的静音时段接口")
class SessionQuietHoursSurfaceTest {
    private static final String PLATFORM = "qq-onebot";

    private static final long GROUP = 30003L;

    @TempDir
    Path dir;

    private NovaCoreProperties properties;

    private SessionQuietHoursService quietHours;

    private RuntimeStateController controller;

    @BeforeEach
    void setUp() {
        properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());

        PushTarget target = new PushTarget();
        target.setPlatform(PLATFORM);
        target.setType(PushTargetType.GROUP);
        target.setNum(GROUP);
        PushUser user = new PushUser();
        user.setUid(1L);
        user.setUname("主播甲");
        user.setPlatform("bilibili");
        user.getTargets().add(target);

        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getAllUsers()).thenReturn(List.of(user));

        NovaStateStore store = new NovaStateStore(properties);
        quietHours = new SessionQuietHoursService(store);
        controller = new RuntimeStateController(
                mock(CommandDispatcher.class),
                mock(CommandSettingsService.class),
                mock(AtSubscriptionService.class),
                store,
                dataSource,
                new RevenueVisibilityService(store),
                mock(LiveDataService.class),
                new StreamerNames(new LiveSessionArchive(properties)),
                quietHours,
                new PushGate(properties, quietHours));
    }

    @Test
    @DisplayName("没设过的会话读成跟全局，全局那一对起止一并给出")
    void defaultsToFollow() {
        properties.getPush().setQuietStart("23:00");
        properties.getPush().setQuietEnd("08:00");

        JSONObject state = controller.state();
        JSONObject session = session(state);
        assertEquals("follow", session.getString("quietMode"));
        assertNull(session.getString("quietStart"));
        assertEquals("23:00", state.getJSONObject("quietGlobal").getString("start"));
        assertEquals("08:00", state.getJSONObject("quietGlobal").getString("end"));
    }

    @Test
    @DisplayName("设成自己的时段：读回起止，此刻落在时段里时 quietActive 为真")
    void customRoundTripsAndReportsActive() {
        JSONObject result = controller.setQuietHours(body("custom", aroundNow()[0], aroundNow()[1]));
        assertTrue(result.getBooleanValue("success"), result.toJSONString());

        JSONObject session = session(controller.state());
        assertEquals("custom", session.getString("quietMode"));
        assertEquals(aroundNow()[0], session.getString("quietStart"));
        assertEquals(aroundNow()[1], session.getString("quietEnd"));
        assertTrue(session.getBooleanValue("quietActive"));
    }

    @Test
    @DisplayName("设成不静音：全局正在静音时这个会话不算静音中")
    void offIsNotActiveDuringGlobalQuiet() {
        properties.getPush().setQuietStart(aroundNow()[0]);
        properties.getPush().setQuietEnd(aroundNow()[1]);
        assertTrue(session(controller.state()).getBooleanValue("quietActive"), "前置：跟全局时此刻在静音");

        controller.setQuietHours(body("off", null, null));

        JSONObject session = session(controller.state());
        assertEquals("off", session.getString("quietMode"));
        assertFalse(session.getBooleanValue("quietActive"));
    }

    @Test
    @DisplayName("改回跟全局即清除记录")
    void followClears() {
        controller.setQuietHours(body("off", null, null));
        controller.setQuietHours(body("follow", null, null));

        assertEquals("follow", session(controller.state()).getString("quietMode"));
        assertTrue(quietHours.all().isEmpty());
    }

    @Test
    @DisplayName("选了自己的时段却没填起止、或档位认不出：拒收，不落记录")
    void rejectsIncompleteInput() {
        assertFalse(controller.setQuietHours(body("custom", "23:00", "")).getBooleanValue("success"));
        assertFalse(controller.setQuietHours(body("sometimes", null, null)).getBooleanValue("success"));
        assertTrue(quietHours.all().isEmpty());
    }

    @Test
    @DisplayName("自己的时段起止不是 HH:mm：拒收并说明格式，不落记录")
    void rejectsMalformedTimes() {
        for (String[] pair : new String[][]{{"25:00", "07:00"}, {"8点", "07:00"}, {"23:00", "7:00"}, {"23:00", "07:00:00"}}) {
            JSONObject result = controller.setQuietHours(body("custom", pair[0], pair[1]));
            assertFalse(result.getBooleanValue("success"), pair[0] + "–" + pair[1] + ": " + result);
            assertTrue(result.getString("message").contains("HH:mm"), result.getString("message"));
        }
        assertTrue(quietHours.all().isEmpty(), quietHours.all().toString());

        assertTrue(controller.setQuietHours(body("custom", " 23:00 ", "07:00")).getBooleanValue("success"),
                "两头空白照旧裁掉");
    }

    @Test
    @DisplayName("会话号不是数字：回一句错误说明，不抛")
    void rejectsNonNumericSession() {
        JSONObject body = body("off", null, null);
        body.put("num", "群一");

        JSONObject result = assertDoesNotThrow(() -> controller.setQuietHours(body));
        assertFalse(result.getBooleanValue("success"));
        assertTrue(result.getString("message").contains("会话号"), result.getString("message"));
        assertTrue(quietHours.all().isEmpty());
    }

    private JSONObject body(String mode, String start, String end) {
        JSONObject body = new JSONObject();
        body.put("platform", PLATFORM);
        body.put("num", GROUP);
        body.put("mode", mode);
        body.put("start", start);
        body.put("end", end);
        return body;
    }

    private static JSONObject session(JSONObject state) {
        JSONArray sessions = state.getJSONArray("sessions");
        for (int i = 0; i < sessions.size(); i++) {
            JSONObject item = sessions.getJSONObject(i);
            if (PLATFORM.equals(item.getString("platform")) && item.getLongValue("num") == GROUP) {
                return item;
            }
        }
        throw new AssertionError("会话清单里没有 " + GROUP + ": " + sessions);
    }

    private static String[] aroundNow() {
        LocalTime now = LocalTime.now();
        DateTimeFormatter format = DateTimeFormatter.ofPattern("HH:mm");
        return new String[]{now.minusHours(1).format(format), now.plusHours(1).format(format)};
    }
}
