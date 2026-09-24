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
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
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
 * 启动时没登录也要跟着看，被踢下线立刻记掉线
 * <p>
 * 机器人账号被 QQ 踢下线时实现进程与端口都还在，推送全部失败，
 * 而界面上一直是启动那一刻的「连不上」。两处漏了：
 * 体检失败就不再挂定时体检，之后登录、掉线都看不到；被踢时那条通知没人接，
 * 账号状态要等最长一个检测周期才动。连上那一刻也不补体检，刚扫码登录完的人
 * 看着没变的旧读数，会以为扫码没成。
 */
@DisplayName("启动没登录也照挂定时体检，被踢立刻记掉线")
class OneBotOfflineAwarenessTest {
    private static final String SENDER_NAME = "测试机器人";

    /**
     * 实现的 HTTP 口在不在。停在扫码页时 NapCat 不开端口，体检与定时检测都连不上
     */
    private final AtomicBoolean reachable = new AtomicBoolean(false);

    /**
     * 实现自身跑得好不好（{@code get_status.good}）
     */
    private final AtomicBoolean good = new AtomicBoolean(true);

    /**
     * QQ 账号在不在线（{@code get_status.online}）
     */
    private final AtomicBoolean online = new AtomicBoolean(true);

    private OneBotConnectionState state;

    private OneBotAdapterPluginProperties properties;

    private ThreadPoolTaskScheduler scheduler;

    private OneBotHttpService httpService;

    private OneBotWebsocketService websocketService;

    private OneBotSender sender;

    private WebSocketSession session;

    @BeforeEach
    void setUp() throws Exception {
        state = new OneBotConnectionState();
        properties = new OneBotAdapterPluginProperties();
        // 检测周期压到 1 秒，等得起；缺省 300 秒不变
        properties.getDetect().setHttpDetectInterval(1);

        scheduler = new ThreadPoolTaskScheduler();
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
    @DisplayName("体检失败后定时体检照挂，登录与掉线三步逐一跟上")
    void detectSurvivesFailedCheckAndFollowsAccount() throws Exception {
        httpService.check(sender);
        assertEquals(OneBotConnectionState.Kind.UNREACHABLE, http().kind(),
                "体检失败那一次记下的就该是连不上: " + http());

        reachable.set(true);
        await("跟上「服务正常」", () -> http().kind() == OneBotConnectionState.Kind.OK
                && "服务正常".equals(http().detail()));
        await("跟上「账号在线」", () -> account().kind() == OneBotConnectionState.Kind.OK);

        online.set(false);
        await("跟上「账号掉线」", () -> account().kind() == OneBotConnectionState.Kind.SERVICE_ABNORMAL
                && account().detail().contains("掉线"));

        // 被踢之后重新登录了，也得由定时体检查回来
        online.set(true);
        await("把账号状态改回在线", () -> account().kind() == OneBotConnectionState.Kind.OK);
    }

    @Test
    @DisplayName("阴性对照：关掉 HTTP 检测时不挂定时体检")
    void detectNotHungWhenHttpDetectDisabled() throws Exception {
        properties.getDetect().setEnableHttpDetect(false);

        httpService.check(sender);
        assertEquals(OneBotConnectionState.Kind.UNREACHABLE, http().kind());

        reachable.set(true);
        Thread.sleep(3000);

        assertEquals(OneBotConnectionState.Kind.UNREACHABLE, http().kind(),
                "关着 HTTP 检测就该不挂定时体检，状态不该被后来的读数刷掉: " + http());
    }

    @Test
    @DisplayName("被踢下线的通知一到就记掉线，不等下一次定时体检")
    void botOfflineNoticeMarksAccountOfflineWithReason() throws Exception {
        // 底子：实现在跑、账号在线。缺了 HTTP 那一格，探针会先按「连不上」判，量不到账号这条
        state.httpOk(SENDER_NAME, "服务正常");
        state.accountOnline(SENDER_NAME, "在线");

        handler().handleMessage(session, botOfflineNotice("登录已失效"));

        assertEquals(OneBotConnectionState.Kind.SERVICE_ABNORMAL, account().kind(),
                "通知一到就该走掉线那个状态入口: " + account());
        assertTrue(account().detail().contains("登录已失效"),
                "通知里的原因要写进状态说明: " + account());

        HealthStatus health = new OneBotHealthProbe(state, properties, new MockEnvironment()).check();
        assertEquals(HealthStatus.Level.DOWN, health.level(), health.summary());
        assertEquals("account", health.reason(), health.summary());
    }

    @Test
    @DisplayName("连上那一刻就补体检，健康页不留上一次的旧读数")
    void websocketConnectRefreshesStaleReading() throws Exception {
        // 关掉定时体检，好让这一格只量「连上那一刻」那条路，别让定时那趟先把读数刷了
        properties.getDetect().setEnableHttpDetect(false);

        httpService.check(sender);
        assertEquals(OneBotConnectionState.Kind.UNREACHABLE, http().kind(),
                "底子: 启动那次体检失败，旧读数是连不上: " + http());

        reachable.set(true);
        handler().afterConnectionEstablished(session);

        assertEquals(OneBotConnectionState.Kind.OK, http().kind(),
                "连上那一刻就该补一次体检: " + http());
        assertEquals(OneBotConnectionState.Kind.OK, account().kind(),
                "同一趟体检也把账号状态改回来: " + account());
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
     * 实现的 HTTP 口按 {@link #reachable} 开关：关着即连不上，开着再看 good 与 online
     */
    private OneBotHttpAdapter stubAdapter() {
        OneBotHttpAdapter adapter = mock(OneBotHttpAdapter.class);
        when(adapter.getVersionInfo(any(), any())).thenAnswer(invocation -> {
            requireReachable();
            return new JSONObject().fluentPut("app_version", "9.9.9");
        });
        when(adapter.getLoginInfo(any(), any())).thenAnswer(invocation -> {
            requireReachable();
            return new JSONObject().fluentPut("nickname", "test-bot").fluentPut("user_id", 1000L);
        });
        when(adapter.getStatus(any(), any())).thenAnswer(invocation -> {
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
}
