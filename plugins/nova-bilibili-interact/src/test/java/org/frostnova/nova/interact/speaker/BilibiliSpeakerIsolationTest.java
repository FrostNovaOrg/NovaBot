package org.frostnova.nova.interact.speaker;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.exception.RiskCooldownException;
import org.frostnova.nova.bilibili.health.BilibiliRiskMetrics;
import org.frostnova.nova.bilibili.model.Cookies;
import org.frostnova.nova.bilibili.service.BilibiliAccountService;
import org.frostnova.nova.bilibili.service.BilibiliCredentialStore;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.util.HttpUtil;
import org.frostnova.nova.interact.config.InteractProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 两个账号各用各的接口工具
 * <p>
 * 用的是真的 {@link BilibiliApiUtil}，只把出网那一层换成假的：按请求里带的是谁的凭据回话。
 * 抓的用户故障：一个账号被风控拦下、进了冷却，另一个账号的同一个接口也跟着被拦；
 * 或者一个账号的请求带出去的是另一个账号的凭据。
 */
@DisplayName("发言账号与监听账号互不影响")
class BilibiliSpeakerIsolationTest {
    private static final String LISTENER_SESS = "listener-sess-value";

    private static final String SPEAKER_SESS = "speaker-sess-value";

    @TempDir
    Path dir;

    private HttpUtil http;

    private NovaBilibiliProperties bilibili;

    private BilibiliApiUtil listenerApi;

    private BilibiliSpeakerAccount speaker;

    /**
     * 谁的请求被拦：这一边的账号问「我是谁」时回 -412
     */
    private volatile String blocked = "";

    private final List<String> sentCookies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        http = mock(HttpUtil.class);
        when(http.getJson(anyString(), anyMap())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0);
            Map<String, String> headers = invocation.getArgument(1);
            String cookie = headers == null ? "" : headers.getOrDefault("Cookie", "");
            if (!url.contains("/x/space/v2/myinfo")) {
                return null;
            }
            sentCookies.add(cookie);
            String who = cookie.contains(LISTENER_SESS) ? "listener" : cookie.contains(SPEAKER_SESS) ? "speaker" : "";
            if (who.equals(blocked)) {
                return JSONObject.parseObject("{\"code\":-412,\"message\":\"请求被拦截\"}");
            }
            long mid = who.equals("listener") ? 42 : 7;
            return JSONObject.parseObject("{\"code\":0,\"data\":{\"profile\":{\"mid\":" + mid + "}}}");
        });

        bilibili = new NovaBilibiliProperties();
        bilibili.getNetwork().setApiRetryMaxTimes(1);
        listenerApi = new BilibiliApiUtil(http, bilibili, new BilibiliRiskMetrics());
        listenerApi.setCookies(new Cookies(LISTENER_SESS, "listener-jct", "buvid-a"));

        InteractProperties interact = new InteractProperties();
        interact.getAccount().setCookiePath(dir.resolve("speaker-cookies.json").toString());
        interact.getAccount().setKeyPath(dir.resolve("speaker-cookies.key").toString());
        new BilibiliCredentialStore(new SpeakerAccountSettings(bilibili.getAccount(), interact.getAccount()))
                .save(new Cookies(SPEAKER_SESS, "speaker-jct", "buvid-b"));

        BilibiliAccountService listener = mock(BilibiliAccountService.class);
        when(listener.getLoginUid()).thenReturn(42L);
        speaker = new BilibiliSpeakerAccount(http, bilibili, interact, listener);
    }

    @AfterEach
    void tearDown() {
        speaker.onContextClosed();
    }

    @Test
    @DisplayName("监听账号被风控拦下进了冷却：发言账号同一个接口照常请求，状态不变")
    void listenerCooldownDoesNotReachSpeaker() {
        blocked = "listener";
        assertThrows(RiskCooldownException.class, listenerApi::fetchLoginUid);
        // 冷却中再问一次：监听账号这一侧直接拒，不再出网
        int before = sentCookies.size();
        assertThrows(RiskCooldownException.class, listenerApi::fetchLoginUid);
        assertEquals(before, sentCookies.size(), "冷却中的监听账号不该再出网");

        speaker.restore();

        assertTrue(speaker.isLoggedIn(), "监听账号的冷却连累到了发言账号");
        assertEquals(7L, speaker.uid());
        assertTrue(sentCookies.get(sentCookies.size() - 1).contains(SPEAKER_SESS));
    }

    @Test
    @DisplayName("发言账号被风控拦下进了冷却：监听账号同一个接口照常请求")
    void speakerCooldownDoesNotReachListener() {
        speaker.restore();
        assertTrue(speaker.isLoggedIn());

        blocked = "speaker";
        assertThrows(RiskCooldownException.class, () -> speaker.api().fetchLoginUid());

        assertEquals(42L, listenerApi.fetchLoginUid(), "发言账号的冷却连累到了监听账号");
    }

    @Test
    @DisplayName("各自的请求只带各自的凭据")
    void eachAccountSendsOnlyItsOwnCredentials() {
        speaker.restore();
        listenerApi.fetchLoginUid();
        speaker.api().fetchLoginUid();

        assertFalse(sentCookies.isEmpty());
        for (String cookie : sentCookies) {
            assertFalse(cookie.contains(LISTENER_SESS) && cookie.contains(SPEAKER_SESS), "一个请求里带了两个账号的凭据");
        }
        assertTrue(speaker.api().getBilibiliHeaders().get("Cookie").contains(SPEAKER_SESS));
        assertFalse(speaker.api().getBilibiliHeaders().get("Cookie").contains(LISTENER_SESS));
        assertFalse(listenerApi.getBilibiliHeaders().get("Cookie").contains(SPEAKER_SESS));
    }
}
