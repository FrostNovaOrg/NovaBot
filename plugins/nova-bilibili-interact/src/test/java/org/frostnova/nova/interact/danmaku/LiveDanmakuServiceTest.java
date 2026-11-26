package org.frostnova.nova.interact.danmaku;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.exception.NetworkException;
import org.frostnova.nova.bilibili.exception.RiskCooldownException;
import org.frostnova.nova.bilibili.service.BilibiliAccountService;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.timeline.TimelineEventType;
import org.frostnova.nova.interact.config.InteractProperties;
import org.frostnova.nova.interact.config.InteractRuntimeAppliers;
import org.frostnova.nova.interact.speaker.BilibiliSpeakerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 发弹幕服务
 * <p>
 * 两个账号都用替身：发言账号那一侧替的是 {@link BilibiliSpeakerService}，监听账号那一侧替的是监听账号的账号服务
 * 与它那一个接口工具。每一格写明抓的是哪种用户会遇到的故障。沙箱连不上平台，回包都是照公开资料描述造的。
 */
@DisplayName("发弹幕服务")
class LiveDanmakuServiceTest {
    private static final long ROOM = 1001L;

    private static final long STREAMER = 501L;

    private static final Instant T0 = Instant.parse("2026-11-26T12:00:00Z");

    private InteractProperties properties;

    private BilibiliSpeakerService speaker;

    private BilibiliAccountService listener;

    private BilibiliApiUtil listenerApi;

    private AbstractDataSource source;

    private final List<TimelineEvent> events = new CopyOnWriteArrayList<>();

    private final AtomicReference<Instant> now = new AtomicReference<>(T0);

    private LiveDanmakuService service;

    @BeforeEach
    void setUp() {
        properties = new InteractProperties();
        speaker = mock(BilibiliSpeakerService.class);
        listener = mock(BilibiliAccountService.class);
        listenerApi = mock(BilibiliApiUtil.class);
        source = mock(AbstractDataSource.class);
        PushUser user = new PushUser();
        user.setPlatform("bilibili");
        user.setUid(STREAMER);
        user.setRoomId(ROOM);
        user.setUname("示例主播");
        user.setEnabled(true);
        when(source.getUsers("bilibili")).thenReturn(List.of(user));
        service = new LiveDanmakuService(properties, speaker, listener, listenerApi, source, events::add);
        service.setClock(now::get);
    }

    private void speakerReady() {
        when(speaker.unusable()).thenReturn(Optional.empty());
        when(speaker.uid()).thenReturn(Optional.of(7L));
        when(speaker.get(anyString(), isNull())).thenReturn(limit(20));
        when(speaker.postOnce(anyString(), anyMap())).thenReturn(ok());
    }

    private void listenerReady() {
        when(listener.isAnonymous()).thenReturn(false);
        when(listener.isLoggedIn()).thenReturn(true);
        when(listener.getLoginUid()).thenReturn(42L);
        when(listenerApi.requestBilibiliApi(anyString())).thenReturn(limit(20));
        when(listenerApi.postFormOnce(anyString(), anyMap())).thenReturn(ok());
    }

    private void choose(DanmakuAccountChoice choice) {
        new InteractRuntimeAppliers(properties).batchApplier().apply()
                .accept(Map.of(InteractRuntimeAppliers.ACCOUNT, choice.value()));
    }

    private static JSONObject ok() {
        return JSONObject.parseObject("{\"code\":0,\"data\":{\"mode_info\":{}},\"message\":\"\",\"msg\":\"\"}");
    }

    private static JSONObject reply(int code, String message) {
        JSONObject body = new JSONObject();
        body.put("code", code);
        body.put("message", message);
        body.put("msg", message);
        body.put("data", List.of());
        return body;
    }

    private static JSONObject limit(int length) {
        return JSONObject.parseObject("{\"property\":{\"danmu\":{\"length\":" + length + ",\"color\":16777215}}}");
    }

    private void later(Duration duration) {
        now.set(now.get().plus(duration));
    }

    private TimelineEvent lastEvent() {
        assertFalse(events.isEmpty(), "这一次发送没有记进时间线");
        return events.get(events.size() - 1);
    }

    /**
     * 抓的用户故障：只是装了插件、打开了页面，程序就往直播间发了东西，或者拿账号去问了平台
     */
    @Test
    @DisplayName("没人点试发：看状态、列主播都不发请求")
    void nothingIsSentWithoutSend() {
        speakerReady();
        service.accountStatus();
        service.rooms();
        choose(DanmakuAccountChoice.LISTENER);
        listenerReady();
        service.accountStatus();

        verify(speaker, never()).postOnce(anyString(), anyMap());
        verify(speaker, never()).get(anyString(), any());
        verify(listenerApi, never()).postFormOnce(anyString(), anyMap());
        verify(listenerApi, never()).requestBilibiliApi(anyString());
        assertTrue(events.isEmpty());
    }

    /**
     * 抓的用户故障：选的是单独的发言账号，弹幕却经监听账号发了出去，风险落到了监听账号头上
     */
    @Test
    @DisplayName("选单独账号、发言账号已登录：经发言账号发出，表单照接口要求填，时间线记一条已发出")
    void speakerSendsWhenChosen() {
        speakerReady();

        DanmakuResult result = service.send(ROOM, "  大家晚上好  ");

        assertTrue(result.sent(), result.message());
        assertNull(result.failure());
        assertEquals("大家晚上好", result.text());
        assertEquals(DanmakuAccountChoice.SPEAKER, result.account());
        verify(speaker).postOnce(eq(LiveDanmakuService.SEND_URL), argThat(form -> form.get("roomid").equals(ROOM)
                && "大家晚上好".equals(form.get("msg")) && form.get("rnd").equals(T0.getEpochSecond())
                && form.get("fontsize").equals(25) && form.get("color").equals(16777215)
                && form.get("mode").equals(1) && form.get("bubble").equals(0)
                && !form.containsKey("csrf")));
        verifyNoInteractions(listenerApi);

        TimelineEvent event = lastEvent();
        assertEquals(TimelineEventType.DANMAKU_SENT, event.type());
        assertEquals("示例主播", event.streamer());
        assertEquals("大家晚上好", event.detail().get("danmaku"));
        assertEquals("SENT", event.detail().get("outcome"));
        assertEquals(String.valueOf(STREAMER), event.detail().get("streamerUid"));
        assertEquals(String.valueOf(ROOM), event.detail().get("room"));
    }

    /**
     * 抓的用户故障：没登录发言账号时用监听账号把弹幕发出去了
     */
    @Test
    @DisplayName("选单独账号、发言账号没登录：一个请求都不发，说明去连接页登录")
    void speakerNotLoggedInSendsNothing() {
        when(speaker.unusable()).thenReturn(Optional.of(new BilibiliSpeakerService.Unusable(
                BilibiliSpeakerService.UnusableKind.NEVER_LOGGED_IN, "还没有登录发言账号")));
        when(speaker.uid()).thenReturn(Optional.empty());
        listenerReady();

        DanmakuResult result = service.send(ROOM, "你好");

        assertFalse(result.sent());
        assertEquals(DanmakuFailure.NO_ACCOUNT, result.failure());
        assertTrue(result.message().contains("连接") && result.message().contains("登录"), result.message());
        verify(speaker, never()).postOnce(anyString(), anyMap());
        verify(speaker, never()).get(anyString(), any());
        verifyNoInteractions(listenerApi);
        assertEquals(TimelineEventType.DANMAKU_NOT_SENT, lastEvent().type());
        assertEquals("NO_ACCOUNT", lastEvent().detail().get("outcome"));

        when(speaker.unusable()).thenReturn(Optional.of(new BilibiliSpeakerService.Unusable(
                BilibiliSpeakerService.UnusableKind.EXPIRED, "发言账号的登录已失效")));
        assertEquals(DanmakuFailure.ACCOUNT_UNAVAILABLE, service.send(ROOM, "你好").failure());
        verify(speaker, never()).postOnce(anyString(), anyMap());
    }

    /**
     * 抓的用户故障：选了「同一个账号」，请求却另起一份凭据或走了发言账号
     */
    @Test
    @DisplayName("选同一个账号、监听账号已登录：经监听账号那一个接口工具发出，发言账号一下不碰")
    void listenerSendsThroughItsOwnApi() {
        choose(DanmakuAccountChoice.LISTENER);
        listenerReady();

        DanmakuResult result = service.send(ROOM, "主播好");

        assertTrue(result.sent(), result.message());
        assertEquals(DanmakuAccountChoice.LISTENER, result.account());
        verify(listenerApi).postFormOnce(eq(LiveDanmakuService.SEND_URL),
                argThat(form -> "主播好".equals(form.get("msg")) && !form.containsKey("csrf")));
        verify(listenerApi).requestBilibiliApi(LiveDanmakuService.USER_ROOM_INFO_URL + ROOM);
        verifyNoInteractions(speaker);
        assertEquals("监听账号", lastEvent().detail().get("account"));
    }

    /**
     * 抓的用户故障：监听账号开着免登录、或登录已失效，程序照样拿空凭据去发
     */
    @Test
    @DisplayName("选同一个账号、监听账号免登录或已失效：不发请求，说明原因")
    void listenerAnonymousOrExpiredSendsNothing() {
        choose(DanmakuAccountChoice.LISTENER);
        when(listener.isAnonymous()).thenReturn(true);

        DanmakuResult anonymous = service.send(ROOM, "你好");
        assertEquals(DanmakuFailure.NO_ACCOUNT, anonymous.failure());
        assertTrue(anonymous.message().contains("免登录"), anonymous.message());

        when(listener.isAnonymous()).thenReturn(false);
        when(listener.isLoggedIn()).thenReturn(false);
        DanmakuResult expired = service.send(ROOM, "你好");
        assertEquals(DanmakuFailure.ACCOUNT_UNAVAILABLE, expired.failure());
        assertTrue(expired.message().contains("失效"), expired.message());

        verifyNoInteractions(listenerApi);
        verifyNoInteractions(speaker);
    }

    /**
     * 抓的用户故障：改了选项要重启才生效，改完马上试发用的还是旧账号
     */
    @Test
    @DisplayName("改了选项马上试发：用新选的账号，不用重启")
    void choiceTakesEffectImmediately() {
        speakerReady();
        listenerReady();

        assertEquals(DanmakuAccountChoice.SPEAKER, service.send(ROOM, "第一条").account());
        choose(DanmakuAccountChoice.LISTENER);
        later(Duration.ofSeconds(1));
        DanmakuResult second = service.send(ROOM, "第二条");

        assertTrue(second.sent(), second.message());
        assertEquals(DanmakuAccountChoice.LISTENER, second.account());
        verify(speaker, times(1)).postOnce(anyString(), anyMap());
        verify(listenerApi, times(1)).postFormOnce(anyString(), anyMap());
    }

    /**
     * 抓的用户故障：超长的弹幕被平台吞掉、或被程序悄悄截断了一半发出去
     */
    @Test
    @DisplayName("超过房间字数上限：不发请求，说明上限；上限按房间和账号缓存半小时")
    void tooLongIsRefusedBeforeSending() {
        speakerReady();
        when(speaker.get(anyString(), isNull())).thenReturn(limit(10));

        DanmakuResult result = service.send(ROOM, "一二三四五六七八九十壹");

        assertEquals(DanmakuFailure.TOO_LONG, result.failure());
        assertTrue(result.message().contains("最多 10 个字") && result.message().contains("11 个字"), result.message());
        verify(speaker, never()).postOnce(anyString(), anyMap());

        assertTrue(service.send(ROOM, "一二三四五六七八九十").sent());
        verify(speaker, times(1)).get(LiveDanmakuService.USER_ROOM_INFO_URL + ROOM, null);
        later(Duration.ofMinutes(31));
        assertTrue(service.send(ROOM, "😀😀😀").sent(), "表情一个算一个字");
        verify(speaker, times(2)).get(LiveDanmakuService.USER_ROOM_INFO_URL + ROOM, null);
    }

    /**
     * 抓的用户故障：取不到上限时要么一律不让发，要么不设上限、发出去被平台拒
     */
    @Test
    @DisplayName("取不到字数上限：按 20 字算")
    void missingLimitFallsBackToTwenty() {
        speakerReady();
        when(speaker.get(anyString(), isNull())).thenThrow(new NetworkException("网络不通"));

        DanmakuResult tooLong = service.send(ROOM, "一".repeat(21));
        assertEquals(DanmakuFailure.TOO_LONG, tooLong.failure());
        assertTrue(tooLong.message().contains("按 20 字算"), tooLong.message());
        assertEquals(new LiveDanmakuService.LengthLimit(20, false), service.lengthLimit(ROOM));
        assertTrue(service.send(ROOM, "一".repeat(20)).sent());
    }

    /**
     * 抓的用户故障：空的、只有空白的、两行的弹幕被发了出去
     */
    @Test
    @DisplayName("空文字、只有空白、含换行：拒绝并说明，不发请求")
    void invalidTextIsRefused() {
        speakerReady();

        for (String text : new String[]{"", "   ", null, "第一行\n第二行", "回\r车"}) {
            DanmakuResult result = service.send(ROOM, text);
            assertEquals(DanmakuFailure.INVALID_TEXT, result.failure(), String.valueOf(text));
            assertFalse(result.message().isBlank());
        }
        assertTrue(service.send(ROOM, "  前后有空格也行  ").sent());
        verify(speaker, times(1)).postOnce(anyString(), anyMap());
    }

    /**
     * 抓的用户故障：3 秒内连发两条，第二条也发了出去，招来平台限流
     */
    @Test
    @DisplayName("3 秒内同一账号连发两条：第二条不发请求，回「发得太快」；过了间隔照常")
    void secondWithinIntervalIsTooFast() {
        speakerReady();

        assertTrue(service.send(ROOM, "第一条").sent());
        later(Duration.ofMillis(2_500));
        DanmakuResult second = service.send(ROOM, "第二条");
        assertEquals(DanmakuFailure.TOO_FAST, second.failure());
        assertTrue(second.message().contains("3 秒"), second.message());
        verify(speaker, times(1)).postOnce(anyString(), anyMap());

        later(Duration.ofMillis(600));
        assertTrue(service.send(ROOM, "第三条").sent());

        // 间隔是可改的设置
        new InteractRuntimeAppliers(properties).batchApplier().apply()
                .accept(Map.of(InteractRuntimeAppliers.MIN_INTERVAL, "10"));
        later(Duration.ofSeconds(5));
        assertEquals(DanmakuFailure.TOO_FAST, service.send(ROOM, "第四条").failure());
        verify(speaker, times(2)).postOnce(anyString(), anyMap());
    }

    /**
     * 抓的用户故障：点一次试发发出去两条（两个请求几乎同时到）
     */
    @Test
    @DisplayName("两个请求同时到：只发一条")
    void concurrentSendsOnlyOneGoesOut() throws Exception {
        speakerReady();
        CountDownLatch inFlight = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(speaker.postOnce(anyString(), anyMap())).thenAnswer(invocation -> {
            inFlight.countDown();
            assertTrue(release.await(20, TimeUnit.SECONDS));
            return ok();
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<DanmakuResult> first = pool.submit(() -> service.send(ROOM, "同一条"));
            assertTrue(inFlight.await(20, TimeUnit.SECONDS), "第一条没发出去");
            DanmakuResult second = pool.submit(() -> service.send(ROOM, "同一条")).get(20, TimeUnit.SECONDS);
            release.countDown();
            assertTrue(first.get(20, TimeUnit.SECONDS).sent());
            assertEquals(DanmakuFailure.TOO_FAST, second.failure());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        verify(speaker, times(1)).postOnce(anyString(), anyMap());
    }

    /**
     * 抓的用户故障：平台已经说太频繁了，程序还在继续发
     */
    @Test
    @DisplayName("平台回太频繁：这次没发出，暂停期内不再发请求并说明还要等多久，到点恢复")
    void tooFrequentPausesTheAccount() {
        speakerReady();
        when(speaker.postOnce(anyString(), anyMap())).thenReturn(reply(10031, "您发送弹幕的频率过快"), ok());

        DanmakuResult first = service.send(ROOM, "第一条");
        assertFalse(first.sent());
        assertEquals(DanmakuFailure.TOO_FAST, first.failure());
        assertEquals(10031, first.platformCode());
        assertTrue(first.message().contains("暂停约 5 分钟"), first.message());

        later(Duration.ofMinutes(2));
        DanmakuResult paused = service.send(ROOM, "第二条");
        assertEquals(DanmakuFailure.PAUSED, paused.failure());
        assertTrue(paused.message().contains("还要等约 3 分钟"), paused.message());
        assertEquals(Duration.ofMinutes(3), service.accountStatus().pausedFor());
        verify(speaker, times(1)).postOnce(anyString(), anyMap());

        later(Duration.ofMinutes(3));
        assertTrue(service.send(ROOM, "第三条").sent());
        assertNull(service.accountStatus().pausedFor());
    }

    /**
     * 抓的用户故障：被风控拦下之后程序接着发，越发越被限制
     */
    @Test
    @DisplayName("风控码与接口冷却：算没发出，暂停时长按设置")
    void riskSignalsPause() {
        speakerReady();
        new InteractRuntimeAppliers(properties).batchApplier().apply()
                .accept(Map.of(InteractRuntimeAppliers.PAUSE_MINUTES, "10"));
        when(speaker.postOnce(anyString(), anyMap())).thenReturn(reply(-412, "请求被拦截"));

        DanmakuResult blocked = service.send(ROOM, "一条");
        assertEquals(DanmakuFailure.RISK_CONTROL, blocked.failure());
        assertTrue(blocked.message().contains("-412") && blocked.message().contains("10 分钟"), blocked.message());
        later(Duration.ofMinutes(9));
        assertEquals(DanmakuFailure.PAUSED, service.send(ROOM, "又一条").failure());
        verify(speaker, times(1)).postOnce(anyString(), anyMap());

        later(Duration.ofMinutes(2));
        doThrow(new RiskCooldownException(LiveDanmakuService.SEND_URL, "请求被风控拦下，约 3 分钟后再试"))
                .when(speaker).postOnce(anyString(), anyMap());
        assertEquals(DanmakuFailure.RISK_CONTROL, service.send(ROOM, "再一条").failure());
        later(Duration.ofSeconds(5));
        assertEquals(DanmakuFailure.PAUSED, service.send(ROOM, "还一条").failure());
    }

    /**
     * 抓的用户故障：被屏蔽词拦下的弹幕平台回的是 code 0，界面上却显示「已发出」
     */
    @Test
    @DisplayName("code 为 0 但带屏蔽提示：算没发出，分类为被屏蔽词拦下；太快、重复另归一类")
    void zeroCodeWithHintIsNotSent() {
        speakerReady();
        when(speaker.postOnce(anyString(), anyMap()))
                .thenReturn(reply(0, "f"), reply(0, "k"), reply(0, "msg in 1s"), reply(0, "msg repeat"));

        DanmakuResult f = service.send(ROOM, "一条");
        assertFalse(f.sent());
        assertEquals(DanmakuFailure.BLOCKED_WORD, f.failure());
        assertEquals("f", f.platformMessage());
        later(Duration.ofSeconds(3));
        assertEquals(DanmakuFailure.BLOCKED_WORD, service.send(ROOM, "二条").failure());
        later(Duration.ofSeconds(3));
        assertEquals(DanmakuFailure.TOO_FAST, service.send(ROOM, "三条").failure());
        later(Duration.ofSeconds(3));
        assertEquals(DanmakuFailure.TOO_FAST, service.send(ROOM, "四条").failure());
        assertEquals("BLOCKED_WORD", events.get(0).detail().get("outcome"));
        assertEquals(TimelineEventType.DANMAKU_NOT_SENT, events.get(0).type());
    }

    /**
     * 抓的用户故障：认不出的回包被当成发出了；或者平台说了什么使用者完全看不到
     */
    @Test
    @DisplayName("认不出的回包：算没发出，带出平台的代码和提示")
    void unknownReplyCarriesCodeAndMessage() {
        speakerReady();
        when(speaker.postOnce(anyString(), anyMap())).thenReturn(reply(12345, "某种没见过的错误"),
                reply(0, "奇怪的提示"), JSONObject.parseObject("{\"data\":{}}"));

        DanmakuResult unknown = service.send(ROOM, "一条");
        assertEquals(DanmakuFailure.PLATFORM_ERROR, unknown.failure());
        assertEquals(12345, unknown.platformCode());
        assertTrue(unknown.message().contains("12345") && unknown.message().contains("某种没见过的错误"), unknown.message());
        assertEquals("12345", lastEvent().detail().get("code"));

        later(Duration.ofSeconds(3));
        DanmakuResult oddHint = service.send(ROOM, "二条");
        assertEquals(DanmakuFailure.PLATFORM_ERROR, oddHint.failure());
        assertTrue(oddHint.message().contains("奇怪的提示"), oddHint.message());

        later(Duration.ofSeconds(3));
        assertEquals(DanmakuFailure.PLATFORM_ERROR, service.send(ROOM, "三条").failure());

        later(Duration.ofSeconds(3));
        when(speaker.postOnce(anyString(), anyMap())).thenReturn(reply(-101, "账号未登录"));
        assertEquals(DanmakuFailure.ACCOUNT_UNAVAILABLE, service.send(ROOM, "四条").failure());
        later(Duration.ofSeconds(3));
        when(speaker.postOnce(anyString(), anyMap())).thenReturn(reply(-111, "csrf 校验失败"));
        assertEquals(DanmakuFailure.ACCOUNT_UNAVAILABLE, service.send(ROOM, "五条").failure());
        later(Duration.ofSeconds(3));
        when(speaker.postOnce(anyString(), anyMap())).thenReturn(reply(1003212, "超出限制长度"));
        assertEquals(DanmakuFailure.TOO_LONG, service.send(ROOM, "六条").failure());
    }

    /**
     * 抓的用户故障：请求超时后程序自己重发，平台其实收到了第一条，直播间里出现两条
     */
    @Test
    @DisplayName("请求超时：不确定是否发出，不重试；连不上平台：网络失败")
    void timeoutIsUncertainAndNotRetried() {
        speakerReady();
        doThrow(new NetworkException("请求时发生网络异常",
                new ResourceAccessException("I/O error", new HttpTimeoutException("request timed out"))))
                .when(speaker).postOnce(anyString(), anyMap());

        DanmakuResult timeout = service.send(ROOM, "一条");
        assertFalse(timeout.sent());
        assertTrue(timeout.uncertain());
        assertEquals(DanmakuFailure.UNCERTAIN, timeout.failure());
        assertTrue(timeout.message().contains("不会自动重发"), timeout.message());
        assertEquals(TimelineEventType.DANMAKU_UNCERTAIN, lastEvent().type());
        verify(speaker, times(1)).postOnce(anyString(), anyMap());

        later(Duration.ofSeconds(3));
        doThrow(new NetworkException("请求时发生网络异常",
                new ResourceAccessException("I/O error", new ConnectException("Connection refused"))))
                .when(speaker).postOnce(anyString(), anyMap());
        DanmakuResult offline = service.send(ROOM, "二条");
        assertEquals(DanmakuFailure.NETWORK, offline.failure());
        assertFalse(offline.uncertain());
        verify(speaker, times(2)).postOnce(anyString(), anyMap());
    }

    /**
     * 抓的用户故障：往一个不认识的房间发了弹幕
     */
    @Test
    @DisplayName("房间号不属于任何已添加的主播：拒绝，不发请求")
    void unknownRoomIsRefused() {
        speakerReady();

        DanmakuResult result = service.send(999_999L, "你好");

        assertEquals(DanmakuFailure.ROOM_NOT_ALLOWED, result.failure());
        assertTrue(result.message().contains("999999"), result.message());
        verify(speaker, never()).postOnce(anyString(), anyMap());
        verify(speaker, never()).get(anyString(), any());
        assertNull(lastEvent().streamer(), "认不出的房间不该挂到哪位主播名下");
    }

    /**
     * 抓的用户故障：发言账号被暂停时，监听账号那边也一起停了（选的是单独账号）
     */
    @Test
    @DisplayName("发言账号暂停中，换成监听账号照常发；两边各算各的")
    void pauseIsPerAccount() {
        speakerReady();
        listenerReady();
        when(speaker.postOnce(anyString(), anyMap())).thenReturn(reply(10031, "频率过快"));

        assertEquals(DanmakuFailure.TOO_FAST, service.send(ROOM, "第一条").failure());
        later(Duration.ofSeconds(5));
        assertEquals(DanmakuFailure.PAUSED, service.send(ROOM, "第二条").failure());

        choose(DanmakuAccountChoice.LISTENER);
        assertTrue(service.send(ROOM, "第三条").sent(), "发言账号的暂停连累到了监听账号");
        verify(listenerApi, times(1)).postFormOnce(anyString(), anyMap());
    }
}
