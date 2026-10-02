package org.frostnova.nova.bilibili.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.enums.DataPackType;
import org.frostnova.nova.bilibili.health.BilibiliDisconnectDigest;
import org.frostnova.nova.bilibili.health.BilibiliRiskMetrics;
import org.frostnova.nova.bilibili.model.ConnectAddress;
import org.frostnova.nova.bilibili.model.ConnectInfo;
import org.frostnova.nova.bilibili.model.Up;
import org.frostnova.nova.bilibili.protocol.BilibiliPacketCodec;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.EventConfig;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.service.DefaultLiveDataService;
import org.frostnova.nova.core.service.LiveDataService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 停机时正在建连的那一间也得在共用上限内放开，停机后不再新建连接。
 */
@DisplayName("停机时正在建连")
class ShutdownWhileConnectingTest {
    /**
     * 全部直播间合计等待的上限。产品结论钉在 3 秒，不从实现常量里读。
     */
    private static final long SHUTDOWN_BUDGET_MILLIS = 3_000L;

    /**
     * 上限之外留一点调度余量。干等建连结束会远超这条线。
     */
    private static final long SHUTDOWN_BUDGET_SLACK_MILLIS = 4_500L;

    /**
     * 无参 {@code ConcurrentHashMap} 的默认表长。文档写明是 16。
     * 元素不到 12 个（默认负载因子 0.75）时不会扩容，表长保持 16。
     */
    private static final int DEFAULT_TABLE_LENGTH = 16;

    /**
     * 已在管的那一间。与 {@link #SAME_BIN_ROOM} 落在表长 16 的同一格，与 {@link #OTHER_BIN_ROOM} 不是。
     */
    private static final long MANAGED_ROOM = 10016L;

    /** 与 {@link #MANAGED_ROOM} 同一格的首连房号 */
    private static final long SAME_BIN_ROOM = 10032L;

    /** 与 {@link #MANAGED_ROOM} 不同格的首连房号，用作对照 */
    private static final long OTHER_BIN_ROOM = 10017L;

    private static final String PLATFORM = BilibiliPlatform.BILIBILI.id();

    private static final LiveStreamerInfo STREAMER = new LiveStreamerInfo(3000003L, "测试主播", 47731877194803L);

    @TempDir
    Path dir;

    private AnnotationConfigApplicationContext context;

    private DefaultLiveDataService live;

    private ExecutorService pool;

    @AfterEach
    void tearDown() {
        if (live != null) {
            live.onContextClosedEvent();
        }
        if (context != null) {
            context.close();
        }
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("建连卡在取连接信息时停机，断开在 3 秒多一点内结束并照常存盘，放行后那次建连不收消息")
    void shutdownWhileFetchingConnectInfoFinishesWithinBudget() throws Exception {
        // 用户看到的故障：网络卡住正在建连时退出，一直等到被系统强杀，本场数据没存上。
        NovaCoreProperties properties = liveProperties();
        live = new DefaultLiveDataService(properties);
        context = new AnnotationConfigApplicationContext();
        TaskScheduler scheduler = mock(TaskScheduler.class);
        NovaBilibiliProperties bilibili = new NovaBilibiliProperties();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        BilibiliApiUtil api = blockingApi(entered, release);
        BilibiliEventParser parser = countingParser(accepted);
        WebSocketClient client = deliveringClient();
        BilibiliLiveRoomService rooms = rooms(context, scheduler, bilibili, live);
        BilibiliLiveRoomConnector connector = connector(STREAMER, api, parser, bilibili, context, scheduler, client, live);
        managedConnectors(rooms).put(STREAMER.getRoomId(), connector);

        context.register(EventConfig.class);
        context.registerBean(DefaultLiveDataService.class, () -> live);
        context.registerBean(BilibiliLiveRoomService.class, () -> rooms);
        context.refresh();
        markLive(live, STREAMER);

        Logger logger = (Logger) LoggerFactory.getLogger(BilibiliLiveRoomService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        pool = Executors.newFixedThreadPool(2);
        Future<?> connecting = pool.submit(connector::connect);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "建连没有卡进取连接信息");

            Future<?> shutting = pool.submit(() -> context.publishEvent(new ContextClosedEvent(context)));
            boolean finished = waitFor(shutting);

            assertTrue(finished,
                    "建连卡在取连接信息时，退出断开应在 " + SHUTDOWN_BUDGET_SLACK_MILLIS
                            + " 毫秒内结束并往下存盘；干等这次建连会超过这条线（上限 "
                            + SHUTDOWN_BUDGET_MILLIS + " 毫秒）");
            assertTrue(Files.isRegularFile(dir.resolve("data.json")), "到点后应已把本场数据存盘");
            String warn = warnContaining(appender, "间");
            assertNotNull(warn, "到点应记一条警告，实际日志: " + appender.list);
            assertTrue(warn.contains("1 间"), "没等完的建连要算进「几间没办完」: " + warn);
            assertTrue(warn.contains("已继续保存"), "警告要写明照常存盘: " + warn);

            release.countDown();
            connecting.get(20, TimeUnit.SECONDS);
            assertEquals(0, accepted.get(), "放行后那次建连不应再收消息，实际收下 " + accepted.get());
        } finally {
            release.countDown();
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("停机开始时还排着的首连，到点后不新建连接器，也不收消息")
    void queuedFirstConnectAfterShutdownDoesNotOpenARoom() throws Exception {
        // 用户看到的故障：存盘之后，排队的首连又冒出一个还在收消息的连接。
        TaskScheduler scheduler = mock(TaskScheduler.class);
        NovaBilibiliProperties bilibili = new NovaBilibiliProperties();
        AtomicInteger accepted = new AtomicInteger();
        BilibiliEventParser parser = countingParser(accepted);
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getLiveRoomConnectInfo(anyLong())).thenReturn(availableInfo());
        BilibiliLiveRoomService rooms = new BilibiliLiveRoomService(
                api,
                parser,
                bilibili,
                mock(ApplicationEventPublisher.class),
                scheduler,
                mock(BilibiliLiveStateGate.class),
                new BilibiliConnectGate(bilibili, scheduler),
                new BilibiliRiskMetrics(),
                new BilibiliDisconnectDigest(bilibili, scheduler),
                nobodyLive());

        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getUsers(anyString())).thenReturn(List.of(streamerUser(STREAMER)));
        rooms.sync(dataSource);

        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(tasks.capture(), any(Instant.class));
        rooms.onContextClosed();
        tasks.getValue().run();

        if (rooms.getManagedRoomCount() > 0) {
            feedDanmu(managedConnectors(rooms).get(STREAMER.getRoomId()));
        }

        assertEquals(0, rooms.getManagedRoomCount(),
                "停机开始后到点的首连仍新建了连接器，管理中 " + rooms.getManagedRoomCount());
        assertEquals(0, accepted.get(),
                "这条首连收下了消息，实际 " + accepted.get());
    }

    @Test
    @DisplayName("首连卡在取连接信息、同桶另一间已在管时停机，断开在 3 秒多一点内结束并照常存盘")
    void shutdownDuringFirstConnectInSameBinFinishesWithinBudget() throws Exception {
        // 用户看到的故障：同时管着几间直播间时退出，正在第一次连接的那一间和已经连上的另一间
        // 落在连接表的同一格，退出要一直等到这次连接返回，超过 3 秒就被系统强制结束，这一场没存上盘。
        shutdownDuringFirstConnect(true);
    }

    @Test
    @DisplayName("首连卡在取连接信息、另一间不在同桶时停机，断开在 3 秒多一点内结束并照常存盘")
    void shutdownDuringFirstConnectInOtherBinFinishesWithinBudget() throws Exception {
        // 对照：另一间不在同一格时，退出不必等这次第一次连接，能在时限内断开并把本场数据存盘。
        shutdownDuringFirstConnect(false);
    }

    /**
     * 首连走服务自己的建连（会写进连接表），卡在取连接信息。
     * @param sameBin 为 true 时首连与已在管的那一间同一格；为 false 时用作不同格对照
     */
    private void shutdownDuringFirstConnect(boolean sameBin) throws Exception {
        long firstRoom = sameBin ? SAME_BIN_ROOM : OTHER_BIN_ROOM;
        String fact = bucketFact(MANAGED_ROOM, firstRoom, DEFAULT_TABLE_LENGTH);
        if (sameBin) {
            assertEquals(binOf(MANAGED_ROOM, DEFAULT_TABLE_LENGTH), binOf(firstRoom, DEFAULT_TABLE_LENGTH),
                    "同桶两间的桶号必须相同。" + fact);
        } else {
            assertNotEquals(binOf(MANAGED_ROOM, DEFAULT_TABLE_LENGTH), binOf(firstRoom, DEFAULT_TABLE_LENGTH),
                    "不同桶对照的桶号必须不同。" + fact);
        }

        NovaCoreProperties properties = liveProperties();
        live = new DefaultLiveDataService(properties);
        context = new AnnotationConfigApplicationContext();
        TaskScheduler scheduler = mock(TaskScheduler.class);
        NovaBilibiliProperties bilibili = new NovaBilibiliProperties();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BilibiliApiUtil api = firstConnectBlockingApi(firstRoom, entered, release);
        BilibiliLiveRoomService rooms = rooms(context, scheduler, bilibili, live, api);
        LiveStreamerInfo managed = new LiveStreamerInfo(MANAGED_ROOM, "已在管", MANAGED_ROOM);
        managedConnectors(rooms).put(MANAGED_ROOM, connector(
                managed,
                mock(BilibiliApiUtil.class),
                countingParser(new AtomicInteger()),
                bilibili,
                context,
                scheduler,
                mock(WebSocketClient.class),
                live));
        assertEquals(1, rooms.getManagedRoomCount(),
                "停机前应只有已在管的那一间，表长才保持默认值。" + fact);

        context.register(EventConfig.class);
        context.registerBean(DefaultLiveDataService.class, () -> live);
        context.registerBean(BilibiliLiveRoomService.class, () -> rooms);
        context.refresh();
        markLive(live, managed);

        Logger logger = (Logger) LoggerFactory.getLogger(BilibiliLiveRoomService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        pool = Executors.newFixedThreadPool(2);
        Method connect = BilibiliLiveRoomService.class.getDeclaredMethod("connect", Up.class);
        connect.setAccessible(true);
        Up first = new Up(firstRoom, "首连", firstRoom);
        Future<?> connecting = pool.submit(() -> {
            try {
                connect.invoke(rooms, first);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException(ex);
            }
        });
        Exception tail = null;
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "首连没有卡进取连接信息。" + fact);

            Future<?> shutting = pool.submit(() -> context.publishEvent(new ContextClosedEvent(context)));
            boolean finished = waitFor(shutting);
            String where = sameBin ? "同桶另一间已在管" : "另一间不在同桶";
            assertTrue(finished,
                    "首连卡在取连接信息、" + where + "时，退出断开应在 " + SHUTDOWN_BUDGET_SLACK_MILLIS
                            + " 毫秒内结束并往下存盘；同桶干等这次首连会超过这条线（上限 "
                            + SHUTDOWN_BUDGET_MILLIS + " 毫秒）。" + fact);
            assertTrue(Files.isRegularFile(dir.resolve("data.json")), "到点后应已把本场数据存盘。" + fact);
            if (sameBin) {
                String warn = warnContaining(appender, "间");
                assertNotNull(warn, "到点应记一条警告，实际日志: " + appender.list);
                assertTrue(warn.contains("1 间"), "没等完的首连要算进「几间没办完」: " + warn);
                assertTrue(warn.contains("已继续保存"), "警告要写明照常存盘: " + warn);
            }
        } finally {
            release.countDown();
            try {
                connecting.get(20, TimeUnit.SECONDS);
            } catch (Exception ex) {
                tail = ex;
            }
            logger.detachAppender(appender);
        }
        if (tail != null) {
            throw tail;
        }
    }

    private boolean waitFor(Future<?> shutting) throws Exception {
        try {
            shutting.get(SHUTDOWN_BUDGET_SLACK_MILLIS, TimeUnit.MILLISECONDS);
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

    private BilibiliApiUtil firstConnectBlockingApi(long firstRoom, CountDownLatch entered, CountDownLatch release) {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getLiveRoomConnectInfo(anyLong())).thenAnswer(invocation -> {
            long roomId = invocation.getArgument(0);
            if (roomId != firstRoom) {
                return availableInfo();
            }
            entered.countDown();
            release.await();
            throw new IllegalStateException("测试放行后不再往下连");
        });
        return api;
    }

    private BilibiliApiUtil blockingApi(CountDownLatch entered, CountDownLatch release) {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getLiveRoomConnectInfo(anyLong())).thenAnswer(invocation -> {
            entered.countDown();
            release.await();
            return availableInfo();
        });
        return api;
    }

    private static ConnectInfo availableInfo() {
        ConnectAddress address = new ConnectAddress();
        address.setHost("harness.chat.bilibili.com");
        address.setPort(2243);
        address.setWssPort(443);
        address.setWsPort(2244);
        ConnectInfo info = new ConnectInfo();
        info.setToken("snapshot-token");
        info.setUid(0L);
        info.setAddresses(new ArrayList<>(List.of(address)));
        return info;
    }

    private static BilibiliEventParser countingParser(AtomicInteger accepted) {
        BilibiliEventParser parser = mock(BilibiliEventParser.class);
        when(parser.parseMessage(any(), any())).thenAnswer(invocation -> {
            accepted.incrementAndGet();
            return new BilibiliEventParser.ParsedMessage(Optional.empty(), false);
        });
        return parser;
    }

    /**
     * 握手一旦被调用，就立刻塞进一条弹幕。建连若在停机之后还往下走，这条会被收下。
     */
    private static WebSocketClient deliveringClient() throws Exception {
        WebSocketClient client = mock(WebSocketClient.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("shutdown-connect");
        byte[] danmu = BilibiliPacketCodec.encode(DataPackType.NOTICE, "{\"cmd\":\"DANMU_MSG\"}");
        when(client.execute(any(WebSocketHandler.class), any(WebSocketHttpHeaders.class), any(URI.class)))
                .thenAnswer(invocation -> {
                    WebSocketHandler handler = invocation.getArgument(0);
                    handler.afterConnectionEstablished(session);
                    handler.handleMessage(session, new BinaryMessage(danmu));
                    return CompletableFuture.completedFuture(session);
                });
        return client;
    }

    private static void feedDanmu(BilibiliLiveRoomConnector connector) throws Exception {
        byte[] danmu = BilibiliPacketCodec.encode(DataPackType.NOTICE, "{\"cmd\":\"DANMU_MSG\"}");
        connector.handleMessage(mock(WebSocketSession.class), new BinaryMessage(danmu));
    }

    private BilibiliLiveRoomConnector connector(LiveStreamerInfo streamer, BilibiliApiUtil api,
                                                BilibiliEventParser parser, NovaBilibiliProperties bilibili,
                                                AnnotationConfigApplicationContext publisher, TaskScheduler scheduler,
                                                WebSocketClient client, DefaultLiveDataService data) {
        return new BilibiliLiveRoomConnector(
                streamer,
                api,
                parser,
                bilibili,
                publisher,
                scheduler,
                client,
                new BilibiliLiveStateGate(data),
                mock(BilibiliConnectGate.class),
                new BilibiliRiskMetrics(),
                new BilibiliDisconnectDigest(bilibili, scheduler),
                data,
                new java.util.concurrent.atomic.AtomicBoolean(true));
    }

    private BilibiliLiveRoomService rooms(AnnotationConfigApplicationContext publisher, TaskScheduler scheduler,
                                          NovaBilibiliProperties bilibili, DefaultLiveDataService data) {
        return rooms(publisher, scheduler, bilibili, data, mock(BilibiliApiUtil.class));
    }

    private BilibiliLiveRoomService rooms(AnnotationConfigApplicationContext publisher, TaskScheduler scheduler,
                                          NovaBilibiliProperties bilibili, DefaultLiveDataService data,
                                          BilibiliApiUtil api) {
        return new BilibiliLiveRoomService(
                api,
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

    /**
     * 与 {@code ConcurrentHashMap} 相同的散布。表长是 2 的幂时，桶号是散布结果的低几位。
     */
    private static int spread(int hash) {
        return (hash ^ (hash >>> 16)) & 0x7fffffff;
    }

    private static int binOf(long roomId, int tableLength) {
        return (tableLength - 1) & spread(Long.hashCode(roomId));
    }

    private static String bucketFact(long managedRoom, long firstRoom, int tableLength) {
        return " 房号 已在管=" + managedRoom + " 首连=" + firstRoom
                + " 表长=" + tableLength
                + " 桶 已在管=" + binOf(managedRoom, tableLength)
                + " 首连=" + binOf(firstRoom, tableLength)
                + " 算法=(表长-1)&spread(Long.hashCode) spread=(h^(h>>>16))&0x7fffffff"
                + " 表长取无参 ConcurrentHashMap 的默认值 16，停机前只有 1 个元素，低于扩容线 12";
    }

    private static LiveDataService nobodyLive() {
        LiveDataService liveDataService = mock(LiveDataService.class);
        when(liveDataService.getLiveStatus(anyString(), anyLong())).thenReturn(Optional.empty());
        return liveDataService;
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

    @SuppressWarnings("unchecked")
    private static Map<Long, BilibiliLiveRoomConnector> managedConnectors(BilibiliLiveRoomService rooms)
            throws ReflectiveOperationException {
        Field field = BilibiliLiveRoomService.class.getDeclaredField("connectors");
        field.setAccessible(true);
        return (Map<Long, BilibiliLiveRoomConnector>) field.get(rooms);
    }

    private static String warnContaining(ListAppender<ILoggingEvent> appender, String keyword) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains(keyword))
                .findFirst()
                .orElse(null);
    }
}
