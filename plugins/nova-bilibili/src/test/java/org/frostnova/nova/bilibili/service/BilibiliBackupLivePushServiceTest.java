package org.frostnova.nova.bilibili.service;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.event.live.BilibiliLiveOffEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliLiveOnEvent;
import org.frostnova.nova.bilibili.model.Room;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.LiveSessionRecovery;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
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
 * 另一组格钉「停机空当里结束的那场」：起来后首轮见「账上在播、实际不在播」，
 * 停得短照正常下播发通知出报告图（下播时刻取上次落盘时刻），停得久当场按未闭合补档。
 * <p>
 * LiveDataService 用自洽 mock（真 Map 后备）而不是纯桩：闸门放行下播时
 * 回落读的正是这本账，「同步写账 → 闸门读到 → 放行下播」这条链要真跑通。
 * 闸门包一层 spy：下播那路必须真走到闸门，光看「发没发」分不出走没走到。
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
    private LiveSessionRecovery sessionRecovery;
    private BilibiliLiveStateGate stateGate;
    private Map<Long, Boolean> statusStore;
    private Map<Long, Long> startTimeStore;
    private Runnable poll;

    @BeforeEach
    void setUp() {
        api = mock(BilibiliApiUtil.class);
        dataSource = mock(AbstractDataSource.class);
        publisher = mock(ApplicationEventPublisher.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        sessionRecovery = mock(LiveSessionRecovery.class);

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

        stateGate = spy(new BilibiliLiveStateGate(liveDataService));
        BilibiliBackupLivePushService service = new BilibiliBackupLivePushService(
                api, new NovaBilibiliProperties(), publisher, scheduler,
                stateGate, liveDataService, sessionRecovery);
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

    @Test
    @DisplayName("停机空当里结束·短停: 照正常下播发一次事件, 下播时刻取上次落盘时刻")
    void shouldPushLiveOffWithLastSaveTimeWhenDownBriefly() {
        // 账上在播、首轮不在播、上次落盘在 1 分钟前——人是刚下播, 通知与报告图照出
        long lastSave = System.currentTimeMillis() - 60_000L;
        when(liveDataService.getLastSaveTime()).thenReturn(Optional.of(lastSave));
        statusStore.put(UID, true);
        startTimeStore.put(UID, 111111111000L);

        runRound(List.of(streamer()), Map.of(UID, offlineRoom()));

        ArgumentCaptor<BilibiliLiveOffEvent> captor = ArgumentCaptor.forClass(BilibiliLiveOffEvent.class);
        verify(publisher, times(1)).publishEvent(captor.capture());
        verify(publisher, never()).publishEvent(any(BilibiliLiveOnEvent.class));
        assertEquals(lastSave, captor.getValue().getTimestamp(),
                "下播时刻应取上次落盘时刻, 实为 " + captor.getValue().getTimestamp());
        verifyNoInteractions(sessionRecovery);
    }

    @Test
    @DisplayName("停机空当里结束·停得久: 不发下播事件, 当场按未闭合归档一场并改账")
    void shouldArchiveUnclosedWithoutPushWhenDownTooLong() {
        // 同上, 但上次落盘在 2 小时前——下播时刻无从得知, 不能按下播发通知
        long lastSave = System.currentTimeMillis() - 2 * 3_600_000L;
        when(liveDataService.getLastSaveTime()).thenReturn(Optional.of(lastSave));
        statusStore.put(UID, true);
        startTimeStore.put(UID, 111111111000L);

        long before = System.currentTimeMillis();
        runRound(List.of(streamer()), Map.of(UID, offlineRoom()));
        long after = System.currentTimeMillis();

        verifyNoInteractions(publisher);
        verify(sessionRecovery, times(1)).archiveUnclosedIfAny(
                eq(BilibiliPlatform.BILIBILI.id()),
                argThat(source -> source != null && source.getUid() != null && source.getUid() == UID),
                longThat(t -> t >= before && t <= after));
        assertEquals(Optional.of(false), liveDataService.getLiveStatus(BilibiliPlatform.BILIBILI.id(), UID),
                "账上应改成不在播, 否则控制台一直挂着在播、下次开播还会再补一遍");
    }

    @Test
    @DisplayName("停机空当里结束·短停但下播已被长连接占过: 闸门拦下, 不发")
    void shouldNotPushWhenGateAlreadyTaken() {
        long lastSave = System.currentTimeMillis() - 60_000L;
        when(liveDataService.getLastSaveTime()).thenReturn(Optional.of(lastSave));
        // 长连接发布前先过闸门: 这一下把本次下播的放行占走; 此刻监听器还没把账写成不在播
        assertTrue(stateGate.admit(UID, false), "占位那一下应放行并记下");
        clearInvocations(stateGate);
        statusStore.put(UID, true);

        runRound(List.of(streamer()), Map.of(UID, offlineRoom()));

        // 「不发」在改前也成立(首轮本来就跳过)。这一格真正钉的是「走到了闸门、被它拦下」——
        // 只断言不发的话, 改前改后同绿, 连走没走这道去重都分不出来
        verify(stateGate, times(1)).admit(UID, false);
        verifyNoInteractions(publisher);
        verifyNoInteractions(sessionRecovery);
    }

    @Test
    @DisplayName("停机空当里结束·取不到上次落盘时刻: 走未闭合补档那路")
    void shouldArchiveUnclosedWhenLastSaveTimeMissing() {
        when(liveDataService.getLastSaveTime()).thenReturn(Optional.empty());
        statusStore.put(UID, true);
        startTimeStore.put(UID, 111111111000L);

        long before = System.currentTimeMillis();
        runRound(List.of(streamer()), Map.of(UID, offlineRoom()));
        long after = System.currentTimeMillis();

        verifyNoInteractions(publisher);
        verify(sessionRecovery, times(1)).archiveUnclosedIfAny(
                eq(BilibiliPlatform.BILIBILI.id()),
                argThat(source -> source != null && source.getUid() != null && source.getUid() == UID),
                longThat(t -> t >= before && t <= after));
        assertEquals(Optional.of(false), liveDataService.getLiveStatus(BilibiliPlatform.BILIBILI.id(), UID),
                "账上应改成不在播");
    }

    @Test
    @DisplayName("运行中加回·账上在播实际已下播: 不发过时的下播通知, 当场按未闭合补档并改账")
    void shouldArchiveWithoutPushWhenReaddedWhileRunning() {
        // 上一进程里在播时被移出配置, 账上一直记着在播; 本进程首轮数据源里没有他, 第二轮才加回,
        // 那时他早已下播——照「停机空当」给他发下播, 就是一条过时的通知外加一张报告图
        long lastSave = System.currentTimeMillis() - 60_000L;
        when(liveDataService.getLastSaveTime()).thenReturn(Optional.of(lastSave));
        statusStore.put(UID, true);
        startTimeStore.put(UID, 111111111000L);

        runRound(List.of(), Map.of());
        long before = System.currentTimeMillis();
        runRound(List.of(streamer()), Map.of(UID, offlineRoom()));
        long after = System.currentTimeMillis();

        verifyNoInteractions(publisher);
        verify(sessionRecovery, times(1)).archiveUnclosedIfAny(
                eq(BilibiliPlatform.BILIBILI.id()),
                argThat(source -> source != null && source.getUid() != null && source.getUid() == UID),
                longThat(t -> t >= before && t <= after));
        assertEquals(Optional.of(false), liveDataService.getLiveStatus(BilibiliPlatform.BILIBILI.id(), UID),
                "账上应改成不在播, 否则控制台一直挂着在播、下次开播还会再补一遍");
    }

    @Test
    @DisplayName("起来时就配着·首轮接口没回他: 第二轮照短停发下播, 时刻取上次落盘")
    void shouldPushLiveOffWhenFirstRoundQueryMissedStartupStreamer() {
        // 首轮数据源里有他、接口那一轮没回这个 uid——他是起来时就配着的, 不得按运行中加回丢掉
        // 「刚下播」的通知与报告图; 也不能拿 initialized 判: 首轮跑完它就真了
        long lastSave = System.currentTimeMillis() - 60_000L;
        when(liveDataService.getLastSaveTime()).thenReturn(Optional.of(lastSave));
        statusStore.put(UID, true);
        startTimeStore.put(UID, 111111111000L);

        runRound(List.of(streamer()), Map.of());
        runRound(List.of(streamer()), Map.of(UID, offlineRoom()));

        ArgumentCaptor<BilibiliLiveOffEvent> captor = ArgumentCaptor.forClass(BilibiliLiveOffEvent.class);
        verify(publisher, times(1)).publishEvent(captor.capture());
        verify(publisher, never()).publishEvent(any(BilibiliLiveOnEvent.class));
        assertEquals(lastSave, captor.getValue().getTimestamp(),
                "下播时刻应取上次落盘时刻, 实为 " + captor.getValue().getTimestamp());
        verifyNoInteractions(sessionRecovery);
    }

    @Test
    @DisplayName("起来时就配着·房间号晚到: 第二轮照短停发下播, 时刻取上次落盘, 不补档")
    void shouldPushLiveOffWhenRoomIdArrivesLate() {
        // 资料缓存缺了这位、后台补全又没赶上首轮: 第一轮数据源里有他但房间号还空着,
        // 第二轮房间号才到。他是起来时就配着的——名单只按 uid 记才不会把他当成运行中
        // 加回, 停得短那半的「刚下播」通知与报告图才不会丢
        long lastSave = System.currentTimeMillis() - 60_000L;
        when(liveDataService.getLastSaveTime()).thenReturn(Optional.of(lastSave));
        statusStore.put(UID, true);
        startTimeStore.put(UID, 111111111000L);

        PushUser roomIdPending = streamer();
        roomIdPending.setRoomId(null);
        runRound(List.of(roomIdPending), Map.of());
        runRound(List.of(streamer()), Map.of(UID, offlineRoom()));

        ArgumentCaptor<BilibiliLiveOffEvent> captor = ArgumentCaptor.forClass(BilibiliLiveOffEvent.class);
        verify(publisher, times(1)).publishEvent(captor.capture());
        verify(publisher, never()).publishEvent(any(BilibiliLiveOnEvent.class));
        assertEquals(lastSave, captor.getValue().getTimestamp(),
                "下播时刻应取上次落盘时刻, 实为 " + captor.getValue().getTimestamp());
        verifyNoInteractions(sessionRecovery);
    }

    /**
     * 备用直播推送关着时起来的那一轮：start() 当场只查一轮，之后不登记任何定时任务
     * @param ups 这一轮数据源里的主播
     * @param rooms 接口对这批 uid 的回答
     * @return 起服务时递给它的调度器——关着时应当一次都没碰
     */
    private TaskScheduler runDisabledStartup(List<PushUser> ups, Map<Long, Room> rooms) {
        when(dataSource.getUsers(BilibiliPlatform.BILIBILI.id())).thenReturn(ups);
        when(api.getLiveInfoByUids(anySet())).thenReturn(rooms);

        NovaBilibiliProperties properties = new NovaBilibiliProperties();
        properties.getLive().setBackupLivePush(false);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        new BilibiliBackupLivePushService(api, properties, publisher, scheduler,
                stateGate, liveDataService, sessionRecovery).start(dataSource);
        return scheduler;
    }

    @Test
    @DisplayName("备用轮询关着·停机空当里结束·短停: 照正常下播发一次事件, 时刻取上次落盘")
    void shouldPushLiveOffWhenDisabledAndDownBriefly() {
        // 备用轮询关着, 停机空当里下了播: 账上一直挂着在播, 控制台显示在播, 下播通知与
        // 报告图全没有。起来只查的这一轮要照正常下播收掉他
        long lastSave = System.currentTimeMillis() - 60_000L;
        when(liveDataService.getLastSaveTime()).thenReturn(Optional.of(lastSave));
        statusStore.put(UID, true);
        startTimeStore.put(UID, 111111111000L);

        runDisabledStartup(List.of(streamer()), Map.of(UID, offlineRoom()));

        ArgumentCaptor<BilibiliLiveOffEvent> captor = ArgumentCaptor.forClass(BilibiliLiveOffEvent.class);
        verify(publisher, times(1)).publishEvent(captor.capture());
        verify(publisher, never()).publishEvent(any(BilibiliLiveOnEvent.class));
        assertEquals(lastSave, captor.getValue().getTimestamp(),
                "下播时刻应取上次落盘时刻, 实为 " + captor.getValue().getTimestamp());
        verifyNoInteractions(sessionRecovery);
    }

    @Test
    @DisplayName("备用轮询关着·停机空当里结束·停得久: 不发下播事件, 当场按未闭合归档一场并改账")
    void shouldArchiveUnclosedWhenDisabledAndDownTooLong() {
        // 同上但停了一夜: 「刚下播」说不通, 发下播通知是误报; 当场按未闭合补档, 账要翻成不在播
        long lastSave = System.currentTimeMillis() - 2 * 3_600_000L;
        when(liveDataService.getLastSaveTime()).thenReturn(Optional.of(lastSave));
        statusStore.put(UID, true);
        startTimeStore.put(UID, 111111111000L);

        long before = System.currentTimeMillis();
        runDisabledStartup(List.of(streamer()), Map.of(UID, offlineRoom()));
        long after = System.currentTimeMillis();

        verifyNoInteractions(publisher);
        verify(sessionRecovery, times(1)).archiveUnclosedIfAny(
                eq(BilibiliPlatform.BILIBILI.id()),
                argThat(source -> source != null && source.getUid() != null && source.getUid() == UID),
                longThat(t -> t >= before && t <= after));
        assertEquals(Optional.of(false), liveDataService.getLiveStatus(BilibiliPlatform.BILIBILI.id(), UID),
                "账上应改成不在播, 否则控制台一直挂着在播、下次开播还会再补一遍");
    }

    @Test
    @DisplayName("备用轮询关着·账上在播实际也在播: 什么都不发, 账不动")
    void shouldTouchNothingWhenDisabledAndStillLiving() {
        // 起来只查的这一轮见他还在播, 可这一轮不是来管在播的人的: 补推开播会把正在播的当成
        // 新开播报一遍, 动账会把正在播的这一场搅了。前提「这一轮真的看了他」不能省——
        // 省了的话「什么都不发」在「压根没查」上同样成立, 两件事分不出来
        statusStore.put(UID, true);
        startTimeStore.put(UID, 111111111000L);

        runDisabledStartup(List.of(streamer()), Map.of(UID, livingRoom(LIVE_START_SECONDS)));

        verify(api, times(1)).getLiveInfoByUids(anySet());
        verifyNoInteractions(publisher);
        verifyNoInteractions(sessionRecovery);
        assertEquals(Optional.of(true), liveDataService.getLiveStatus(BilibiliPlatform.BILIBILI.id(), UID),
                "正在播的场不该被翻成不在播");
        assertEquals(Optional.of(111111111000L), liveDataService.getLiveStartTime(BilibiliPlatform.BILIBILI.id(), UID),
                "正在播的本场起始不该被动");
        verify(liveDataService, never()).setLiveStatus(anyString(), anyLong(), anyBoolean());
        verify(liveDataService, never()).setLiveStartTime(anyString(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("备用轮询关着: 起来只查一轮, 不登记定时轮询")
    void shouldQueryOnlyOnceWithoutSchedulingWhenDisabled() {
        // 关着还挂定时器就是没关; 查完这一轮还接着查就是没做到「只查一轮」
        TaskScheduler scheduler = runDisabledStartup(List.of(streamer()), Map.of(UID, offlineRoom()));

        verifyNoInteractions(scheduler);
        verify(dataSource, times(1)).getUsers(BilibiliPlatform.BILIBILI.id());
        verify(api, times(1)).getLiveInfoByUids(anySet());
    }
}
