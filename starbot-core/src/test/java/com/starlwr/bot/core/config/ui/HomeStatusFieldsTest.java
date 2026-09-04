package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.config.ui.auth.ConfigUiAuthService;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.health.HealthProbe;
import com.starlwr.bot.core.health.HealthStatus;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.sender.PushGate;
import com.starlwr.bot.core.service.EventStreamTokenService;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.StarBotSenderService;
import com.starlwr.bot.core.timeline.TimelineEvent;
import com.starlwr.bot.core.timeline.TimelineEventType;
import com.starlwr.bot.core.timeline.TimelineStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 首页要用的那几项运行状态
 * <p>
 * 首页的判断全在 {@code config-ui/home-model.js} 里，而它读的是本接口下发的字段。
 * 那一侧的八档对照喂的是<b>手写的回包</b>（tools/home-model-check.sh），
 * 手写的那份与真实回包之间没有任何东西钉着——键名少一个字母，模型那边照样八档全绿，
 * 而屏幕上是一片空白。本组用例钉的就是这一头：<b>这些键确实出自服务端，且形状如此</b>。
 * <p>
 * 至于两头的键名是不是同一批，只有真起一次产物、真开一次页面才答得了；
 * 那一步（走面）另有安排，本组用例不假装自己答了。
 */
@DisplayName("首页运行状态字段")
class HomeStatusFieldsTest {
    @TempDir
    Path dir;

    private StarBotCoreProperties properties;

    private AbstractDataSource dataSource;

    private LiveDataService liveDataService;

    private TimelineStore timeline;

    private ConfigUiAuthService authService;

    /** 探针清单由本字段供给，各用例按需改 */
    private List<HealthProbe> probes = List.of();

    @BeforeEach
    void setUp() {
        properties = new StarBotCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());

        dataSource = mock(AbstractDataSource.class);
        when(dataSource.getAllUsers()).thenReturn(List.of());

        liveDataService = mock(LiveDataService.class);
        when(liveDataService.supportsTotalData()).thenReturn(true);
        when(liveDataService.getLiveStatus(any(), any())).thenReturn(Optional.of(false));
        when(liveDataService.getLiveStartTime(any(), any())).thenReturn(Optional.empty());

        timeline = mock(TimelineStore.class);
        when(timeline.countsOn(any())).thenReturn(Map.of());

        authService = mock(ConfigUiAuthService.class);
        when(authService.isEnabled()).thenReturn(true);
    }

    @SuppressWarnings("unchecked")
    private ConfigUiController controller() {
        ObjectProvider<HealthProbe> healthProbes = mock(ObjectProvider.class);
        when(healthProbes.orderedStream()).thenAnswer(invocation -> probes.stream());

        StarBotSenderService senders = mock(StarBotSenderService.class);
        when(senders.getSenderNames()).thenReturn(Set.of("默认"));

        return new ConfigUiController(
                mock(ConfigurationMetadataService.class),
                mock(ConfigurationFileService.class),
                properties,
                dataSource,
                healthProbes,
                mock(ConfigurationValidator.class),
                senders,
                mock(com.starlwr.bot.core.sender.StarBotMessageSender.class),
                mock(ObjectProvider.class),
                mock(com.starlwr.bot.core.health.PushActivityRecorder.class),
                mock(com.starlwr.bot.core.service.StarBotEventHandlerService.class),
                mock(com.starlwr.bot.core.datasource.DataSourceServiceRegistry.class),
                mock(ConfigurationLevelResolver.class),
                // 不 mock 这个具体类：内联 mock 要改写它的字节码，clean 构建下实测会抛「could not instrument」
                new ConfigurationEffectResolver(mock(org.springframework.context.ApplicationContext.class)),
                mock(RuntimeConfigurationApplier.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                new EventStreamTokenService(properties.getLive()),
                mock(ObjectProvider.class),
                new PushGate(properties),
                liveDataService,
                timeline,
                authService);
    }

    /** 一个只声明范围与登录态位的探针，够本组用例用 */
    private HealthProbe probe(String name, HealthProbe.Scope scope, boolean loginState) {
        return new HealthProbe() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public HealthStatus check() {
                return HealthStatus.ok("正常");
            }

            @Override
            public HealthProbe.Scope scope() {
                return scope;
            }

            @Override
            public boolean loginState() {
                return loginState;
            }
        };
    }

    private PushUser user(long uid, String platform, boolean enabled) {
        PushUser pushUser = new PushUser();
        pushUser.setUid(uid);
        pushUser.setUname("主播" + uid);
        pushUser.setRoomId(uid * 10);
        pushUser.setPlatform(platform);
        pushUser.setEnabled(enabled);
        return pushUser;
    }

    @Test
    @DisplayName("静音时段：在不在与区间一并下发")
    void quietHoursCarryBothStateAndRange() {
        properties.getPush().setQuietStart("00:00");
        properties.getPush().setQuietEnd("23:59");

        JSONObject quiet = controller().status().getJSONObject("quiet");

        assertNotNull(quiet, "首页顶部横条要写「静音中 hh:mm – hh:mm」，缺了这一项就无从写起");
        assertTrue(quiet.getBooleanValue("active"), "区间几乎覆盖整天，此刻应当判为静音中");
        assertEquals("00:00", quiet.getString("start"));
        assertEquals("23:59", quiet.getString("end"));
    }

    @Test
    @DisplayName("没设静音时段时不是静音中，区间照样给出（空串）")
    void quietHoursAbsentIsNotActive() {
        JSONObject quiet = controller().status().getJSONObject("quiet");

        assertFalse(quiet.getBooleanValue("active"), "起止都没填即未启用，不该判成静音中");
        assertEquals("", quiet.getString("start"), "键要在，值为空串——整项缺席与「没设」在读的那一端分不出来");
    }

    @Test
    @DisplayName("在播的主播只列监听清单里的，停用的不算")
    void liveListsOnlyEnabledMonitoredStreamers() {
        when(dataSource.getAllUsers()).thenReturn(List.of(
                user(1L, "bilibili", true), user(2L, "bilibili", true), user(3L, "bilibili", false)));
        // 1 号在播，2 号没在播，3 号在播但已停用
        when(liveDataService.getLiveStatus("bilibili", 1L)).thenReturn(Optional.of(true));
        when(liveDataService.getLiveStatus("bilibili", 2L)).thenReturn(Optional.of(false));
        when(liveDataService.getLiveStatus("bilibili", 3L)).thenReturn(Optional.of(true));
        when(liveDataService.getLiveStartTime("bilibili", 1L)).thenReturn(Optional.of(1757000000000L));

        JSONArray live = controller().status().getJSONArray("live");

        assertEquals(1, live.size(), "只有 1 号既在监听清单里、又启用着、又在播");
        JSONObject first = live.getJSONObject(0);
        assertEquals(1L, first.getLongValue("uid"));
        assertEquals("主播1", first.getString("uname"));
        assertEquals(10L, first.getLongValue("roomId"));
        assertEquals(1757000000000L, first.getLongValue("since"));
    }

    @Test
    @DisplayName("开播时刻没记上时给 null，不编一个出来")
    void liveWithoutStartTimeReportsNull() {
        when(dataSource.getAllUsers()).thenReturn(List.of(user(1L, "bilibili", true)));
        when(liveDataService.getLiveStatus("bilibili", 1L)).thenReturn(Optional.of(true));
        when(liveDataService.getLiveStartTime("bilibili", 1L)).thenReturn(Optional.empty());

        JSONObject first = controller().status().getJSONArray("live").getJSONObject(0);

        assertTrue(first.containsKey("since"), "键要在");
        assertNull(first.get("since"), "编一个开始时刻出来，界面上那个已播时长会一直是错的");
    }

    @Test
    @DisplayName("今日推送条数按日历上的今天数，取自时间线")
    void todayCountsComeFromTimeline() {
        when(timeline.countsOn(any())).thenReturn(Map.of(
                TimelineEventType.PUSH_SENT, 14,
                TimelineEventType.PUSH_FAILED, 2,
                TimelineEventType.PUSH_MUTED, 5));

        JSONObject today = controller().status().getJSONObject("today");

        assertEquals(14, today.getIntValue("sent"));
        assertEquals(2, today.getIntValue("failed"));
    }

    @Test
    @DisplayName("今天什么都没发生时是 0，不是缺键")
    void todayCountsDefaultToZero() {
        JSONObject today = controller().status().getJSONObject("today");

        assertEquals(0, today.getIntValue("sent"), "缺键会被读成「取不到」，而 0 说的是「真的一条都没有」");
        assertEquals(0, today.getIntValue("failed"));
    }

    @Test
    @DisplayName("上没上锁与累计存储开没开各有一位")
    void lockedAndTotalDataAreReported() {
        when(authService.isEnabled()).thenReturn(false);
        when(liveDataService.supportsTotalData()).thenReturn(false);

        JSONObject status = controller().status();

        assertFalse(status.getBooleanValue("locked"), "没配口令即没上锁，首页要为此常驻一条待办");
        assertFalse(status.getBooleanValue("totalDataAvailable"));
    }

    @Test
    @DisplayName("探针带着自己是不是量登录态的那一位")
    void healthCarriesLoginStateFlag() {
        probes = List.of(
                probe("平台登录", HealthProbe.Scope.PLATFORM, true),
                probe("直播间连接", HealthProbe.Scope.PLATFORM, false));

        JSONArray health = controller().status().getJSONArray("health");

        assertEquals(2, health.size());
        assertTrue(health.getJSONObject(0).getBooleanValue("loginState"),
                "登录掉了要人去扫码，断流多半会自己恢复——首页按这一位把两者分开说");
        assertFalse(health.getJSONObject(1).getBooleanValue("loginState"));
    }

    /**
     * 时间线那一头：真写几条进去，再让它自己数
     * <p>
     * 上面几条用的是桩，答的是「控制器把数字放对了位置」；这一条不打桩，
     * 答的是「数出来的数对不对」——两者都要，缺前者时字段错位看不出来，
     * 缺后者时数错了也看不出来。
     */
    @Test
    @DisplayName("按日计数只数当天那一份，且分得清成功与失败")
    void countsOnCountsThatDayOnly() {
        TimelineStore store = new TimelineStore(properties);
        Instant now = Instant.now();
        Instant yesterday = now.minus(1, ChronoUnit.DAYS);

        store.record(TimelineEvent.of(TimelineEventType.PUSH_SENT, TimelineEvent.Level.INFO)
                .at(now).text("今天推成功").build());
        store.record(TimelineEvent.of(TimelineEventType.PUSH_SENT, TimelineEvent.Level.INFO)
                .at(now).text("今天又推成功").build());
        store.record(TimelineEvent.of(TimelineEventType.PUSH_FAILED, TimelineEvent.Level.ERROR)
                .at(now).text("今天推失败").build());
        store.record(TimelineEvent.of(TimelineEventType.PUSH_SENT, TimelineEvent.Level.INFO)
                .at(yesterday).text("昨天推成功").build());

        Map<TimelineEventType, Integer> counts = store.countsOn(LocalDate.now());

        assertEquals(2, counts.getOrDefault(TimelineEventType.PUSH_SENT, 0), "昨天那条不该算进今天");
        assertEquals(1, counts.getOrDefault(TimelineEventType.PUSH_FAILED, 0));
    }

    /**
     * 探针清单为空时也要能出一份状态
     * <p>
     * 一个插件都没装的机器上首页仍要打得开：那一档正是使用者第一次打开控制台看到的画面。
     */
    @Test
    @DisplayName("一个探针都没有时状态照出，不抛")
    void statusSurvivesWithoutProbes() {
        JSONObject status = controller().status();

        assertTrue(status.getBooleanValue("success"));
        assertEquals(0, status.getJSONArray("health").size());
        assertNotNull(status.getJSONObject("quiet"));
        assertNotNull(status.getJSONArray("live"));
        assertNotNull(status.getJSONObject("today"));
    }
}
