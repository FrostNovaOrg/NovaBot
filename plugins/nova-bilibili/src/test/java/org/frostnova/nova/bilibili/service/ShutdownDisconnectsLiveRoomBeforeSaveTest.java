package org.frostnova.nova.bilibili.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.event.live.BilibiliLiveOffEvent;
import org.frostnova.nova.bilibili.health.BilibiliDisconnectDigest;
import org.frostnova.nova.bilibili.health.BilibiliRiskMetrics;
import org.frostnova.nova.bilibili.model.Room;
import org.frostnova.nova.bilibili.protocol.BilibiliPacketCodec;
import org.frostnova.nova.bilibili.enums.DataPackType;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.EventConfig;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.LiveEndReason;
import org.frostnova.nova.core.listener.NovaDefaultLiveOffEventListener;
import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.service.DefaultLiveDataService;
import org.frostnova.nova.core.service.LiveDetailArchive;
import org.frostnova.nova.core.service.LiveInterventionTracker;
import org.frostnova.nova.core.service.LiveRoomInfoHistory;
import org.frostnova.nova.core.service.LiveSessionArchive;
import org.frostnova.nova.core.service.LiveSessionDetailArchiver;
import org.frostnova.nova.core.service.LiveSessionRecovery;
import org.frostnova.nova.core.service.NovaStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.event.SmartApplicationListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 停机时先断开直播间，存盘之后才到的下播不能把同一场记两次。
 * <p>
 * 下播喂在存盘那一步的末尾。存盘次序读生产件 {@code DefaultLiveDataService} 上的注解，
 * 这里不再另标。断开若没有排在这之前，这条下播仍会归档并入累计，盘上却还记着在播；
 * 下次启动从这份盘读回来，下一次开播又按未闭合再归档一次。
 * 场次归档是追加写，两次都留在文件里，重启抹不掉。
 * <p>
 * 另外三格：手头那条卡住时断开必须在上限内结束并照常存盘；断开之后备用轮询再发现的下播不再入账；
 * 另一线程正在办的那条，存盘要等它办完。
 */
@DisplayName("停机时先断开直播间")
class ShutdownDisconnectsLiveRoomBeforeSaveTest {
    private static final String PLATFORM = BilibiliPlatform.BILIBILI.id();

    /**
     * 全部直播间合计等待的上限。产品结论钉在 3 秒，不从实现常量里读。
     */
    private static final long SHUTDOWN_BUDGET_MILLIS = 3_000L;

    /**
     * 上限之外留一点调度余量。两间各等满 3 秒会落到 6 秒，超得过这条线。
     */
    private static final long SHUTDOWN_BUDGET_SLACK_MILLIS = 4_500L;

    private static final LiveStreamerInfo STREAMER = new LiveStreamerInfo(3000003L, "测试主播", 47731877194803L);

    private static final LiveStreamerInfo STREAMER_B = new LiveStreamerInfo(3000004L, "测试主播乙", 47731877194804L);

    @TempDir
    Path dir;

    private AnnotationConfigApplicationContext context;

    private DefaultLiveDataService live;

    @AfterEach
    void tearDown() {
        if (live != null) {
            live.onContextClosedEvent();
        }
        if (context != null) {
            context.close();
        }
    }

    @Test
    @DisplayName("存盘之后才到的下播，下次开播只把这一场记一次")
    void lateOffAfterSaveIsCountedOnce() throws Exception {
        NovaCoreProperties properties = liveProperties();
        SaveThenLateOff saving = new SaveThenLateOff(properties);
        live = saving;
        LiveSessionArchive archive = archive(properties);
        NovaDefaultLiveOffEventListener off = offListener(saving, archive, properties);

        context = new AnnotationConfigApplicationContext();
        TaskScheduler scheduler = mock(TaskScheduler.class);
        NovaBilibiliProperties bilibili = new NovaBilibiliProperties();
        BilibiliLiveRoomConnector connector = connector(STREAMER, context, scheduler, bilibili, saving);
        BilibiliLiveRoomService rooms = rooms(context, scheduler, bilibili, saving);
        managedConnectors(rooms).put(STREAMER.getRoomId(), connector);
        saving.feed(connector);

        context.register(EventConfig.class);
        context.registerBean(NovaDefaultLiveOffEventListener.class, () -> off);
        context.registerBean(BilibiliLiveRoomService.class, () -> rooms);
        context.addApplicationListener(new OrderedSave(productionSaveOrder(), saving::onContextClosedEvent));
        context.refresh();

        markLive(saving, STREAMER);

        context.publishEvent(new ContextClosedEvent(context));

        recover(properties, archive);

        List<LiveSession> sessions = archive.find(0, Long.MAX_VALUE);
        assertEquals(1, sessions.size(),
                "同一场被记了 " + sessions.size() + " 次: " + endReasons(sessions));
        assertEquals(LiveEndReason.UNCLOSED, sessions.get(0).endReason(),
                "漏掉的下播应只在下次开播时按未闭合补记一次，实际是 " + sessions.get(0).endReason());
    }

    @Test
    @DisplayName("手头那条消息卡住不返回时，退出断开在 3 秒多一点内结束，并照常存盘")
    void shutdownGivesUpWhenMessageStaysStuck() throws Exception {
        // 用户看到的故障：退出正赶上一条消息办不完，一直等到被系统强杀，本场数据没存上。
        NovaCoreProperties properties = liveProperties();
        live = new DefaultLiveDataService(properties);
        context = new AnnotationConfigApplicationContext();
        TaskScheduler scheduler = mock(TaskScheduler.class);
        NovaBilibiliProperties bilibili = new NovaBilibiliProperties();
        BilibiliLiveRoomService rooms = rooms(context, scheduler, bilibili, live);
        managedConnectors(rooms).put(STREAMER.getRoomId(), connector(STREAMER, context, scheduler, bilibili, live));
        managedConnectors(rooms).put(STREAMER_B.getRoomId(), connector(STREAMER_B, context, scheduler, bilibili, live));
        StallOnLiveOff stall = new StallOnLiveOff(2);

        context.register(EventConfig.class);
        context.registerBean(DefaultLiveDataService.class, () -> live);
        context.registerBean(BilibiliLiveRoomService.class, () -> rooms);
        context.registerBean(StallOnLiveOff.class, () -> stall);
        context.refresh();
        markLive(live, STREAMER);

        Logger logger = (Logger) LoggerFactory.getLogger(BilibiliLiveRoomService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            BilibiliLiveRoomConnector first = managedConnectors(rooms).get(STREAMER.getRoomId());
            BilibiliLiveRoomConnector second = managedConnectors(rooms).get(STREAMER_B.getRoomId());
            pool.submit(() -> receivePreparing(first));
            pool.submit(() -> receivePreparing(second));
            assertTrue(stall.entered.await(5, TimeUnit.SECONDS), "两条下播没有都卡在处理中");

            Future<?> closed = pool.submit(() -> context.publishEvent(new ContextClosedEvent(context)));
            boolean finished = waitFor(closed);
            stall.release.countDown();
            closed.get(20, TimeUnit.SECONDS);

            assertTrue(finished,
                    "手头消息卡住不返回时，退出断开应在 " + SHUTDOWN_BUDGET_SLACK_MILLIS
                            + " 毫秒内结束并往下存盘；两间加起来若各等满 " + SHUTDOWN_BUDGET_MILLIS
                            + " 毫秒，会超过这条线");
            assertTrue(Files.isRegularFile(dir.resolve("data.json")), "到点后应已把本场数据存盘");
            String warn = warnContaining(appender, "间");
            assertNotNull(warn, "到点应记一条警告，实际日志: " + appender.list);
            assertTrue(warn.contains("2 间"), "警告要写明几间没等完: " + warn);
            assertTrue(warn.contains("已继续保存"), "警告要写明照常存盘: " + warn);
        } finally {
            stall.release.countDown();
            logger.detachAppender(appender);
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("退出断开之后备用轮询再发现下播，不再入账，下次开播只补记一次")
    void backupPollAfterShutdownDoesNotCountLiveOffTwice() throws Exception {
        // 用户看到的故障：直播间已经断开、本场也存了盘，备用轮询又补来一条下播，同一场记两次。
        NovaCoreProperties properties = liveProperties();
        live = new DefaultLiveDataService(properties);
        LiveSessionArchive sessions = archive(properties);
        context = new AnnotationConfigApplicationContext();
        TaskScheduler scheduler = mock(TaskScheduler.class);
        NovaBilibiliProperties bilibili = new NovaBilibiliProperties();
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        BilibiliBackupLivePushService backup = new BilibiliBackupLivePushService(
                api, bilibili, context, scheduler, new BilibiliLiveStateGate(live), live,
                mock(LiveSessionRecovery.class));
        BilibiliLiveRoomService rooms = rooms(context, scheduler, bilibili, live);
        managedConnectors(rooms).put(STREAMER.getRoomId(), connector(STREAMER, context, scheduler, bilibili, live));

        context.register(EventConfig.class);
        context.registerBean(DefaultLiveDataService.class, () -> live);
        context.registerBean(NovaDefaultLiveOffEventListener.class, () -> offListener(live, sessions, properties));
        context.registerBean(BilibiliLiveRoomService.class, () -> rooms);
        context.registerBean(BilibiliBackupLivePushService.class, () -> backup);
        context.refresh();
        markLive(live, STREAMER);

        backup.start(dataSource);
        Runnable poll = capturedPoll(scheduler);
        when(dataSource.getUsers(PLATFORM)).thenReturn(List.of(streamerUser(STREAMER)));
        when(api.getLiveInfoByUids(anySet()))
                .thenReturn(Map.of(STREAMER.getUid(), livingRoom()))
                .thenReturn(Map.of(STREAMER.getUid(), offlineRoom()));
        poll.run();

        context.publishEvent(new ContextClosedEvent(context));
        poll.run();
        recover(properties, sessions);

        List<LiveSession> found = sessions.find(0, Long.MAX_VALUE);
        assertEquals(1, found.size(),
                "同一场被记了 " + found.size() + " 次: " + endReasons(found));
        assertEquals(LiveEndReason.UNCLOSED, found.get(0).endReason(),
                "断开之后备用轮询发现的下播不应入账，实际是 " + found.get(0).endReason());
    }

    @Test
    @DisplayName("另一线程正在办下播时，存盘要等它办完")
    void saveWaitsUntilInFlightMessageFinishes() throws Exception {
        // 用户看到的故障：断开不等手头那条，下播办到一半就存盘。
        NovaCoreProperties properties = liveProperties();
        AtomicBoolean finished = new AtomicBoolean();
        AtomicBoolean saveSawFinished = new AtomicBoolean();
        CountDownLatch saveStarted = new CountDownLatch(1);
        NoteThenSave saving = new NoteThenSave(properties, finished, saveSawFinished, saveStarted);
        live = saving;
        context = new AnnotationConfigApplicationContext();
        TaskScheduler scheduler = mock(TaskScheduler.class);
        NovaBilibiliProperties bilibili = new NovaBilibiliProperties();
        BilibiliLiveRoomService rooms = rooms(context, scheduler, bilibili, saving);
        BilibiliLiveRoomConnector connector = connector(STREAMER, context, scheduler, bilibili, saving);
        managedConnectors(rooms).put(STREAMER.getRoomId(), connector);
        StallOnLiveOff stall = new StallOnLiveOff(1);
        stall.finished = finished;

        context.register(EventConfig.class);
        context.registerBean(BilibiliLiveRoomService.class, () -> rooms);
        context.registerBean(StallOnLiveOff.class, () -> stall);
        context.addApplicationListener(new OrderedSave(productionSaveOrder(), saving::onContextClosedEvent));
        context.refresh();
        markLive(saving, STREAMER);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            pool.submit(() -> receivePreparing(connector));
            assertTrue(stall.entered.await(5, TimeUnit.SECONDS), "下播没有卡在处理中");
            Future<?> closed = pool.submit(() -> context.publishEvent(new ContextClosedEvent(context)));
            boolean saveEarly = saveStarted.await(1, TimeUnit.SECONDS);
            stall.release.countDown();
            closed.get(5, TimeUnit.SECONDS);
            assertFalse(saveEarly, "存盘没有等手头那条下播办完");
            assertTrue(saveSawFinished.get(), "存盘时手头那条下播还没办完");
        } finally {
            stall.release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("备用轮询已经在发布下播时，存盘要等这一趟办完")
    void saveWaitsUntilBackupPublishFinishes() throws Exception {
        // 用户看到的故障：备用轮询这条下播已经发出去了，存盘不等它，办完时盘上仍记着在播，同一场记两次。
        NovaCoreProperties properties = liveProperties();
        AtomicBoolean finished = new AtomicBoolean();
        AtomicBoolean saveSawFinished = new AtomicBoolean();
        CountDownLatch saveStarted = new CountDownLatch(1);
        NoteThenSave saving = new NoteThenSave(properties, finished, saveSawFinished, saveStarted);
        live = saving;
        context = new AnnotationConfigApplicationContext();
        TaskScheduler scheduler = mock(TaskScheduler.class);
        NovaBilibiliProperties bilibili = new NovaBilibiliProperties();
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        BilibiliBackupLivePushService backup = new BilibiliBackupLivePushService(
                api, bilibili, context, scheduler, new BilibiliLiveStateGate(saving), saving,
                mock(LiveSessionRecovery.class));
        BilibiliLiveRoomService rooms = rooms(context, scheduler, bilibili, saving);
        StallOnLiveOff stall = new StallOnLiveOff(1);
        stall.finished = finished;

        context.register(EventConfig.class);
        context.registerBean(BilibiliLiveRoomService.class, () -> rooms);
        context.registerBean(BilibiliBackupLivePushService.class, () -> backup);
        context.registerBean(StallOnLiveOff.class, () -> stall);
        context.addApplicationListener(new OrderedSave(productionSaveOrder(), saving::onContextClosedEvent));
        context.refresh();
        markLive(saving, STREAMER);

        backup.start(dataSource);
        Runnable poll = capturedPoll(scheduler);
        when(dataSource.getUsers(PLATFORM)).thenReturn(List.of(streamerUser(STREAMER)));
        when(api.getLiveInfoByUids(anySet()))
                .thenReturn(Map.of(STREAMER.getUid(), livingRoom()))
                .thenReturn(Map.of(STREAMER.getUid(), offlineRoom()));
        poll.run();

        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            pool.submit(poll);
            assertTrue(stall.entered.await(5, TimeUnit.SECONDS), "备用轮询的下播没有卡在发布中");
            Future<?> closed = pool.submit(() -> context.publishEvent(new ContextClosedEvent(context)));
            boolean saveEarly = saveStarted.await(1, TimeUnit.SECONDS);
            stall.release.countDown();
            closed.get(5, TimeUnit.SECONDS);
            assertFalse(saveEarly, "存盘没有等备用轮询这条下播办完");
            assertTrue(saveSawFinished.get(), "存盘时备用轮询这条下播还没办完");
        } finally {
            stall.release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("卡住的直播间和正在发布的备用轮询共用一个 3 秒上限")
    void roomsAndBackupPublishShareOneShutdownBudget() throws Exception {
        // 用户看到的故障：直播间等满 3 秒、备用轮询再等 3 秒，退出被拉长，仍可能在存盘前被强杀。
        NovaCoreProperties properties = liveProperties();
        live = new DefaultLiveDataService(properties);
        context = new AnnotationConfigApplicationContext();
        TaskScheduler scheduler = mock(TaskScheduler.class);
        NovaBilibiliProperties bilibili = new NovaBilibiliProperties();
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        BilibiliBackupLivePushService backup = new BilibiliBackupLivePushService(
                api, bilibili, context, scheduler, new BilibiliLiveStateGate(live), live,
                mock(LiveSessionRecovery.class));
        BilibiliLiveRoomService rooms = rooms(context, scheduler, bilibili, live);
        BilibiliLiveRoomConnector connector = connector(STREAMER, context, scheduler, bilibili, live);
        managedConnectors(rooms).put(STREAMER.getRoomId(), connector);
        StallOnLiveOff stall = new StallOnLiveOff(2);

        context.register(EventConfig.class);
        context.registerBean(DefaultLiveDataService.class, () -> live);
        context.registerBean(BilibiliLiveRoomService.class, () -> rooms);
        context.registerBean(BilibiliBackupLivePushService.class, () -> backup);
        context.registerBean(StallOnLiveOff.class, () -> stall);
        context.refresh();
        markLive(live, STREAMER);

        backup.start(dataSource);
        Runnable poll = capturedPoll(scheduler);
        when(dataSource.getUsers(PLATFORM)).thenReturn(List.of(streamerUser(STREAMER)));
        when(api.getLiveInfoByUids(anySet()))
                .thenReturn(Map.of(STREAMER.getUid(), livingRoom()))
                .thenReturn(Map.of(STREAMER.getUid(), offlineRoom()));
        poll.run();

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            pool.submit(poll);
            pool.submit(() -> receivePreparing(connector));
            assertTrue(stall.entered.await(5, TimeUnit.SECONDS), "直播间下播和备用轮询下播没有都卡在处理中");
            Future<?> closed = pool.submit(() -> context.publishEvent(new ContextClosedEvent(context)));
            boolean finished = waitFor(closed);
            stall.release.countDown();
            closed.get(20, TimeUnit.SECONDS);
            assertTrue(finished,
                    "直播间和备用轮询应共用一个 " + SHUTDOWN_BUDGET_MILLIS
                            + " 毫秒上限，实际超过 " + SHUTDOWN_BUDGET_SLACK_MILLIS + " 毫秒还没结束");
            assertTrue(Files.isRegularFile(dir.resolve("data.json")), "到点后应已把本场数据存盘");
        } finally {
            stall.release.countDown();
            pool.shutdownNow();
        }
    }

    private boolean waitFor(Future<?> closed) throws Exception {
        try {
            closed.get(SHUTDOWN_BUDGET_SLACK_MILLIS, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException ex) {
            return false;
        }
    }

    private NovaCoreProperties liveProperties() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        properties.getLive().setSaveLiveData(true);
        properties.getLive().setAutoSaveLiveDataInterval(3600);
        return properties;
    }

    private void markLive(DefaultLiveDataService data, LiveStreamerInfo streamer) {
        data.onApplicationReadyEvent();
        long start = System.currentTimeMillis() - 3_600_000L;
        data.setLiveStatus(PLATFORM, streamer.getUid(), true);
        data.setLiveStartTime(PLATFORM, streamer.getUid(), start);
        data.incrementLiveMetric(PLATFORM, streamer.getUid(), "danmu_count", 12);
    }

    private LiveSessionArchive archive(NovaCoreProperties properties) {
        return new LiveSessionArchive(properties);
    }

    private NovaDefaultLiveOffEventListener offListener(DefaultLiveDataService data, LiveSessionArchive archive,
                                                        NovaCoreProperties properties) {
        LiveRoomInfoHistory history = new LiveRoomInfoHistory(new NovaStateStore(properties));
        return new NovaDefaultLiveOffEventListener(
                data, archive, new LiveInterventionTracker(), history,
                new LiveSessionDetailArchiver(data, history, new LiveDetailArchive(properties)));
    }

    private void recover(NovaCoreProperties properties, LiveSessionArchive archive) {
        DefaultLiveDataService restarted = new DefaultLiveDataService(properties);
        restarted.onApplicationReadyEvent();
        try {
            new LiveSessionRecovery(restarted, archive,
                    new LiveRoomInfoHistory(new NovaStateStore(properties)),
                    new LiveSessionDetailArchiver(restarted,
                            new LiveRoomInfoHistory(new NovaStateStore(properties)),
                            new LiveDetailArchive(properties)))
                    .archiveUnclosedIfAny(PLATFORM, STREAMER, System.currentTimeMillis());
        } finally {
            restarted.onContextClosedEvent();
        }
    }

    private BilibiliLiveRoomConnector connector(LiveStreamerInfo streamer, AnnotationConfigApplicationContext publisher,
                                                TaskScheduler scheduler, NovaBilibiliProperties bilibili,
                                                DefaultLiveDataService data) {
        return new BilibiliLiveRoomConnector(
                streamer,
                mock(BilibiliApiUtil.class),
                new BilibiliEventParser(bilibili, mock(BilibiliGiftService.class),
                        mock(BilibiliApiSupport.class), mock(BilibiliGuardReconciler.class)),
                bilibili,
                publisher,
                scheduler,
                mock(WebSocketClient.class),
                new BilibiliLiveStateGate(data),
                mock(BilibiliConnectGate.class),
                new BilibiliRiskMetrics(),
                new BilibiliDisconnectDigest(bilibili, scheduler),
                data,
                new java.util.concurrent.atomic.AtomicBoolean(true));
    }

    private BilibiliLiveRoomService rooms(AnnotationConfigApplicationContext publisher, TaskScheduler scheduler,
                                          NovaBilibiliProperties bilibili, DefaultLiveDataService data) {
        return new BilibiliLiveRoomService(
                mock(BilibiliApiUtil.class),
                mock(BilibiliEventParser.class),
                bilibili,
                publisher,
                scheduler,
                new BilibiliLiveStateGate(data),
                mock(BilibiliConnectGate.class),
                new BilibiliRiskMetrics(),
                new BilibiliDisconnectDigest(bilibili, scheduler),
                data);
    }

    private static Runnable capturedPoll(TaskScheduler scheduler) {
        org.mockito.ArgumentCaptor<Runnable> captor = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(captor.capture(), any(Duration.class));
        return captor.getValue();
    }

    private static PushUser streamerUser(LiveStreamerInfo streamer) {
        PushUser user = new PushUser();
        user.setUid(streamer.getUid());
        user.setUname(streamer.getUname());
        user.setRoomId(streamer.getRoomId());
        user.setPlatform(PLATFORM);
        user.setEnabled(true);
        return user;
    }

    private static Room livingRoom() {
        Room room = new Room();
        room.setLiveStatus(1);
        room.setLiveStartTime(1_757_000_000L);
        return room;
    }

    private static Room offlineRoom() {
        Room room = new Room();
        room.setLiveStatus(0);
        return room;
    }

    private static void receivePreparing(BilibiliLiveRoomConnector connector) {
        try {
            byte[] encoded = BilibiliPacketCodec.encode(DataPackType.NOTICE, "{\"cmd\":\"PREPARING\"}");
            connector.handleMessage(mock(WebSocketSession.class), new BinaryMessage(encoded));
        } catch (Exception e) {
            throw new IllegalStateException("喂下播时出错", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, BilibiliLiveRoomConnector> managedConnectors(BilibiliLiveRoomService rooms)
            throws ReflectiveOperationException {
        Field field = BilibiliLiveRoomService.class.getDeclaredField("connectors");
        field.setAccessible(true);
        return (Map<Long, BilibiliLiveRoomConnector>) field.get(rooms);
    }

    /**
     * 存盘次序只读生产件上的注解。测试自己不再标。
     */
    private static int productionSaveOrder() throws Exception {
        Order order = DefaultLiveDataService.class
                .getDeclaredMethod("onContextClosedEvent")
                .getAnnotation(Order.class);
        if (order == null) {
            throw new IllegalStateException("存盘方法上没有次序注解");
        }
        return order.value();
    }

    private static String warnContaining(ListAppender<ILoggingEvent> appender, String keyword) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains(keyword))
                .findFirst()
                .orElse(null);
    }

    private static String endReasons(List<LiveSession> sessions) {
        StringBuilder reasons = new StringBuilder();
        for (LiveSession session : sessions) {
            if (!reasons.isEmpty()) {
                reasons.append(", ");
            }
            reasons.append(session.endReason());
        }
        return reasons.toString();
    }

    /**
     * 收尾保存用的就是产品里那个方法。存盘返回之后立刻喂一条下播：断开若排在存盘后面，这条仍会被处理。
     * 这次调用排在哪，由生产件上的次序注解决定。
     */
    static final class SaveThenLateOff extends DefaultLiveDataService {
        private BilibiliLiveRoomConnector connector;

        SaveThenLateOff(NovaCoreProperties properties) {
            super(properties);
        }

        void feed(BilibiliLiveRoomConnector connector) {
            this.connector = connector;
        }

        @Override
        public void onContextClosedEvent() {
            super.onContextClosedEvent();
            try {
                receivePreparing(connector);
            } catch (Exception e) {
                throw new IllegalStateException("喂下播时出错", e);
            }
        }
    }

    /**
     * 记下存盘开始时，手头那条有没有已经办完。
     */
    static final class NoteThenSave extends DefaultLiveDataService {
        private final AtomicBoolean finished;
        private final AtomicBoolean sawFinished;
        private final CountDownLatch started;

        NoteThenSave(NovaCoreProperties properties, AtomicBoolean finished, AtomicBoolean sawFinished,
                     CountDownLatch started) {
            super(properties);
            this.finished = finished;
            this.sawFinished = sawFinished;
            this.started = started;
        }

        @Override
        public void onContextClosedEvent() {
            sawFinished.set(finished.get());
            started.countDown();
            super.onContextClosedEvent();
        }
    }

    /**
     * 把下播处理卡住，直到测试放行。
     */
    static final class StallOnLiveOff {
        private final int expected;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger arrivals = new AtomicInteger();
        private AtomicBoolean finished = new AtomicBoolean();

        StallOnLiveOff(int expected) {
            this.expected = expected;
        }

        @EventListener
        public void onOff(BilibiliLiveOffEvent event) {
            int n = arrivals.incrementAndGet();
            if (n == expected) {
                entered.countDown();
            }
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            finished.set(true);
        }
    }

    /**
     * 按生产件存盘方法上的次序，跑测试给的那一段收尾。
     */
    private static final class OrderedSave implements SmartApplicationListener {
        private final int order;
        private final Runnable action;

        private OrderedSave(int order, Runnable action) {
            this.order = order;
            this.action = action;
        }

        @Override
        public boolean supportsEventType(Class<? extends ApplicationEvent> eventType) {
            return ContextClosedEvent.class.isAssignableFrom(eventType);
        }

        @Override
        public void onApplicationEvent(ApplicationEvent event) {
            action.run();
        }

        @Override
        public int getOrder() {
            return order;
        }
    }
}
