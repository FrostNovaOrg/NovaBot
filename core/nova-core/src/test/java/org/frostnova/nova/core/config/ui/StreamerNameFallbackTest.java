package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.alert.AlertService;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.health.HealthProbe;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.protocol.EventStreamTokenService;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.sender.PushGate;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.NovaSenderService;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.frostnova.nova.core.timeline.TimelineStore;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 起动时没查到主播昵称，首页在播与状态接口里的主播名
 * <p>
 * 推送配置里通常只写 uid，昵称要等程序起动时去直播平台查回来。断网、被风控时查不回来，
 * 内存里的昵称就是空串——此时首页「在播」与状态接口给出的主播名是空的，使用者认不出是谁；
 * 而主播页那一处会退回最近一场归档里的昵称，同一个人两处显示得不一样。
 * <p>
 * 每处三种情形：内存没昵称但有归档场次、内存有昵称、两样都没有。
 */
@DisplayName("首页在播与状态接口的主播名：补全没查到昵称时退回最近一场")
class StreamerNameFallbackTest {
    @TempDir
    Path dir;

    private NovaCoreProperties properties;

    private AbstractDataSource dataSource;

    private LiveDataService liveDataService;

    @BeforeEach
    void setUp() {
        properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());

        dataSource = mock(AbstractDataSource.class);

        liveDataService = mock(LiveDataService.class);
        when(liveDataService.getLiveStatus(any(), any())).thenReturn(Optional.of(true));
        when(liveDataService.getLiveStartTime(any(), any())).thenReturn(Optional.empty());
    }

    @SuppressWarnings("unchecked")
    private ConfigUiController controller() {
        ObjectProvider<HealthProbe> healthProbes = mock(ObjectProvider.class);
        when(healthProbes.orderedStream()).thenAnswer(invocation -> java.util.stream.Stream.empty());

        NovaSenderService senders = mock(NovaSenderService.class);
        when(senders.getSenderNames()).thenReturn(Set.of("默认"));

        TimelineStore timeline = mock(TimelineStore.class);
        when(timeline.countsOn(any())).thenReturn(Map.of());

        ObjectProvider<org.frostnova.nova.core.alert.AlertChannel> channels = mock(ObjectProvider.class);
        when(channels.orderedStream()).thenAnswer(invocation -> java.util.stream.Stream.empty());

        return new ConfigUiController(
                mock(ConfigurationMetadataService.class),
                mock(ConfigurationFileService.class),
                properties,
                dataSource,
                healthProbes,
                mock(ConfigurationValidator.class),
                senders,
                mock(NovaMessageSender.class),
                mock(ObjectProvider.class),
                mock(org.frostnova.nova.core.health.PushActivityRecorder.class),
                mock(org.frostnova.nova.core.service.NovaEventHandlerService.class),
                mock(org.frostnova.nova.core.datasource.DataSourceServiceRegistry.class),
                mock(ConfigurationLevelResolver.class),
                new ConfigurationLabelResolver(mock(org.springframework.context.ApplicationContext.class)),
                new ConfigurationEffectResolver(mock(org.springframework.context.ApplicationContext.class)),
                new ConfigurationDangerResolver(mock(org.springframework.context.ApplicationContext.class)),
                mock(RuntimeConfigurationApplier.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                new EventStreamTokenService(properties.getLive()),
                mock(ObjectProvider.class),
                new PushGate(properties),
                liveDataService,
                timeline,
                mock(ConfigUiAuthService.class),
                new PushTemplateDefaults(new NovaCoreProperties()),
                mock(UpdateCheckService.class),
                new AlertService(properties, channels, TimelineWriter.NONE));
    }

    private static PushUser user(long uid, String uname) {
        PushUser user = new PushUser();
        user.setUid(uid);
        user.setUname(uname);
        user.setRoomId(uid * 10);
        user.setPlatform("bilibili");
        user.setEnabled(true);
        return user;
    }

    /** 往场次归档里追加一场，只写取昵称要用的几项 */
    private void archive(long uid, String uname, long startTime) throws IOException {
        JSONObject line = new JSONObject();
        line.put("platform", "bilibili");
        line.put("uid", uid);
        line.put("uname", uname);
        line.put("roomId", uid * 10);
        line.put("startTime", startTime);
        line.put("endTime", startTime + 3_600_000L);
        line.put("durationSeconds", 3600);
        Files.writeString(dir.resolve("sessions.jsonl"), line.toJSONString() + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private JSONObject statusUser(JSONObject status) {
        JSONArray users = status.getJSONArray("users");
        assertEquals(1, users.size());
        return users.getJSONObject(0);
    }

    private JSONObject liveUser(JSONObject status) {
        JSONArray live = status.getJSONArray("live");
        assertEquals(1, live.size());
        return live.getJSONObject(0);
    }

    @Test
    @DisplayName("内存没昵称、有归档场次：两处都显示最近一场的昵称")
    void blankNameFallsBackToLatestSession() throws IOException {
        when(dataSource.getAllUsers()).thenReturn(List.of(user(1L, "")));
        archive(1L, "早先的昵称", 1_700_000_000_000L);
        archive(1L, "最近的昵称", 1_700_100_000_000L);
        archive(2L, "别人的昵称", 1_700_200_000_000L);

        JSONObject status = controller().status();

        assertEquals("最近的昵称", statusUser(status).getString("uname"), "状态接口 users 里的主播名");
        assertEquals("最近的昵称", liveUser(status).getString("uname"), "首页在播里的主播名");
    }

    @Test
    @DisplayName("内存有昵称：两处照旧显示内存的，不被归档里的旧名盖掉")
    void loadedNameWinsOverArchive() throws IOException {
        when(dataSource.getAllUsers()).thenReturn(List.of(user(1L, "查回来的昵称")));
        archive(1L, "归档里的旧昵称", 1_700_100_000_000L);

        JSONObject status = controller().status();

        assertEquals("查回来的昵称", statusUser(status).getString("uname"));
        assertEquals("查回来的昵称", liveUser(status).getString("uname"));
    }

    @Test
    @DisplayName("内存没昵称、这位也没有归档场次：两处照旧是空名")
    void noNameAnywhereStaysBlank() throws IOException {
        when(dataSource.getAllUsers()).thenReturn(List.of(user(1L, "")));
        archive(2L, "别人的昵称", 1_700_200_000_000L);

        JSONObject status = controller().status();

        assertEquals("", statusUser(status).getString("uname"));
        assertEquals("", liveUser(status).getString("uname"));
    }

    @Test
    @DisplayName("归档里又下播了一场：退回的昵称跟着换成新的那一场")
    void newerSessionIsPickedUpAfterAppend() throws IOException {
        when(dataSource.getAllUsers()).thenReturn(List.of(user(1L, "")));
        archive(1L, "上一场的昵称", 1_700_000_000_000L);
        ConfigUiController controller = controller();
        assertEquals("上一场的昵称", liveUser(controller.status()).getString("uname"));

        archive(1L, "改名后的昵称", 1_700_100_000_000L);

        assertEquals("改名后的昵称", liveUser(controller.status()).getString("uname"),
                "新下播的一场写进归档之后，常轮询的首页要看得到");
    }
}
