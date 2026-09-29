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
import org.frostnova.nova.core.service.AtSubscriptionService;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.LiveSessionArchive;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.service.RevenueVisibilityService;
import org.frostnova.nova.core.service.StreamerNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 起动时没查到主播昵称，运行状态页上会话与订阅名单里的主播名
 * <p>
 * 推送配置里通常只写 uid，昵称要等程序起动时去直播平台查回来。查不回来时这里原先退回 uid 数字，
 * 使用者认不出是谁；而主播页那一处会退回最近一场归档里的昵称，同一个人两处显示得不一样。
 */
@DisplayName("运行状态页的主播名：补全没查到昵称时退回最近一场")
class RuntimeStateStreamerNameTest {
    private static final long UID = 4242L;

    @TempDir
    Path dir;

    private NovaCoreProperties properties;

    private AbstractDataSource dataSource;

    private AtSubscriptionService subscriptions;

    @BeforeEach
    void setUp() {
        properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());

        dataSource = mock(AbstractDataSource.class);
        subscriptions = mock(AtSubscriptionService.class);
        when(subscriptions.all()).thenReturn(List.of(
                new AtSubscriptionService.Subscription("qq-onebot", 30003L, UID, "live", List.of(1L))));
    }

    private RuntimeStateController controller() {
        NovaStateStore store = new NovaStateStore(properties);
        return new RuntimeStateController(
                mock(CommandDispatcher.class),
                mock(CommandSettingsService.class),
                subscriptions,
                store,
                dataSource,
                new RevenueVisibilityService(store),
                mock(LiveDataService.class),
                new StreamerNames(new LiveSessionArchive(properties)));
    }

    private static PushUser user(String uname) {
        PushTarget target = new PushTarget();
        target.setPlatform("qq-onebot");
        target.setType(PushTargetType.GROUP);
        target.setNum(30003L);

        PushUser user = new PushUser();
        user.setUid(UID);
        user.setUname(uname);
        user.setPlatform("bilibili");
        user.setEnabled(true);
        user.getTargets().add(target);
        return user;
    }

    /** 往场次归档里追加一场，只写取昵称要用的几项 */
    private void archive(long uid, String uname, long startTime) throws IOException {
        JSONObject line = new JSONObject();
        line.put("platform", "bilibili");
        line.put("uid", uid);
        line.put("uname", uname);
        line.put("startTime", startTime);
        line.put("endTime", startTime + 3_600_000L);
        Files.writeString(dir.resolve("sessions.jsonl"), line.toJSONString() + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String sessionStreamer(JSONObject state) {
        JSONArray names = state.getJSONArray("sessions").getJSONObject(0).getJSONArray("streamers");
        assertEquals(1, names.size());
        return names.getString(0);
    }

    private static String subscriptionStreamer(JSONObject state) {
        return state.getJSONArray("subscriptions").getJSONObject(0).getString("streamerName");
    }

    @Test
    @DisplayName("内存没昵称、有归档场次：会话与订阅名单都显示最近一场的昵称")
    void blankNameFallsBackToLatestSession() throws IOException {
        when(dataSource.getAllUsers()).thenReturn(List.of(user("")));
        archive(UID, "早先的昵称", 1_700_000_000_000L);
        archive(UID, "最近的昵称", 1_700_100_000_000L);
        archive(UID + 1, "别人的昵称", 1_700_200_000_000L);

        JSONObject state = controller().state();

        assertEquals("最近的昵称", sessionStreamer(state));
        assertEquals("最近的昵称", subscriptionStreamer(state));
    }

    @Test
    @DisplayName("内存有昵称：照旧显示内存的，不被归档里的旧名盖掉")
    void loadedNameWinsOverArchive() throws IOException {
        when(dataSource.getAllUsers()).thenReturn(List.of(user("查回来的昵称")));
        archive(UID, "归档里的旧昵称", 1_700_100_000_000L);

        JSONObject state = controller().state();

        assertEquals("查回来的昵称", sessionStreamer(state));
        assertEquals("查回来的昵称", subscriptionStreamer(state));
    }

    @Test
    @DisplayName("内存没昵称、这位也没有归档场次：照旧退回 uid")
    void noNameAnywhereFallsBackToUid() throws IOException {
        when(dataSource.getAllUsers()).thenReturn(List.of(user("")));
        archive(UID + 1, "别人的昵称", 1_700_200_000_000L);

        JSONObject state = controller().state();

        assertEquals(String.valueOf(UID), sessionStreamer(state));
        assertEquals(String.valueOf(UID), subscriptionStreamer(state));
    }
}
