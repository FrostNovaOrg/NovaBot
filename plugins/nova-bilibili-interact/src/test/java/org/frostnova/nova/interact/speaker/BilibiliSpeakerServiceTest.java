package org.frostnova.nova.interact.speaker;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.model.Cookies;
import org.frostnova.nova.bilibili.service.BilibiliAccountService;
import org.frostnova.nova.bilibili.service.BilibiliCredentialStore;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.interact.config.InteractProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用发言账号发请求的入口
 * <p>
 * 抓的用户故障：发言账号没登录、失效或与监听账号成了同一个号时，请求照样发了出去——
 * 甚至换成监听账号的凭据发，风险就落到了监听账号头上。
 */
@DisplayName("发言账号的请求入口")
class BilibiliSpeakerServiceTest {
    private static final String URL = "https://api.example.invalid/x/demo";

    private ScheduledExecutorService worker;

    private BilibiliApiUtil api;

    private BilibiliAccountService listener;

    private BilibiliCredentialStore store;

    private BilibiliSpeakerAccount speaker;

    private BilibiliSpeakerService service;

    @BeforeEach
    void setUp() {
        worker = Executors.newSingleThreadScheduledExecutor();
        api = mock(BilibiliApiUtil.class);
        listener = mock(BilibiliAccountService.class);
        when(listener.getLoginUid()).thenReturn(42L);
        store = mock(BilibiliCredentialStore.class);
        when(store.load()).thenReturn(Optional.empty());
        speaker = new BilibiliSpeakerAccount(api,
                new SpeakerAccountSettings(new NovaBilibiliProperties().getAccount(), new InteractProperties().getAccount()),
                store, listener, worker);
        service = new BilibiliSpeakerService(speaker);
        when(api.requestBilibiliApi(anyString(), anyString(), anyMap(), any())).thenReturn(new JSONObject());
        when(api.getBilibiliHeaders()).thenReturn(Map.of("Cookie", "SESSDATA=speaker"));
    }

    @AfterEach
    void tearDown() {
        worker.shutdownNow();
    }

    private void loginAs(long uid) {
        Cookies cookies = new Cookies("speaker-sess", "speaker-jct", "buvid");
        when(store.load()).thenReturn(Optional.of(cookies));
        when(api.fetchLoginUid()).thenReturn(uid);
        when(api.getCookies()).thenReturn(cookies);
        speaker.restore();
    }

    @Test
    @DisplayName("没登录：答「不能用」并说明，请求一个都不发")
    void refusesWhenNotLoggedIn() {
        assertFalse(service.available());
        assertEquals(Optional.of("还没有登录发言账号"), service.unavailableReason());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.post(URL, Map.of("msg", "你好")));
        assertEquals("还没有登录发言账号", e.getMessage());
        verify(api, never()).requestBilibiliApi(anyString(), anyString(), anyMap(), any());
    }

    @Test
    @DisplayName("能用时：用发言账号自己的登录态发，跨站校验口令由入口填上")
    void postsWithOwnCredentials() {
        loginAs(7L);

        assertTrue(service.available());
        assertEquals(Optional.of(7L), service.uid());
        service.post(URL, Map.of("msg", "你好"));

        verify(api).requestBilibiliApi(eq(URL), eq("POST"), eq(Map.of("Cookie", "SESSDATA=speaker")),
                argThat(form -> "speaker-jct".equals(form.get("csrf")) && "speaker-jct".equals(form.get("csrf_token"))
                        && "你好".equals(form.get("msg"))));
    }

    @Test
    @DisplayName("与监听账号成了同一个号：不再发，理由说清楚")
    void refusesWhenSameAsListener() {
        loginAs(7L);
        when(listener.getLoginUid()).thenReturn(7L);

        assertFalse(service.available());
        assertEquals(Optional.of(BilibiliSpeakerAccount.SAME_AS_LISTENER_NOW), service.unavailableReason());
        assertThrows(IllegalStateException.class, () -> service.get(URL, null));
        verify(api, never()).requestBilibiliApi(anyString(), anyString(), anyMap(), any());
    }
}
