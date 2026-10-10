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

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code get_status} 的 good 不只说实现自身跑得好不好：OneBot 11 里它含「QQ 在线」，
 * 账号掉线时实现照规范回 good=false、online=false。
 *
 * <h2>这里守的是什么</h2>
 * <ul>
 * <li>账号掉线被记成「HTTP 服务异常」时，健康页把人往查实现、查网络上引，
 *     「去重新扫码登录」那条建议反而走不到——接口明明通着。</li>
 * <li>实现没报 good、或状态查询的回包没有 data，同样只是「取不到」，不是「坏了」：
 *     记成异常或连不上的话，健康页一直红，怎么查都查不出毛病。</li>
 * <li>反过来，账号在线（或没报 online）而 good=false 是实现真的病了，要照报不误。</li>
 * </ul>
 * 体检与定时体检量的是同一副判法，两条路都得过这里的格。
 */
@DisplayName("get_status 判法：账号掉线不记服务异常，good 缺席不臆断，没回内容不记连不上")
class OneBotHttpServiceStatusVerdictTest {
    private static final String SENDER_NAME = "qq-onebot";

    private OneBotConnectionState state;

    private OneBotAdapterPluginProperties properties;

    private OneBotSender sender;

    private ThreadPoolTaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        state = new OneBotConnectionState();
        properties = new OneBotAdapterPluginProperties();
        // 除定时那一格外，这里量的是体检那一刻的读数，定期检测只会把结论冲掉
        properties.getDetect().setEnableHttpDetect(false);

        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.initialize();

        sender = new OneBotSender();
        sender.setName(SENDER_NAME);
        sender.setOneBotAddress("127.0.0.1");
        sender.setOneBotHttpPort(3000);
        sender.setOneBotHttpToken("token");
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    /**
     * 用户故障：QQ 账号掉线时，健康页把 HTTP 一栏报成「服务异常」，建议让人去查实现与网络；
     * 真正该给的「重新扫码登录」那条建议被顶掉，接口明明通着。
     */
    @Test
    @DisplayName("⚠️ 账号掉线(good=false, online=false): HTTP 记正常, 健康页给的建议是去重新扫码")
    void accountOfflineIsNotHttpServiceAbnormal() {
        serviceWith(adapterOf(false, false)).check(sender);

        OneBotConnectionState.Status http = http();
        assertEquals(OneBotConnectionState.Kind.OK, http.kind(),
                "掉线的是账号不是接口, HTTP 一栏该记正常: " + http);
        assertEquals("接口正常，QQ 账号已掉线", http.detail(), http.toString());
        assertEquals(OneBotConnectionState.Kind.SERVICE_ABNORMAL, account().kind(),
                "掉线由账号那一栏记: " + account());

        HealthStatus health = probe().check();
        assertEquals("account", health.reason(),
                "健康页该按账号掉线归类, 不是按连不上: " + health);
        assertTrue(health.advice().contains("重新扫码登录"),
                "建议该是去 OneBot 实现的界面重新扫码登录: " + health);
        assertTrue(!health.summary().contains("服务异常") && !health.advice().contains("实现自身状态异常"),
                "不该再把人往「实现自身状态异常」上引: " + health);
    }

    /**
     * 一张表守住整副判法：good=false 时只有账号还在线（或没报 online）才是实现自身异常；
     * good 没报不臆断。修过头（连真异常也吞成正常）或修不足（掉线仍记异常）这格都会红。
     */
    @Test
    @DisplayName("get_status 判法一表: 掉线记正常, good 缺席不臆断, 真异常照报")
    void statusVerdictTable() {
        assertHttpKind(OneBotConnectionState.Kind.OK, adapterOf(true, true), "good=true");
        assertHttpKind(OneBotConnectionState.Kind.OK, adapterOf(false, false), "账号掉线");
        assertHttpKind(OneBotConnectionState.Kind.SERVICE_ABNORMAL, adapterOf(false, true),
                "账号在线而实现报异常");
        assertHttpKind(OneBotConnectionState.Kind.SERVICE_ABNORMAL, adapterOf(false, null),
                "实现报异常且未报 online");
        assertHttpKind(OneBotConnectionState.Kind.OK, adapterOf(null, true), "未上报 good");
    }

    /**
     * 用户故障：实现不上报 get_status.good（并非所有实现都报）时，HTTP 一栏一直显示
     * 「服务异常」，健康页常年红，使用者照着提示查什么都查不出毛病。
     */
    @Test
    @DisplayName("⚠️ 实现未上报 good: HTTP 不再记异常, 账号照旧看 online")
    void missingGoodIsNotAbnormal() {
        serviceWith(adapterOf(null, true)).check(sender);

        OneBotConnectionState.Status http = http();
        assertEquals(OneBotConnectionState.Kind.OK, http.kind(),
                "实现没报 good 只是取不到, 不是坏了, 不要臆断成异常: " + http);
        assertEquals("该 OneBot 实现未上报整体状态", http.detail(), http.toString());
        assertEquals(OneBotConnectionState.Kind.OK, account().kind(),
                "账号在不在线照旧看 online, 不跟着 good 走: " + account());
    }

    /**
     * 用户故障：状态查询的回包没有 data 时，版本与登录信息明明都取到了、接口通着，
     * 健康页却显示「连不上」，让人去查一个根本没断的网络。
     */
    @Test
    @DisplayName("⚠️ 状态查询没回 data: 不记连不上, HTTP 记正常, 账号记未知")
    void statusWithoutDataIsNotUnreachable() {
        serviceWith(adapterReturning(null)).check(sender);

        OneBotConnectionState.Status http = http();
        assertEquals(OneBotConnectionState.Kind.OK, http.kind(),
                "回包没有 data 不等于连不上: " + http);
        assertEquals("该 OneBot 实现的状态查询没有返回内容", http.detail(), http.toString());
        assertEquals(OneBotConnectionState.Kind.UNKNOWN, account().kind(),
                "状态取不到时账号记未知, 不臆断: " + account());

        HealthStatus health = probe().check();
        assertTrue(health.summary().contains("HTTP 正常"), "HTTP 一栏该显示正常: " + health);
        assertTrue(!health.summary().contains("连不上") && !health.advice().contains("连不上"),
                "健康页不该说连不上: " + health);
        assertEquals("", health.reason(), "不该按连不上归类: " + health);
    }

    /**
     * 用户故障：掉线发生在两次体检之间时靠定时体检翻页；它若另用一副判法，
     * 好不容易对了的读数又会被写回「服务异常」。
     */
    @Test
    @DisplayName("⚠️ 定时体检同一副判法: 账号掉线不把 HTTP 写成服务异常")
    void scheduledDetectUsesSameVerdict() throws Exception {
        properties.getDetect().setEnableHttpDetect(true);
        properties.getDetect().setHttpDetectInterval(1);

        RecordingState recording = new RecordingState();
        OneBotHttpService service = new OneBotHttpService(scheduler, inlineExecutor(), properties,
                adapterOf(false, false), new OneBotMessageConverter(), recording);

        // 体检先写一笔，定时体检在后面每秒一趟；「已掉线」那句话两条路都会写
        service.check(sender);

        await("定时体检也按「接口正常，QQ 账号已掉线」记 HTTP",
                () -> recording.httpHistory.stream().filter(detail -> detail.contains("已掉线")).count() >= 2,
                recording);

        assertEquals(OneBotConnectionState.Kind.SERVICE_ABNORMAL, recording.all().get(SENDER_NAME).getAccount().kind(),
                "定时体检同样要把账号记成掉线: " + recording.all().get(SENDER_NAME).getAccount());
    }

    private OneBotHttpService serviceWith(OneBotHttpAdapter http) {
        return new OneBotHttpService(scheduler, inlineExecutor(), properties, http, new OneBotMessageConverter(), state);
    }

    /**
     * 版本、登录信息都取得到；{@code get_status} 的回包按入参拼，
     * good 或 online 传 null 即该字段缺席，整个回包传 null 即没有 data
     */
    private OneBotHttpAdapter adapterOf(Boolean good, Boolean online) {
        JSONObject status = new JSONObject();
        if (good != null) {
            status.put("good", good);
        }
        if (online != null) {
            status.put("online", online);
        }
        return adapterReturning(status);
    }

    private OneBotHttpAdapter adapterReturning(JSONObject status) {
        OneBotHttpAdapter http = mock(OneBotHttpAdapter.class);
        when(http.getVersionInfo(any(), any()))
                .thenReturn(new JSONObject().fluentPut("app_version", "1.2.3"));
        when(http.getLoginInfo(any(), any()))
                .thenReturn(new JSONObject().fluentPut("nickname", "bot").fluentPut("user_id", 10001L));
        when(http.getStatus(any(), any())).thenReturn(status);
        return http;
    }

    private OneBotHealthProbe probe() {
        return new OneBotHealthProbe(state, properties, new MockEnvironment());
    }

    private void assertHttpKind(OneBotConnectionState.Kind expected, OneBotHttpAdapter adapter, String what) {
        serviceWith(adapter).check(sender);
        assertEquals(expected, http().kind(), what + "时 HTTP 一栏: " + http());
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
    private void await(String what, Supplier<Boolean> condition, RecordingState recording) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            Thread.sleep(50);
        }
        fail("等了 5 秒仍未" + what + "；httpHistory=" + recording.httpHistory);
    }

    /**
     * 就地跑完提交进来的活，免得断言跑在定时体检之前
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
     * 后写的读数会盖掉先写的，只看当前值分不出定时体检到底跑没跑、写的是什么。
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
}
