package org.frostnova.nova.adapter.onebot.service;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.adapter.onebot.config.OneBotAdapterPluginProperties;
import org.frostnova.nova.adapter.onebot.converter.OneBotMessageConverter;
import org.frostnova.nova.adapter.onebot.health.OneBotConnectionState;
import org.frostnova.nova.adapter.onebot.health.OneBotHealthProbe;
import org.frostnova.nova.adapter.onebot.http.OneBotHttpAdapter;
import org.frostnova.nova.adapter.onebot.model.OneBotSender;
import org.frostnova.nova.core.health.HealthStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Constructor;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 同一平台定时体检至多一份，重连不打断，账号按在线口径，Token 错单列
 * <p>
 * 「停旧挂新」不是一步的话，两处同时触发体检时两边都会把对方那份停掉、又各挂一份，
 * 先挂的那份从此没人能停：同一平台被反复轮询、日志翻倍，每撞一次多一份，只有重启才清得掉。
 * 连上时补的那次体检若只看取不取得到登录信息就把账号记成在线，被踢下线后一重连
 * 就会先翻回在线、下一次再翻回掉线，时间线上多出来回记录。重连比体检周期还勤时，
 * 每次重连都重新计时，定时体检一次都跑不到。Token 不对要单列：说成「连不上」
 * 会让人去查一个根本没坏的网络。
 */
@DisplayName("定时体检至多一份，重连不打断，账号按在线口径，Token 错单列")
class OneBotDetectScheduleTest {
    private static final String SENDER_NAME = "测试机器人";

    /**
     * 实现的 HTTP 口在不在。停在扫码页时 NapCat 不开端口，体检与定时检测都连不上
     */
    private final AtomicBoolean reachable = new AtomicBoolean(true);

    /**
     * 实现自身跑得好不好（{@code get_status.good}）
     */
    private final AtomicBoolean good = new AtomicBoolean(true);

    /**
     * QQ 账号在不在线（{@code get_status.online}）
     */
    private final AtomicBoolean online = new AtomicBoolean(true);

    /**
     * Token 对不对。不对时每个 HTTP 调用都回 403
     */
    private final AtomicBoolean tokenWrong = new AtomicBoolean(false);

    private RecordingState state;

    private OneBotAdapterPluginProperties properties;

    private RecordingScheduler scheduler;

    private OneBotHttpService httpService;

    private OneBotWebsocketService websocketService;

    private OneBotSender sender;

    private WebSocketSession session;

    @BeforeEach
    void setUp() throws Exception {
        state = new RecordingState();
        properties = new OneBotAdapterPluginProperties();
        // 检测周期压到 1 秒，等得起；缺省 300 秒不变
        properties.getDetect().setHttpDetectInterval(1);

        scheduler = new RecordingScheduler();
        scheduler.setPoolSize(2);
        scheduler.initialize();

        httpService = new OneBotHttpService(scheduler, inlineExecutor(), properties, stubAdapter(),
                new OneBotMessageConverter(), state);
        // 按类型现填依赖，服务多一个依赖时判据不用改
        websocketService = newWebsocketService();
        session = mock(WebSocketSession.class);

        sender = new OneBotSender();
        sender.setName(SENDER_NAME);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    @Test
    @DisplayName("两线程同时触发体检，同一平台也只留一份定时体检")
    void concurrentChecksLeaveExactlyOneDetectTask() throws Exception {
        // 周期取长，免得这一格量着量着定时体检自己跑起来添乱
        properties.getDetect().setHttpDetectInterval(300);

        for (int round = 0; round < 5; round++) {
            CountDownLatch start = new CountDownLatch(1);
            Thread first = concurrentCheck(start);
            Thread second = concurrentCheck(start);
            first.start();
            second.start();
            start.countDown();
            first.join();
            second.join();
        }

        assertEquals(1, scheduler.liveDetectTasks(),
                "同一平台任何时候至多一份定时体检，多出来的那份没人能停、会一直轮询到重启: live="
                        + scheduler.liveDetectTasks());
    }

    @Test
    @DisplayName("被踢下线后 WebSocket 重连，账号不被改回在线")
    void reconnectAfterKickDoesNotFlipAccountOnline() throws Exception {
        // 关掉定时体检，好让这一格只量「连上补体检」那条路，别让定时那趟把账号状态又写一遍
        properties.getDetect().setEnableHttpDetect(false);
        httpService.check(sender);
        assertEquals(OneBotConnectionState.Kind.OK, account().kind(), "底子: 账号在线: " + account());

        handler().handleMessage(session, botOfflineNotice("登录已失效"));
        online.set(false);
        assertEquals(OneBotConnectionState.Kind.SERVICE_ABNORMAL, account().kind(),
                "踢完就该是掉线: " + account());

        handler().afterConnectionEstablished(session);

        assertEquals(OneBotConnectionState.Kind.SERVICE_ABNORMAL, account().kind(),
                "重连不该把账号改回在线——在不在线看 get_status 的 online，不是看取不取得到登录信息: " + account());
    }

    @Test
    @DisplayName("重连比体检周期还勤，定时体检也照周期跑到")
    void frequentReconnectsStillLetDetectRun() throws Exception {
        properties.getDetect().setHttpDetectInterval(2);

        // 每 200 毫秒重连一次、连 12 趟，比 2 秒的体检周期勤得多
        for (int i = 0; i < 12; i++) {
            handler().afterConnectionEstablished(session);
            Thread.sleep(200);
        }

        assertTrue(state.httpHistory.contains("服务正常"),
                "重连再勤，定时体检也该照周期跑到（「服务正常」只有定时体检那条路写）: " + state.httpHistory);
    }

    @Test
    @DisplayName("护栏: Token 不对时健康页说的是 Token 不对，不是连不上")
    void tokenWrongSaysTokenNotUnreachable() throws Exception {
        // 本树已有「Token 不对」那一支，这一格改前即绿：护的是它别被删掉或被「连不上」盖掉
        tokenWrong.set(true);
        state.websocketConnected(SENDER_NAME);

        httpService.check(sender);
        // 等定时体检跑过一趟，它那支写下的才是被护的读数
        Thread.sleep(2500);

        HealthStatus health = new OneBotHealthProbe(state, properties, new MockEnvironment()).check();
        assertTrue(health.summary().contains("HTTP Token 不正确"),
                "Token 错时健康页该说 Token 不对: " + health.summary());
        assertTrue(!health.summary().contains("HTTP 连不上"),
                "Token 错不该说成连不上，说成连不上会让人去查一个根本没坏的网络: " + health.summary());
    }

    /**
     * 线程就绪后一起调一次体检，好让两边撞进停旧挂新
     */
    private Thread concurrentCheck(CountDownLatch start) {
        return new Thread(() -> {
            try {
                start.await();
                httpService.check(sender);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private OneBotConnectionState.Status http() {
        return state.all().get(SENDER_NAME).getHttp();
    }

    private OneBotConnectionState.Status account() {
        return state.all().get(SENDER_NAME).getAccount();
    }

    /**
     * 等一件事成立，超时即红并抄下当时的读数
     */
    private void await(String what, Supplier<Boolean> condition) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            Thread.sleep(50);
        }
        fail("等了 5 秒仍未" + what + "；http=" + http() + " account=" + account());
    }

    /**
     * 一条被踢下线的通知
     */
    private WebSocketMessage<?> botOfflineNotice(String reason) {
        JSONObject json = new JSONObject();
        json.put("post_type", "notice");
        json.put("notice_type", "bot_offline");
        json.put("reason", reason);
        return new TextMessage(json.toJSONString());
    }

    /**
     * 实现的 HTTP 口按 {@link #reachable} 开关：关着即连不上，开着再看 good 与 online；
     * {@link #tokenWrong} 打开时每个调用都回 403
     */
    private OneBotHttpAdapter stubAdapter() {
        OneBotHttpAdapter adapter = mock(OneBotHttpAdapter.class);
        @SuppressWarnings("unchecked")
        HttpClientErrorException.Forbidden wrongToken = mock(HttpClientErrorException.Forbidden.class);
        when(adapter.getVersionInfo(any(), any())).thenAnswer(invocation -> {
            if (tokenWrong.get()) {
                throw wrongToken;
            }
            requireReachable();
            return new JSONObject().fluentPut("app_version", "9.9.9");
        });
        when(adapter.getLoginInfo(any(), any())).thenAnswer(invocation -> {
            if (tokenWrong.get()) {
                throw wrongToken;
            }
            requireReachable();
            return new JSONObject().fluentPut("nickname", "test-bot").fluentPut("user_id", 1000L);
        });
        when(adapter.getStatus(any(), any())).thenAnswer(invocation -> {
            if (tokenWrong.get()) {
                throw wrongToken;
            }
            requireReachable();
            return new JSONObject().fluentPut("good", good.get()).fluentPut("online", online.get());
        });
        return adapter;
    }

    private void requireReachable() {
        if (!reachable.get()) {
            throw new IllegalStateException("connection refused");
        }
    }

    /**
     * 造一个依赖对得上真件的服务，好让真的处理器把状态写进真的状态表
     * <p>
     * 构造参数按类型现填：多一个依赖时判据照跑，免得量到的是构造器签名。
     */
    private OneBotWebsocketService newWebsocketService() throws Exception {
        Constructor<?> constructor = OneBotWebsocketService.class.getDeclaredConstructors()[0];
        Class<?>[] types = constructor.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (types[i] == ThreadPoolTaskExecutor.class) {
                args[i] = inlineExecutor();
            } else if (types[i] == OneBotConnectionState.class) {
                args[i] = state;
            } else if (types[i] == OneBotHttpService.class) {
                args[i] = httpService;
            } else {
                args[i] = mock(types[i]);
            }
        }
        constructor.setAccessible(true);
        return (OneBotWebsocketService) constructor.newInstance(args);
    }

    /**
     * 取真的处理器来驱动，不按名字反射私有构造器
     */
    private WebSocketHandler handler() {
        return websocketService.handlerFor(sender);
    }

    /**
     * 就地跑完提交进来的活，免得断言跑在处理之前
     */
    private ThreadPoolTaskExecutor inlineExecutor() {
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return CompletableFuture.completedFuture(null);
        }).when(executor).submit(any(Runnable.class));
        return executor;
    }

    /**
     * 记下写进 HTTP 状态的每一条读数
     * <p>
     * 后写的读数会盖掉先写的，只看当前值分不出定时体检到底跑没跑过。
     */
    private static final class RecordingState extends OneBotConnectionState {
        final List<String> httpHistory = new CopyOnWriteArrayList<>();

        @Override
        public void httpOk(String sender, String detail) {
            httpHistory.add(detail);
            super.httpOk(sender, detail);
        }

        @Override
        public void httpFailed(String sender, Kind kind, String detail) {
            httpHistory.add(detail);
            super.httpFailed(sender, kind, detail);
        }
    }

    /**
     * 真挂定时体检、并记下挂过的每一份
     * <p>
     * 挂之前先歇 50 毫秒，好让「停旧」与「挂新」之间的缝张开到两线程一定撞得上。
     */
    private static final class RecordingScheduler extends ThreadPoolTaskScheduler {
        private final List<ScheduledFuture<?>> scheduled = new CopyOnWriteArrayList<>();

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ScheduledFuture<?> future = super.scheduleAtFixedRate(task, startTime, period);
            scheduled.add(future);
            return future;
        }

        int liveDetectTasks() {
            int live = 0;
            for (ScheduledFuture<?> future : scheduled) {
                if (!future.isCancelled()) {
                    live++;
                }
            }
            return live;
        }
    }
}
