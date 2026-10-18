package org.frostnova.nova.bilibili.service;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.health.BilibiliRiskMetrics;
import org.frostnova.nova.bilibili.model.ConnectInfo;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 直播间连接管理服务测试
 * <p>
 * sync 自热重载接入后会被反复调用，这里以桩替身覆盖重复调用下的幂等性。
 * 真实的连接建立需要网络与登录态，属真机验证范畴，不在本测试内。
 */
@DisplayName("直播间连接管理服务")
class BilibiliLiveRoomServiceTest {
    @Test
    @DisplayName("反复同步不应重复注册风控检测周期任务")
    void repeatedSyncShouldScheduleRiskDetectionOnce() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        BilibiliLiveRoomService service = service(scheduler);

        AbstractDataSource dataSource = emptyDataSource();
        service.sync(dataSource);
        service.sync(dataSource);
        service.sync(dataSource);

        verify(scheduler, times(1)).scheduleAtFixedRate(any(Runnable.class), any(Duration.class));
    }

    @Test
    @DisplayName("关闭长连接时同步应直接返回且不注册风控检测")
    void syncShouldNoOpWhenDisabled() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        NovaBilibiliProperties properties = new NovaBilibiliProperties();
        properties.getLive().setEnableConnectLiveRoom(false);
        BilibiliLiveRoomService service = service(scheduler, properties);

        service.sync(emptyDataSource());

        verify(scheduler, times(0)).scheduleAtFixedRate(any(Runnable.class), any(Duration.class));
    }

    @Test
    @DisplayName("推送配置里的直播间超过上限时只连接靠前的 10 间, 被截掉的点名写进日志")
    void syncShouldConnectAtMostTenRooms() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(BilibiliLiveRoomService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            TaskScheduler scheduler = mock(TaskScheduler.class);
            BilibiliLiveRoomService service = service(scheduler);

            AbstractDataSource dataSource = mock(AbstractDataSource.class);
            when(dataSource.getUsers(anyString())).thenReturn(users(1, 11));

            service.sync(dataSource);

            // 上限写死 10 而不是引用常量：这里要钉住的正是「上限是 10」这条产品结论
            List<Runnable> queued = queuedConnects(scheduler);
            assertEquals(10, queued.size(), "超出上限的直播间不该被排进建连队列");

            queued.forEach(Runnable::run);

            assertEquals(10, service.getManagedRoomCount(), "纳入连接管理的直播间数不得超过上限");
            assertTrue(service.getStatus(1011L).isEmpty(), "排在第 11 位的直播间不应被接管");

            // 「配了却没连上」若不在日志里记录，只会表现为某个直播间从此没有任何事件，
            // 而界面上一切正常——这种缺口是查不出来的
            String warn = warnContaining(appender, "上限");
            assertNotNull(warn, "被截掉的直播间必须点名，实际日志: " + appender.list);
            assertTrue(warn.contains("10 位"), "日志要说清上限是多少: " + warn);
            assertTrue(warn.contains("房间号: 1011"), "日志要带上被截掉的房间号: " + warn);
            assertTrue(warn.contains("UID: 11"), "日志要带上被截掉的 uid: " + warn);
        } finally {
            logger.detachAppender(appender);
        }
    }

    /**
     * 抓的故障：热重载整批换了主播，上一批还压在闸门里的建连任务先放行，把名额占满，
     * 新名单上的十位一位都连不上，要等下一次重载。
     */
    @Test
    @DisplayName("热重载整批换人时以新名单为准, 已在管理中的直播间重连不受影响")
    void connectShouldFollowLatestTargetsWhenWholeBatchReplaced() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        BilibiliLiveRoomService service = service(scheduler);

        AbstractDataSource first = mock(AbstractDataSource.class);
        when(first.getUsers(anyString())).thenReturn(users(1, 10));
        service.sync(first);

        AbstractDataSource second = mock(AbstractDataSource.class);
        when(second.getUsers(anyString())).thenReturn(users(11, 10));
        service.sync(second);

        List<Runnable> queued = queuedConnects(scheduler);
        assertEquals(20, queued.size(), "两次同步各排十间，单次都未超限，都应排进队列");

        queued.forEach(Runnable::run);

        assertEquals(10, service.getManagedRoomCount());
        assertTrue(service.getStatus(1001L).isEmpty(), "已从名单里换掉的直播间不应被接管");
        assertTrue(service.getStatus(1011L).isPresent(), "新名单上的直播间应被接管");

        // 已在管理中的房间放行，满员时它再走一遍建连不该被上限挡下
        queued.get(10).run();

        assertTrue(service.getStatus(1011L).isPresent(), "已在管理中的直播间不该被上限挡下");
        assertEquals(10, service.getManagedRoomCount(), "不应改变管理中的直播间数");
    }

    /**
     * 抓的故障：启动或改配置后几秒内又删掉一位主播，他的首连还在闸门里排着，
     * 放行后照样连上、继续采集，直到下一次同步才断开。
     */
    @Test
    @DisplayName("⚠️ 首连还在闸门里排队时这位被删掉, 放行后不建连")
    void queuedConnectOfRemovedStreamerIsDropped() throws Exception {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        List<Long> connectOrder = new ArrayList<>();
        BilibiliLiveRoomService service = service(scheduler, new NovaBilibiliProperties(),
                recordingApi(connectOrder), nobodyLiveOnAccount());

        AbstractDataSource first = mock(AbstractDataSource.class);
        when(first.getUsers(anyString())).thenReturn(users(1, 2));
        service.sync(first);

        AbstractDataSource later = mock(AbstractDataSource.class);
        when(later.getUsers(anyString())).thenReturn(users(1, 1));
        service.sync(later);

        runQueued(scheduler);

        assertTrue(service.getStatus(1002L).isEmpty(), "已删掉的主播不应被接管");
        assertFalse(connectOrder.contains(1002L), "已删掉的主播不应去建连，实际建连 " + connectOrder);
        assertTrue(service.getStatus(1001L).isPresent(), "留下的主播照常接管");
        assertTrue(startupWaveRooms(service).isEmpty(),
                "放弃的那位不该留着启动那一波的记号，实际 " + startupWaveRooms(service));
    }

    /**
     * 抓的故障：名额刚好满时删一位、加一位，已删那位还在排队的首连先放行占了名额，
     * 新加的那位被报「已达上限」，一直连不上。
     */
    @Test
    @DisplayName("⚠️ 名额刚好满时删一位加一位, 新加的那位能连上")
    void swapOneWhenFullConnectsTheNewcomer() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        BilibiliLiveRoomService service = service(scheduler);

        AbstractDataSource first = mock(AbstractDataSource.class);
        when(first.getUsers(anyString())).thenReturn(users(1, 10));
        service.sync(first);

        // 删掉 uid 1，加上 uid 11
        AbstractDataSource later = mock(AbstractDataSource.class);
        when(later.getUsers(anyString())).thenReturn(users(2, 10));
        service.sync(later);

        runQueued(scheduler);

        assertTrue(service.getStatus(1011L).isPresent(), "新加的那位应被接管，不该被已删那位的旧任务占掉名额");
        assertTrue(service.getStatus(1001L).isEmpty(), "已删的那位不应被接管");
        assertEquals(10, service.getManagedRoomCount());
    }

    @Test
    @SuppressWarnings("unchecked")
    void managedConnectorRecoversAfterReauthentication() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        BilibiliGapRecovery recovery = mock(BilibiliGapRecovery.class);
        BilibiliLiveRoomService service = service(scheduler);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "gapRecovery", recovery);
        AbstractDataSource source = mock(AbstractDataSource.class);
        when(source.getUsers(anyString())).thenReturn(users(1, 1));
        service.sync(source);
        runQueued(scheduler);
        Map<Long, BilibiliLiveRoomConnector> connectors = (Map<Long, BilibiliLiveRoomConnector>)
                org.springframework.test.util.ReflectionTestUtils.getField(service, "connectors");
        BilibiliLiveRoomConnector connector = connectors.get(1001L);
        assertNotNull(connector);
        Instant from = Instant.now().minusSeconds(5);
        org.springframework.test.util.ReflectionTestUtils.setField(connector, "authenticatedOnce", true);
        org.springframework.test.util.ReflectionTestUtils.setField(connector, "disconnectedAt", from);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(connector, "handlePacket",
                new org.frostnova.nova.bilibili.protocol.BilibiliPacket(8, 1, new byte[0]));
        verify(recovery).recover(any(), org.mockito.ArgumentMatchers.eq(from), any());
    }

    @SuppressWarnings("unchecked")
    private static java.util.Set<Long> startupWaveRooms(BilibiliLiveRoomService service) throws Exception {
        Field field = BilibiliLiveRoomService.class.getDeclaredField("startupWaveRooms");
        field.setAccessible(true);
        return (java.util.Set<Long>) field.get(service);
    }

    @Test
    @DisplayName("正赶上有人直播时, 账上在播的排在最前建连, 其余保持配置顺序")
    void syncShouldConnectLiveOnAccountFirst() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        List<Long> connectOrder = new ArrayList<>();
        // 甲乙丙按配置顺序; 房号 1001/1002/1003 对应 uid 1/2/3, 只有丙(uid 3)账上在播
        BilibiliLiveRoomService service = service(scheduler, new NovaBilibiliProperties(),
                recordingApi(connectOrder), liveOnAccount(3L));

        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getUsers(anyString())).thenReturn(users(1, 3));

        service.sync(dataSource);
        runQueued(scheduler);

        // 升级重启那一下最要紧的就是在播的那间先连上: 排在后面连, 就多漏它一段弹幕、礼物与醒目留言
        assertEquals(List.of(1003L, 1001L, 1002L), connectOrder,
                "账上在播的丙要排最前建连, 其后的甲乙保持配置顺序");
    }

    @Test
    @DisplayName("没人账上在播时建连顺序与配置顺序一致")
    void syncShouldKeepConfigOrderWhenNobodyLive() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        List<Long> connectOrder = new ArrayList<>();
        BilibiliLiveRoomService service = service(scheduler, new NovaBilibiliProperties(),
                recordingApi(connectOrder), nobodyLiveOnAccount());

        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getUsers(anyString())).thenReturn(users(1, 3));

        service.sync(dataSource);
        runQueued(scheduler);

        assertEquals(List.of(1001L, 1002L, 1003L), connectOrder, "没人在播时应照配置顺序建连");
    }

    /**
     * 抓的故障：热重载新加的房也被当成启动那一波。
     * 那位主播第一次连上会被记成从重启到现在的重启缺口。
     * <p>
     * 算不算，看建连时交给连接器的那个标志。第一次同步排进闸门的房间号记在表里，
     * 建连时摘下来传给构造器；热重载再进来的不进这张表。
     * 建连任务跑完后，从管理表里读连接器收下的那个值。
     */
    @Test
    @DisplayName("⚠️ 第一次同步排进闸门的房算启动那一波，热重载新加的房不算")
    void hotReloadAddedRoomIsNotStartupWave() throws Exception {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        BilibiliLiveRoomService service = service(scheduler);

        AbstractDataSource first = mock(AbstractDataSource.class);
        when(first.getUsers(anyString())).thenReturn(users(1, 1));
        service.sync(first);
        runQueued(scheduler);

        AbstractDataSource later = mock(AbstractDataSource.class);
        when(later.getUsers(anyString())).thenReturn(users(1, 2));
        service.sync(later);
        runQueued(scheduler);

        assertTrue(startupWaveOf(service, 1001L),
                "第一次同步排进闸门的房，建出来的连接器应算启动那一波");
        assertFalse(startupWaveOf(service, 1002L),
                "热重载新加的房不算启动那一波，否则第一次连上会被记成从重启到现在的重启缺口");
    }

    /**
     * 抓的故障：重启时房间号要后台补全的在播主播，下播报告和控制台少算了
     * 从进程就绪到首连认证成功的停机缺口。这段应跟停机成因，不算本房断线。
     */
    @Test
    @DisplayName("启动时房间号为空，补全后首连仍算启动那一波")
    void startupRoomResolvedLaterIsStartupWave() throws Exception {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        BilibiliLiveRoomService service = service(scheduler, new NovaBilibiliProperties(),
                mock(BilibiliApiUtil.class), liveOnAccount(1L));
        PushUser user = users(1, 1).get(0);
        user.setRoomId(null);
        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getUsers(anyString())).thenReturn(List.of(user));

        service.sync(dataSource);
        service.sync(dataSource);
        assertTrue(queuedConnects(scheduler).isEmpty(), "房间号尚未补全时不应排队建连");

        user.setRoomId(1001L);
        service.sync(dataSource);
        runQueued(scheduler);

        assertTrue(startupWaveOf(service, 1001L),
                "启动时待补房间号的主播，补全后首连应按停机成因补上缺口");

        service.disconnect(1001L);
        service.sync(dataSource);
        runQueued(scheduler);
        assertFalse(startupWaveOf(service, 1001L), "启动记号用过即移出，重新建连接器不再补停机尾巴");
    }

    /**
     * 抓的故障：热重载新加的主播也要后台补房间号时，下播报告和控制台
     * 误把加入前的时间算成程序停机缺口；只有重启时已启用的主播才补这段尾巴。
     */
    @Test
    @DisplayName("热重载新加的主播房间号补全后首连不算启动那一波")
    void hotReloadRoomResolvedLaterIsNotStartupWave() throws Exception {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        BilibiliLiveRoomService service = service(scheduler);
        service.sync(emptyDataSource());

        PushUser user = users(1, 1).get(0);
        user.setRoomId(null);
        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getUsers(anyString())).thenReturn(List.of(user));
        service.sync(dataSource);
        assertTrue(queuedConnects(scheduler).isEmpty(), "房间号尚未补全时不应排队建连");

        user.setRoomId(1001L);
        service.sync(dataSource);
        runQueued(scheduler);

        assertFalse(startupWaveOf(service, 1001L),
                "热重载新加的主播即使房号要补全，也不应补从重启到首连的停机缺口");
    }

    /**
     * 抓的故障：启动时房间号为空的主播，使用者自己删掉又加回的那段时间，
     * 被算进下播报告的采集缺口和控制台的「因程序停机未采集」。
     */
    @Test
    @DisplayName("启动时房间号为空，删掉再加回后首连不算启动那一波")
    void removedPendingStartupRoomIsNotStartupWaveWhenReadded() throws Exception {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        BilibiliLiveRoomService service = service(scheduler);
        PushUser user = users(1, 1).get(0);
        user.setRoomId(null);
        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getUsers(anyString())).thenReturn(List.of(user));
        service.sync(dataSource);
        assertTrue(queuedConnects(scheduler).isEmpty(), "房间号尚未补全时不应排队建连");

        service.sync(emptyDataSource());

        user.setRoomId(1001L);
        service.sync(dataSource);
        runQueued(scheduler);

        assertFalse(startupWaveOf(service, 1001L),
                "使用者自己删掉又加回的时间，不应算成程序停机未采集");
    }

    /**
     * 抓的故障：启动时房间号为空的主播，拿到号时名额已满，后来腾出名额才连上，
     * 没连的那段时间被算进下播报告的采集缺口和控制台的「因程序停机未采集」。
     */
    @Test
    @DisplayName("启动时房间号为空，补全时名额已满，腾出名额后首连不算启动那一波")
    void resolvedPendingStartupRoomAtCapacityIsNotStartupWaveLater() throws Exception {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        BilibiliLiveRoomService service = service(scheduler);
        List<PushUser> users = users(1, 11);
        PushUser pending = users.get(10);
        pending.setRoomId(null);
        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getUsers(anyString())).thenReturn(users);
        service.sync(dataSource);
        runQueued(scheduler);
        assertEquals(10, service.getManagedRoomCount(), "其余十位应已占满名额");

        pending.setRoomId(1011L);
        int queuedBeforeResolution = queuedConnects(scheduler).size();
        service.sync(dataSource);
        assertEquals(queuedBeforeResolution, queuedConnects(scheduler).size(),
                "名额满时不应为刚补全房间号的主播排队建连");
        assertTrue(service.getStatus(1011L).isEmpty(), "名额满时不应接管第十一位主播");

        when(dataSource.getUsers(anyString())).thenReturn(users.subList(1, 11));
        service.sync(dataSource);
        runQueued(scheduler);

        assertEquals(10, service.getManagedRoomCount(), "腾出名额后应接管第十一位主播");
        assertFalse(startupWaveOf(service, 1011L),
                "名额满而没连的时间，不应算成程序停机未采集");
    }

    /**
     * 取出排进闸门等待放行的建连任务
     * <p>
     * 调度器是桩，任务不会自己跑，因此先收下来再由用例决定何时执行。
     * 不让桩同步执行：首连失败后排出的重连会立刻再回到桩里，无限递归下去
     * @param scheduler 调度器桩
     * @return 已排队的建连任务
     */
    private List<Runnable> queuedConnects(TaskScheduler scheduler) {
        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, atLeast(0)).schedule(tasks.capture(), any(Instant.class));
        return tasks.getAllValues();
    }

    /**
     * 找出第一条含指定内容的 WARN 日志
     * @param appender 日志收集器
     * @param keyword 关键字
     * @return 日志内容，没有时为 null
     */
    private String warnContaining(ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender,
                                  String keyword) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains(keyword))
                .findFirst()
                .orElse(null);
    }

    /**
     * 构造若干个已启用的哔哩哔哩推送用户，uid 自 startUid 起递增，房间号为 1000 + uid
     * <p>
     * 顺序是判据的一部分：截断要按数据源给出的先后取，否则「留下哪几间」每次启动都可能不同
     * @param startUid 起始 uid
     * @param count 用户数
     * @return 推送用户列表
     */
    private List<PushUser> users(int startUid, int count) {
        List<PushUser> users = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long uid = startUid + i;
            PushUser user = new PushUser();
            user.setUid(uid);
            user.setUname("主播" + uid);
            user.setRoomId(1000L + uid);
            user.setPlatform(BilibiliPlatform.BILIBILI.id());
            user.setEnabled(true);
            user.setTargets(new ArrayList<>());
            users.add(user);
        }
        return users;
    }

    /**
     * 构造被测服务（默认配置）
     */
    private BilibiliLiveRoomService service(TaskScheduler scheduler) {
        return service(scheduler, new NovaBilibiliProperties());
    }

    /**
     * 构造被测服务
     */
    private BilibiliLiveRoomService service(TaskScheduler scheduler, NovaBilibiliProperties properties) {
        return service(scheduler, properties, mock(BilibiliApiUtil.class), nobodyLiveOnAccount());
    }

    /**
     * 构造被测服务
     */
    private BilibiliLiveRoomService service(TaskScheduler scheduler, NovaBilibiliProperties properties,
                                            BilibiliApiUtil api, LiveDataService liveDataService) {
        return new BilibiliLiveRoomService(
                api,
                mock(BilibiliEventParser.class),
                properties,
                mock(ApplicationEventPublisher.class),
                scheduler,
                mock(BilibiliLiveStateGate.class),
                // 用真实闸门而不是 mock：首连的错开间隔现在由它产生，
                // mock 掉就等于把被测行为一起 mock 没了
                new BilibiliConnectGate(properties, scheduler),
                new BilibiliRiskMetrics(),
                new org.frostnova.nova.bilibili.health.BilibiliDisconnectDigest(properties, scheduler),
                liveDataService
        );
    }

    /**
     * 构造账上谁都不在播的在播状态替身
     * <p>
     * 默认答「取不到状态」——生产码把取不到状态的按不在播处理，替身照同一口径答
     */
    private LiveDataService nobodyLiveOnAccount() {
        LiveDataService liveDataService = mock(LiveDataService.class);
        when(liveDataService.getLiveStatus(anyString(), anyLong())).thenReturn(Optional.empty());
        return liveDataService;
    }

    /**
     * 构造只有 uid 一位账上在播的在播状态替身，其余取不到状态
     */
    private LiveDataService liveOnAccount(Long uid) {
        LiveDataService liveDataService = nobodyLiveOnAccount();
        when(liveDataService.getLiveStatus(BilibiliPlatform.BILIBILI.id(), uid)).thenReturn(Optional.of(true));
        return liveDataService;
    }

    /**
     * 构造记录建连先后的接口替身，谁来问连接信息就把谁的房间号记下
     * <p>
     * 建连任务是不透明的闭包，只有它向接口要连接信息时才知道排进闸门的这一个是谁。
     * 答一份取不到地址的连接信息：这里量的是「先连谁」，不是真把连接建起来
     * @param connectOrder 建连房间号的记录处，按下闸门的先后追加
     */
    private BilibiliApiUtil recordingApi(List<Long> connectOrder) {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getLiveRoomConnectInfo(anyLong())).thenAnswer(invocation -> {
            connectOrder.add(invocation.getArgument(0));
            return new ConnectInfo();
        });
        return api;
    }

    /**
     * 放行排进闸门等待的建连任务
     * <p>
     * 调度器是桩，任务不会自己跑，由用例决定何时执行。快照成普通列表再逐个放行：
     * 建连失败排出的重连会再回到桩里，不该跟着这一批放行
     * @param scheduler 调度器桩
     */
    private void runQueued(TaskScheduler scheduler) {
        List<Runnable> queued = new ArrayList<>(queuedConnects(scheduler));
        queued.forEach(Runnable::run);
    }

    /**
     * 读连接器在构造时收下的启动那一波标志
     * @param service 被测服务
     * @param roomId 直播间号
     * @return 该房的连接器是否算启动那一波
     */
    @SuppressWarnings("unchecked")
    private static boolean startupWaveOf(BilibiliLiveRoomService service, long roomId) throws Exception {
        Field connectorsField = BilibiliLiveRoomService.class.getDeclaredField("connectors");
        connectorsField.setAccessible(true);
        Map<Long, BilibiliLiveRoomConnector> connectors =
                (Map<Long, BilibiliLiveRoomConnector>) connectorsField.get(service);
        BilibiliLiveRoomConnector connector = connectors.get(roomId);
        assertNotNull(connector, "房间 " + roomId + " 应已建出连接器");
        Field wave = BilibiliLiveRoomConnector.class.getDeclaredField("startupWave");
        wave.setAccessible(true);
        return wave.getBoolean(connector);
    }

    /**
     * 构造一个不含任何推送用户的数据源桩
     */
    private AbstractDataSource emptyDataSource() {
        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getUsers(anyString())).thenReturn(List.of());
        return dataSource;
    }
}
