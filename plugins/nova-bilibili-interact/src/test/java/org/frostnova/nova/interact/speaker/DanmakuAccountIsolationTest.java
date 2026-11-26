package org.frostnova.nova.interact.speaker;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.exception.RiskCooldownException;
import org.frostnova.nova.bilibili.health.BilibiliRiskMetrics;
import org.frostnova.nova.bilibili.model.Cookies;
import org.frostnova.nova.bilibili.service.BilibiliAccountService;
import org.frostnova.nova.bilibili.service.BilibiliCredentialStore;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.util.HttpUtil;
import org.frostnova.nova.interact.config.InteractProperties;
import org.frostnova.nova.interact.config.InteractRuntimeAppliers;
import org.frostnova.nova.interact.danmaku.DanmakuAccountChoice;
import org.frostnova.nova.interact.danmaku.DanmakuFailure;
import org.frostnova.nova.interact.danmaku.DanmakuResult;
import org.frostnova.nova.interact.danmaku.LiveDanmakuService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 发弹幕时两个账号各走各的
 * <p>
 * 两个账号都用真的 {@link BilibiliApiUtil}，只把出网那一层换成假的：按请求里带的是谁的凭据回话，
 * 并记下每一次发弹幕带的是谁的凭据、填的是谁的跨站校验口令。
 */
@DisplayName("发弹幕时两个账号互不借用、互不连累")
class DanmakuAccountIsolationTest {
    private static final String LISTENER_SESS = "listener-sess-value";

    private static final String LISTENER_JCT = "listener-jct-value";

    private static final String SPEAKER_SESS = "speaker-sess-value";

    private static final String SPEAKER_JCT = "speaker-jct-value";

    private static final long ROOM = 2002L;

    @TempDir
    Path dir;

    private BilibiliApiUtil listenerApi;

    private BilibiliSpeakerAccount speaker;

    private InteractProperties interact;

    private LiveDanmakuService service;

    /**
     * 每一次发弹幕：带的凭据与表单
     */
    private final List<Map<String, Object>> sends = new CopyOnWriteArrayList<>();

    private final List<String> sendCookies = new CopyOnWriteArrayList<>();

    /**
     * 发弹幕时按谁回什么：缺省回成功
     */
    private volatile String speakerReply = "{\"code\":0,\"data\":{},\"message\":\"\",\"msg\":\"\"}";

    private volatile String listenerReply = "{\"code\":0,\"data\":{},\"message\":\"\",\"msg\":\"\"}";

    private final List<TimelineEvent> events = new CopyOnWriteArrayList<>();

    private ListAppender<ILoggingEvent> logs;

    private final List<Logger> attached = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        HttpUtil http = mock(HttpUtil.class);
        when(http.getJson(anyString(), anyMap())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0);
            String cookie = ((Map<String, String>) invocation.getArgument(1)).getOrDefault("Cookie", "");
            if (url.contains("/x/space/v2/myinfo")) {
                long mid = cookie.contains(LISTENER_SESS) ? 42 : 7;
                return JSONObject.parseObject("{\"code\":0,\"data\":{\"profile\":{\"mid\":" + mid + "}}}");
            }
            if (url.contains("getInfoByUser")) {
                return JSONObject.parseObject("{\"code\":0,\"data\":{\"property\":{\"danmu\":{\"length\":30}}}}");
            }
            return null;
        });
        when(http.postJsonAsForm(anyString(), anyMap(), anyMap())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0);
            String cookie = ((Map<String, String>) invocation.getArgument(1)).getOrDefault("Cookie", "");
            if (!url.contains("/msg/send")) {
                return null;
            }
            sends.add(Map.copyOf((Map<String, Object>) invocation.getArgument(2)));
            sendCookies.add(cookie);
            return JSON.parseObject(cookie.contains(LISTENER_SESS) ? listenerReply : speakerReply);
        });

        NovaBilibiliProperties bilibili = new NovaBilibiliProperties();
        bilibili.getNetwork().setApiRetryMaxTimes(3);
        bilibili.getNetwork().setApiRetryInterval(0);
        listenerApi = new BilibiliApiUtil(http, bilibili, new BilibiliRiskMetrics());
        listenerApi.setCookies(new Cookies(LISTENER_SESS, LISTENER_JCT, "buvid-a"));

        interact = new InteractProperties();
        interact.getAccount().setCookiePath(dir.resolve("speaker-cookies.json").toString());
        interact.getAccount().setKeyPath(dir.resolve("speaker-cookies.key").toString());
        new BilibiliCredentialStore(new SpeakerAccountSettings(bilibili.getAccount(), interact.getAccount()))
                .save(new Cookies(SPEAKER_SESS, SPEAKER_JCT, "buvid-b"));

        BilibiliAccountService listener = mock(BilibiliAccountService.class);
        when(listener.getLoginUid()).thenReturn(42L);
        when(listener.isLoggedIn()).thenReturn(true);
        speaker = new BilibiliSpeakerAccount(http, bilibili, interact, listener);
        speaker.restore();
        assertTrue(speaker.isLoggedIn(), "发言账号没能用保存的凭据登上");

        AbstractDataSource source = mock(AbstractDataSource.class);
        PushUser user = new PushUser();
        user.setPlatform("bilibili");
        user.setUid(301L);
        user.setRoomId(ROOM);
        user.setUname("示例主播");
        user.setEnabled(true);
        when(source.getUsers("bilibili")).thenReturn(List.of(user));
        service = new LiveDanmakuService(interact, new BilibiliSpeakerService(speaker), listener, listenerApi,
                source, events::add);

        logs = new ListAppender<>();
        logs.start();
        for (Class<?> type : List.of(LiveDanmakuService.class, BilibiliApiUtil.class, BilibiliSpeakerAccount.class)) {
            Logger logger = (Logger) LoggerFactory.getLogger(type);
            logger.addAppender(logs);
            attached.add(logger);
        }
    }

    @AfterEach
    void tearDown() {
        speaker.onContextClosed();
        attached.forEach(logger -> logger.detachAppender(logs));
    }

    private void choose(DanmakuAccountChoice choice) {
        new InteractRuntimeAppliers(interact).batchApplier().apply()
                .accept(Map.of(InteractRuntimeAppliers.ACCOUNT, choice.value()));
    }

    /**
     * 抓的用户故障：选的是发言账号，弹幕带出去的却是监听账号的凭据或口令
     */
    @Test
    @DisplayName("选单独账号：发弹幕只带发言账号的凭据与口令")
    void speakerSendsWithOwnCredentialsOnly() {
        DanmakuResult result = service.send(ROOM, "晚上好");

        assertTrue(result.sent(), result.message());
        assertEquals(1, sends.size());
        assertTrue(sendCookies.get(0).contains(SPEAKER_SESS));
        assertFalse(sendCookies.get(0).contains(LISTENER_SESS), "发言账号的弹幕带上了监听账号的凭据");
        assertEquals(SPEAKER_JCT, sends.get(0).get("csrf"));
        assertEquals(SPEAKER_JCT, sends.get(0).get("csrf_token"));
        assertEquals("晚上好", sends.get(0).get("msg"));
        assertEquals(String.valueOf(ROOM), String.valueOf(sends.get(0).get("roomid")));
    }

    /**
     * 抓的用户故障：选了同一个账号，程序却另抄了一份凭据、或绕开了监听账号的风控冷却照发
     */
    @Test
    @DisplayName("选同一个账号：经监听账号那一个接口工具发出，带它的口令")
    void listenerSendsThroughItsApi() {
        choose(DanmakuAccountChoice.LISTENER);

        DanmakuResult result = service.send(ROOM, "主播好");

        assertTrue(result.sent(), result.message());
        assertEquals(1, sends.size());
        assertTrue(sendCookies.get(0).contains(LISTENER_SESS));
        assertFalse(sendCookies.get(0).contains(SPEAKER_SESS), "监听账号的弹幕带上了发言账号的凭据");
        assertEquals(LISTENER_JCT, sends.get(0).get("csrf"));
        assertEquals(LISTENER_JCT, sends.get(0).get("csrf_token"));
    }

    /**
     * 抓的用户故障：监听账号的接口已经被平台拦进冷却，选了同一个账号的弹幕却绕开冷却照发
     */
    @Test
    @DisplayName("选同一个账号：受监听账号那一份风控冷却管，冷却中不出网")
    void listenerCooldownAppliesToDanmaku() {
        choose(DanmakuAccountChoice.LISTENER);
        listenerReply = "{\"code\":-412,\"message\":\"请求被拦截\"}";
        listenerApi.postFormOnce("https://api.live.bilibili.com/msg/send", Map.of("msg", "别处的一次"));
        assertThrows(RiskCooldownException.class,
                () -> listenerApi.postFormOnce("https://api.live.bilibili.com/msg/send", Map.of("msg", "冷却中")));
        int before = sends.size();

        DanmakuResult cooling = service.send(ROOM, "冷却中发一条");

        assertEquals(DanmakuFailure.RISK_CONTROL, cooling.failure(), cooling.message());
        assertEquals(before, sends.size(), "监听账号的接口在冷却，发弹幕还是出网了");
    }

    /**
     * 抓的用户故障：发言账号被平台说太频繁、暂停之后，监听账号的请求也一起被拦
     */
    @Test
    @DisplayName("发言账号被暂停：监听账号的请求照常，凭据值不进日志、时间线与结果")
    void speakerPauseLeavesListenerAlone() {
        speakerReply = "{\"code\":10031,\"message\":\"您发送弹幕的频率过快\",\"msg\":\"您发送弹幕的频率过快\"}";

        DanmakuResult limited = service.send(ROOM, "第一条");
        assertEquals(DanmakuFailure.TOO_FAST, limited.failure());
        assertEquals(DanmakuFailure.PAUSED, service.send(ROOM, "第二条").failure());
        assertEquals(1, sends.size(), "暂停期内又发了请求");

        assertEquals(42L, listenerApi.fetchLoginUid(), "发言账号的暂停连累到了监听账号");
        assertTrue(sendCookies.stream().noneMatch(cookie -> cookie.contains(LISTENER_SESS)),
                "选的是发言账号，监听账号却发过弹幕");

        List<String> texts = new ArrayList<>();
        logs.list.forEach(event -> texts.add(event.getFormattedMessage()));
        events.forEach(event -> {
            texts.add(String.valueOf(event.text()));
            texts.addAll(event.detail().values());
        });
        texts.add(limited.message());
        for (String text : texts) {
            for (String secret : List.of(LISTENER_SESS, LISTENER_JCT, SPEAKER_SESS, SPEAKER_JCT)) {
                assertFalse(text.contains(secret), "凭据值出现在了日志、时间线或结果里: " + text);
            }
        }
        assertFalse(events.isEmpty());
    }
}
