package org.frostnova.nova.bilibili.service;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.event.live.BilibiliLiveOffEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliLiveOnEvent;
import org.frostnova.nova.bilibili.model.Room;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.event.live.base.NovaLiveStatusChangeEvent;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.service.LiveDataService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 备用轮询里一位主播的事件出错不再连累同轮后面几位
 * <p>
 * 用户故障：某位主播的开播（或下播）事件处理出错——各位监听器里有一个抛了异常——
 * 这个异常一路冒出轮询，同一轮里排在他后面的主播就没人管了：那几位的开播／下播
 * 这一轮不处理，群里要晚一轮才收到。
 * <p>
 * 本组格钉住：出错那位只留一条错误日志（带主播名与事件类型），同轮剩下的照常发，
 * 异常不带着冒出去。谁排最前由轮询自己那张表决定，格不挑人——本轮实际排最前的
 * 那位一发就抛，验的就是「他出错后排在后面的那位还发不发」。
 * <p>
 * LiveDataService 用自洽 mock（真 Map 后备）：下播那条路过闸门时回落读这本账。
 */
@DisplayName("备用轮询·一位主播的事件出错不连累同轮后面的")
class BilibiliBackupLivePushFaultIsolationTest {
    private static final long UID_ONE = 21000000000001L;
    private static final long UID_TWO = 21000000000002L;
    private static final long ROOM_ONE = 780001L;
    private static final long ROOM_TWO = 780002L;
    private static final long LIVE_START_SECONDS = 1757000000L;

    /**
     * 埋进主播头像地址里的记号：错误日志这一行只带主播名与事件类型，
     * up 与事件都不进这行——记号不该在行里露面
     */
    private static final String FACE_MARK = "face-mark-should-not-be-logged";

    private BilibiliApiUtil api;
    private AbstractDataSource dataSource;
    private ApplicationEventPublisher publisher;
    private LiveDataService liveDataService;
    private Map<Long, Boolean> statusStore;
    private Map<Long, Long> startTimeStore;

    /**
     * 这一轮已发出的事件，按发布先后排。头一笔就抛——抛的就是本轮实际排最前的那位
     */
    private final List<Object> published = new ArrayList<>();

    /**
     * 还要抛几次：头一笔默认抛一次；两位都出错的格拨到两次
     */
    private int throwBudget = 1;

    private Runnable poll;

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger serviceLogger;

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

        doAnswer(this::publish).when(publisher).publishEvent(any(BilibiliLiveOnEvent.class));
        doAnswer(this::publish).when(publisher).publishEvent(any(BilibiliLiveOffEvent.class));

        BilibiliBackupLivePushService service = new BilibiliBackupLivePushService(
                api, new NovaBilibiliProperties(), publisher, scheduler,
                new BilibiliLiveStateGate(liveDataService), liveDataService);
        service.start(dataSource);

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(captor.capture(), any(Duration.class));
        poll = captor.getValue();

        serviceLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(BilibiliBackupLivePushService.class);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        serviceLogger.detachAppender(appender);
        appender.stop();
    }

    private Object publish(InvocationOnMock invocation) {
        Object event = invocation.getArgument(0);
        published.add(event);
        if (throwBudget > 0) {
            throwBudget--;
            throw new IllegalStateException("模拟监听器处理事件时抛错");
        }
        return null;
    }

    /**
     * 跑一轮轮询。ups 为这一轮数据源里的主播，rooms 为接口对这批 uid 的回答。
     */
    private void runRound(List<PushUser> ups, Map<Long, Room> rooms) {
        when(dataSource.getUsers(BilibiliPlatform.BILIBILI.id())).thenReturn(ups);
        when(api.getLiveInfoByUids(anySet())).thenReturn(rooms);
        poll.run();
    }

    private PushUser streamer(long uid, String uname, long roomId) {
        PushUser user = new PushUser();
        user.setUid(uid);
        user.setUname(uname);
        user.setRoomId(roomId);
        user.setPlatform(BilibiliPlatform.BILIBILI.id());
        user.setEnabled(true);
        user.setFace("https://img.example/face/" + FACE_MARK + ".jpg");
        return user;
    }

    private List<PushUser> bothStreamers() {
        return List.of(
                streamer(UID_ONE, "主播甲", ROOM_ONE),
                streamer(UID_TWO, "主播乙", ROOM_TWO));
    }

    private Room livingRoom() {
        Room room = new Room();
        room.setLiveStatus(1);
        room.setLiveStartTime(LIVE_START_SECONDS);
        return room;
    }

    private Room offlineRoom() {
        Room room = new Room();
        room.setLiveStatus(0);
        return room;
    }

    private Map<Long, Room> rooms(Room room) {
        return Map.of(UID_ONE, room, UID_TWO, room);
    }

    private String nameOf(Object event) {
        return ((NovaLiveStatusChangeEvent) event).getSource().getUname();
    }

    /**
     * 本服务在 ERROR 级记的行。INFO 里的「检测到」行另算——它证明这把夹具看得见这个 logger
     */
    private List<String> errorLines() {
        return appender.list.stream()
                .filter(event -> event.getLevel().toInt() >= ch.qos.logback.classic.Level.ERROR.toInt())
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private void assertSeesThisLogger() {
        List<String> infoLines = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("备用直播推送检测到"))
                .toList();
        assertEquals(2, infoLines.size(), "夹具应看得见这个 logger 的「检测到」行, 实际: " + infoLines);
    }

    private void assertErrorLineFor(Object failedEvent, String eventType) {
        List<String> errors = errorLines();
        assertEquals(1, errors.size(), "出错那位应恰留一条错误日志, 实际: " + errors);
        String line = errors.get(0);
        assertTrue(line.contains(nameOf(failedEvent)), "错误日志要带主播名: " + line);
        assertTrue(line.contains(eventType), "错误日志要带事件类型: " + line);
        assertFalse(line.contains(FACE_MARK), "错误日志只带主播名与事件类型, up 与事件不进这行: " + line);
    }

    @Test
    @DisplayName("排最前的那位开播监听抛错: 同轮后面的开播事件照发, 出错那位只留一条错误日志")
    void liveOnFailureOfFirstDoesNotBlockTheRestOfTheRound() {
        runRound(bothStreamers(), rooms(offlineRoom()));
        runRound(bothStreamers(), rooms(livingRoom()));

        assertEquals(2, published.size(), "第二位的开播事件应照样在本轮发出, 实际: " + published);
        assertInstanceOf(BilibiliLiveOnEvent.class, published.get(1), "后面那位发出的应是开播事件");
        assertSeesThisLogger();
        assertErrorLineFor(published.get(0), "开播");
    }

    @Test
    @DisplayName("排最前的那位下播监听抛错: 同轮后面的下播事件照发, 出错那位只留一条错误日志")
    void liveOffFailureOfFirstDoesNotBlockTheRestOfTheRound() {
        // 两位都已在播入账（按在播记账不补推），下一轮都下播, 各发下播事件
        runRound(bothStreamers(), rooms(livingRoom()));
        runRound(bothStreamers(), rooms(offlineRoom()));

        assertEquals(2, published.size(), "第二位的下播事件应照样在本轮发出, 实际: " + published);
        assertInstanceOf(BilibiliLiveOffEvent.class, published.get(1), "后面那位发出的应是下播事件");
        assertSeesThisLogger();
        assertErrorLineFor(published.get(0), "下播");
    }

    @Test
    @DisplayName("同轮两位的监听都抛错: 各留一条错误日志, 本轮照样走完")
    void failuresOfBothStillWalkTheWholeRound() {
        throwBudget = 2;

        runRound(bothStreamers(), rooms(offlineRoom()));
        runRound(bothStreamers(), rooms(livingRoom()));

        assertEquals(2, published.size(), "两位的开播事件都试过了, 实际: " + published);
        List<String> errors = errorLines();
        assertEquals(2, errors.size(), "两位各留一条错误日志, 实际: " + errors);
        assertEquals(2, errors.stream().filter(message -> message.contains("开播")).count(),
                "两条都要带事件类型: " + errors);
        assertSeesThisLogger();
    }
}
