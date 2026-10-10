package org.frostnova.nova.bilibili.service;

import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.enums.ConnectStatus;
import org.frostnova.nova.bilibili.event.live.BilibiliDisconnectedEvent;
import org.frostnova.nova.bilibili.health.BilibiliDisconnectDigest;
import org.frostnova.nova.bilibili.health.BilibiliRiskMetrics;
import org.frostnova.nova.bilibili.model.ConnectAddress;
import org.frostnova.nova.bilibili.model.ConnectInfo;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.service.LiveDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/**
 * 重连之后旧会话的回调晚到
 * <p>
 * 心跳超时后本端关掉旧会话、排一次重连；重连跑起来建好新会话时，旧会话的关闭回调可能还没到。
 * 它晚到时只能收旧会话自己的尾，不能把新会话当成断了。
 * <p>
 * 这里每次握手发一条新的会话，才分得出「哪条会话的回调」。
 */
@DisplayName("旧会话的回调晚于新会话建立")
class BilibiliLiveRoomConnectorLateCloseTest {
    private static final long ROOM_ID = 47731877194803L;

    private final List<WebSocketSession> sessions = new ArrayList<>();

    private final Map<WebSocketSession, Boolean> open = new HashMap<>();

    /** 每次 scheduleAtFixedRate 登记的任务与它返回的句柄，按登记顺序 */
    private final List<Runnable> periodicTasks = new ArrayList<>();

    private final List<ScheduledFuture<?>> periodicFutures = new ArrayList<>();

    private final List<Runnable> queuedReconnects = new ArrayList<>();

    private final List<Object> published = new ArrayList<>();

    private BilibiliLiveRoomConnector connector;

    @BeforeEach
    void setUp() {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        ConnectAddress address = new ConnectAddress();
        address.setHost("late-close.chat.bilibili.com");
        address.setPort(2243);
        address.setWssPort(443);
        address.setWsPort(2244);
        ConnectInfo info = new ConnectInfo();
        info.setToken("token");
        info.setUid(0L);
        info.setAddresses(new ArrayList<>(List.of(address)));
        when(api.getLiveRoomConnectInfo(anyLong())).thenReturn(info);

        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        doAnswer(invocation -> published.add(invocation.getArgument(0)))
                .when(publisher).publishEvent(any(Object.class));
        doAnswer(invocation -> published.add(invocation.getArgument(0)))
                .when(publisher).publishEvent(any(ApplicationEvent.class));

        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), any(Duration.class))).thenAnswer(invocation -> {
            ScheduledFuture<?> future = mock(ScheduledFuture.class);
            periodicTasks.add(invocation.getArgument(0));
            periodicFutures.add(future);
            return future;
        });

        WebSocketClient client = mock(WebSocketClient.class);
        when(client.execute(any(WebSocketHandler.class), any(WebSocketHttpHeaders.class), any(URI.class)))
                .thenAnswer(invocation -> {
                    WebSocketSession session = newSession();
                    WebSocketHandler handler = invocation.getArgument(0);
                    handler.afterConnectionEstablished(session);
                    return CompletableFuture.completedFuture(session);
                });

        BilibiliConnectGate connectGate = mock(BilibiliConnectGate.class);
        when(connectGate.submit(any(Runnable.class), any())).thenAnswer(invocation -> {
            queuedReconnects.add(invocation.getArgument(0));
            return Instant.EPOCH;
        });

        NovaBilibiliProperties properties = new NovaBilibiliProperties();
        connector = new BilibiliLiveRoomConnector(
                new LiveStreamerInfo(3000003L, "测试主播", ROOM_ID),
                api, mock(BilibiliEventParser.class), properties, publisher, scheduler, client,
                mock(BilibiliLiveStateGate.class), connectGate, mock(BilibiliRiskMetrics.class),
                new BilibiliDisconnectDigest(properties, scheduler), mock(LiveDataService.class),
                new AtomicBoolean(true));
    }

    private WebSocketSession newSession() throws IOException {
        WebSocketSession session = mock(WebSocketSession.class);
        open.put(session, true);
        when(session.getId()).thenReturn("session-" + sessions.size());
        when(session.isOpen()).thenAnswer(invocation -> open.get(session));
        doAnswer(invocation -> {
            open.put(session, false);
            return null;
        }).when(session).close();
        sessions.add(session);
        return session;
    }

    /**
     * 走到「新会话已连上、旧会话的关闭回调还没到」这一刻
     * <p>
     * 旧会话心跳超时：本端关掉它并排一次重连；重连跑完，新会话建好、认证包发出、心跳起来。
     * 超时这条路把状态改成 TIMEOUT，重连因此不必等关闭回调就能建连，正是实况里的次序。
     */
    private void reconnectBeforeOldCloseArrives() throws Exception {
        connector.connect();
        assertEquals(1, sessions.size());

        Field lastMessage = BilibiliLiveRoomConnector.class.getDeclaredField("lastMessageTime");
        lastMessage.setAccessible(true);
        lastMessage.set(connector, Instant.now().minus(Duration.ofMinutes(5)));
        heartbeatOfLatestSession().run();
        assertFalse(open.get(sessions.get(0)), "心跳超时应当关掉旧会话");
        assertEquals(1, queuedReconnects.size(), "心跳超时应当排一次重连");

        runQueuedReconnects();
        assertEquals(2, sessions.size(), "重连应当建出第二条会话");
        assertEquals(ConnectStatus.CONNECTED, connector.getStatus());
        published.clear();
    }

    /** 最近一次启动的长连接心跳。startHeartbeat 每次先登记长连接心跳、再登记观看心跳 */
    private Runnable heartbeatOfLatestSession() {
        return periodicTasks.get(periodicTasks.size() - 2);
    }

    private List<ScheduledFuture<?>> heartbeatFuturesOfLatestSession() {
        return periodicFutures.subList(periodicFutures.size() - 2, periodicFutures.size());
    }

    private static boolean cancelled(ScheduledFuture<?> future) {
        return mockingDetails(future).getInvocations().stream()
                .anyMatch(invocation -> invocation.getMethod().getName().equals("cancel"));
    }

    private void runQueuedReconnects() {
        List<Runnable> pending = new ArrayList<>(queuedReconnects);
        queuedReconnects.clear();
        pending.forEach(Runnable::run);
    }

    private long openSessions() {
        return open.values().stream().filter(Boolean::booleanValue).count();
    }

    private long disconnectedEvents() {
        return published.stream().filter(BilibiliDisconnectedEvent.class::isInstance).count();
    }

    /**
     * 抓的故障：重连后旧连接的关闭通知晚到，把新连接的心跳停了。
     * B 站约一分钟收不到心跳就断开新连接，于是又断一次、又多一段采集缺口，
     * 健康栏与断流提醒也多报一次。
     */
    @Test
    @DisplayName("⚠️ 旧会话的关闭回调晚到：新会话心跳还在、仍是已连接、不排重连、不发断线事件")
    void lateCloseOfOldSessionLeavesNewSessionAlone() throws Exception {
        reconnectBeforeOldCloseArrives();
        List<ScheduledFuture<?>> newHeartbeats = heartbeatFuturesOfLatestSession();

        connector.afterConnectionClosed(sessions.get(0), CloseStatus.NORMAL);

        List<String> reds = new ArrayList<>();
        if (newHeartbeats.stream().anyMatch(BilibiliLiveRoomConnectorLateCloseTest::cancelled)) {
            reds.add("新会话的心跳被停了");
        }
        if (connector.getStatus() != ConnectStatus.CONNECTED) {
            reds.add("状态应仍是已连接，实际 " + connector.getStatus());
        }
        if (!queuedReconnects.isEmpty()) {
            reds.add("多排了 " + queuedReconnects.size() + " 次重连");
        }
        if (disconnectedEvents() != 0) {
            reds.add("发了 " + disconnectedEvents() + " 个断线事件");
        }
        assertTrue(reds.isEmpty(), () -> String.join("; ", reds));
    }

    /**
     * 抓的故障：旧回调把状态改成已关闭并排出重连，重连再建一条会话，而当前那条没人关，
     * 两条会话同时收这个房间的消息，事件重复。
     */
    @Test
    @DisplayName("⚠️ 旧会话的关闭回调晚到之后，这个房间同时开着的会话只有一条")
    void lateCloseDoesNotLeaveTwoSessionsOpen() throws Exception {
        reconnectBeforeOldCloseArrives();

        connector.afterConnectionClosed(sessions.get(0), CloseStatus.NORMAL);
        runQueuedReconnects();

        assertEquals(1, openSessions(),
                "同一房间同时开着 " + openSessions() + " 条会话，共建过 " + sessions.size() + " 条");
    }

    /**
     * 抓的故障：旧会话报一次传输异常，就把正常的新会话标成出错，健康栏跟着报错。
     */
    @Test
    @DisplayName("旧会话晚到的传输异常不改新会话的状态")
    void lateTransportErrorOfOldSessionKeepsStatus() throws Exception {
        reconnectBeforeOldCloseArrives();

        connector.handleTransportError(sessions.get(0), new IOException("旧连接的异常"));

        assertEquals(ConnectStatus.CONNECTED, connector.getStatus());
    }

    /**
     * 阴性对照：当前会话自己关了，照旧停心跳、改状态、发断线事件、排一次重连。
     * 上面几格的判法若对谁都不动，这一格会红。
     */
    @Test
    @DisplayName("对照：当前会话的关闭回调照旧停心跳、发断线事件、排重连")
    void closeOfCurrentSessionStillReconnects() throws Exception {
        reconnectBeforeOldCloseArrives();
        List<ScheduledFuture<?>> newHeartbeats = heartbeatFuturesOfLatestSession();
        open.put(sessions.get(1), false);

        connector.afterConnectionClosed(sessions.get(1), CloseStatus.NORMAL);

        assertTrue(newHeartbeats.stream().allMatch(BilibiliLiveRoomConnectorLateCloseTest::cancelled),
                "当前会话断了，它的心跳该停");
        assertEquals(ConnectStatus.CLOSED, connector.getStatus());
        assertEquals(1, disconnectedEvents());
        assertEquals(1, queuedReconnects.size());
    }
}
