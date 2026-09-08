package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.bilibili.BilibiliPlatform;
import com.starlwr.bot.bilibili.config.NovaBilibiliProperties;
import com.starlwr.bot.bilibili.event.live.BilibiliLiveOffEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliLiveOnEvent;
import com.starlwr.bot.bilibili.model.Room;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.service.LiveDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 备用直播推送「加入监听时已在播」测试
 * <p>
 * 运行期新增的主播若开播在先，长连接进房收不到那条早已发生的开播消息，
 * 轮询这边又对「没观测过的 uid」一律不置状态——这场直播会全程被当成没在播
 * （直播报告、实时数据、控制台在播态都读这本账）。用例钉住同步记账的行为与边界。
 * <p>
 * LiveDataService 用自洽 mock（真 Map 后备）而不是纯桩：闸门放行下播时
 * 回落读的正是这本账，「同步写账 → 闸门读到 → 放行下播」这条链要真跑通。
 */
@DisplayName("备用直播推送·加入监听时已在播")
class BilibiliBackupLivePushServiceTest {
    private static final long UID = 19142561034510L;
    private static final long ROOM_ID = 777001L;
    private static final long LIVE_START_SECONDS = 1757000000L;

    private BilibiliApiUtil api;
    private AbstractDataSource dataSource;
    private ApplicationEventPublisher publisher;
    private LiveDataService liveDataService;
    private Map<Long, Boolean> statusStore;
    private Map<Long, Long> startTimeStore;
    private Runnable poll;

    @BeforeEach
    void setUp() {
        api = mock(BilibiliApiUtil.class);
        dataSource = mock(AbstractDataSource.class);
        publisher = mock(ApplicationEventPublisher.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);

        statusStore = new HashMap<>();
        startTimeStore = new HashMap<>();
        liveDataService = mock(LiveDataService.class);
        when(liveDataService.getLiveStatus(anyString(), anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(statusStore.get(inv.getArgument(1))));
        doAnswer(inv -> {
            statusStore.put(inv.getArgument(1), inv.getArgument(2));
            return null;
        }).when(liveDataService).setLiveStatus(anyString(), anyLong(), anyBoolean());
        when(liveDataService.getLiveStartTime(anyString(), anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(startTimeStore.get(inv.getArgument(1))));
        doAnswer(inv -> {
            startTimeStore.put(inv.getArgument(1), inv.getArgument(2));
            return null;
        }).when(liveDataService).setLiveStartTime(anyString(), anyLong(), anyLong());

        BilibiliBackupLivePushService service = new BilibiliBackupLivePushService(
                api, new NovaBilibiliProperties(), publisher, scheduler,
                new BilibiliLiveStateGate(liveDataService), liveDataService);
        service.start(dataSource);

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(captor.capture(), any(Duration.class));
        poll = captor.getValue();
    }

    /**
     * 跑一轮轮询。ups 为这一轮数据源里的主播，rooms 为接口对这批 uid 的回答。
     */
    private void runRound(List<PushUser> ups, Map<Long, Room> rooms) {
        when(dataSource.getUsers(BilibiliPlatform.BILIBILI.id())).thenReturn(ups);
        when(api.getLiveInfoByUids(anySet())).thenReturn(rooms);
        poll.run();
    }

    private PushUser streamer() {
        PushUser user = new PushUser();
        user.setUid(UID);
        user.setUname("保留段主播");
        user.setRoomId(ROOM_ID);
        user.setPlatform(BilibiliPlatform.BILIBILI.id());
        user.setEnabled(true);
        return user;
    }

    private Room livingRoom(Long startSeconds) {
        Room room = new Room();
        room.setLiveStatus(1);
        room.setLiveStartTime(startSeconds);
        return room;
    }

    private Room offlineRoom() {
        Room room = new Room();
        room.setLiveStatus(0);
        return room;
    }

    @Test
    @DisplayName("运行期新增的主播已在播: 同步成在播, 不补推开播")
    void shouldAdoptAlreadyLivingStreamerWithoutPush() {
        runRound(List.of(), Map.of());
        runRound(List.of(streamer()), Map.of(UID, livingRoom(LIVE_START_SECONDS)));

        assertEquals(Optional.of(true), liveDataService.getLiveStatus(BilibiliPlatform.BILIBILI.id(), UID),
                "在播状态应已记账, 否则直播报告与实时数据全程答「没在播」");
        assertEquals(Optional.of(LIVE_START_SECONDS * 1000), liveDataService.getLiveStartTime(BilibiliPlatform.BILIBILI.id(), UID),
                "无本场数据时应按接口给的开播时间起一场");
        // 人不是刚开播, 补推开播是误报——一次事件都不许有
        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("先未播后开播: 仍按开播推送一条")
    void shouldStillPushOnRealLiveOn() {
        runRound(List.of(streamer()), Map.of(UID, offlineRoom()));
        runRound(List.of(streamer()), Map.of(UID, livingRoom(LIVE_START_SECONDS)));

        ArgumentCaptor<BilibiliLiveOnEvent> captor = ArgumentCaptor.forClass(BilibiliLiveOnEvent.class);
        verify(publisher, times(1)).publishEvent(captor.capture());
        assertEquals(LIVE_START_SECONDS * 1000, captor.getValue().getTimestamp(),
                "开播事件应带上接口报的开播时间");
    }

    @Test
    @DisplayName("已在播的主播加入监听后下播: 下播报告照出")
    void shouldStillReportLiveOffAfterAdopting() {
        runRound(List.of(), Map.of());
        runRound(List.of(streamer()), Map.of(UID, livingRoom(LIVE_START_SECONDS)));
        runRound(List.of(streamer()), Map.of(UID, offlineRoom()));

        verify(publisher, times(1)).publishEvent(any(BilibiliLiveOffEvent.class));
        verify(publisher, never()).publishEvent(any(BilibiliLiveOnEvent.class));
    }

    @Test
    @DisplayName("进程首轮就发现在播的主播同样按在播记账")
    void shouldAdoptOnFirstRound() {
        runRound(List.of(streamer()), Map.of(UID, livingRoom(LIVE_START_SECONDS)));

        assertEquals(Optional.of(true), liveDataService.getLiveStatus(BilibiliPlatform.BILIBILI.id(), UID),
                "首轮只免推送, 不免记账");
        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("账上已在播的主播(如进程重启后)不重记, 已有本场起始不被覆盖")
    void shouldNotAdoptWhenAlreadyLivingOnRecord() {
        statusStore.put(UID, true);
        startTimeStore.put(UID, 111111111000L);

        runRound(List.of(streamer()), Map.of(UID, livingRoom(LIVE_START_SECONDS)));

        assertEquals(Optional.of(111111111000L), liveDataService.getLiveStartTime(BilibiliPlatform.BILIBILI.id(), UID),
                "已有本场数据时不得改本场起始");
        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("账上不在播但已有本场起始时: 只补状态, 不改本场起始")
    void shouldKeepExistingStartTimeWhenStatusMissing() {
        startTimeStore.put(UID, 111111111000L);

        runRound(List.of(), Map.of());
        runRound(List.of(streamer()), Map.of(UID, livingRoom(LIVE_START_SECONDS)));

        assertEquals(Optional.of(true), liveDataService.getLiveStatus(BilibiliPlatform.BILIBILI.id(), UID),
                "状态缺了要补成在播");
        assertEquals(Optional.of(111111111000L), liveDataService.getLiveStartTime(BilibiliPlatform.BILIBILI.id(), UID),
                "已有本场起始不得被接口报的时间覆盖");
        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("新增时尚未开播的主播不写账不推送")
    void shouldNotTouchAccountForOfflineNewcomer() {
        runRound(List.of(streamer()), Map.of(UID, offlineRoom()));

        assertTrue(liveDataService.getLiveStartTime(BilibiliPlatform.BILIBILI.id(), UID).isEmpty(),
                "未开播不该起一场");
        assertTrue(statusStore.isEmpty(), "未开播不该被写成在播");
        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("接口没报开播时间时按当前时刻起一场")
    void shouldStartFromNowWhenApiOmitsStartTime() {
        runRound(List.of(), Map.of());
        long before = System.currentTimeMillis();
        runRound(List.of(streamer()), Map.of(UID, livingRoom(null)));
        long after = System.currentTimeMillis();

        Optional<Long> startTime = liveDataService.getLiveStartTime(BilibiliPlatform.BILIBILI.id(), UID);
        assertTrue(startTime.isPresent(), "接口没给开播时间也要起一场, 否则本场没有起始");
        startTime.ifPresent(value -> assertTrue(value >= before && value <= after,
                "应落在轮询前后之间, 实为 " + value));
    }
}
